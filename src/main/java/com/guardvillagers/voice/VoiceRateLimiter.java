package com.guardvillagers.voice;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Cost and abuse limits for the OpenAI key: per-player STT spacing and hourly audio budget, global in-flight
 * caps, per-owner callout spacing, LLM calls per player and server-wide per minute plus a server-wide LLM
 * in-flight cap, and Retry-After back-off per channel.
 * Thread-safe. The clock is injected so tests control time.
 */
public final class VoiceRateLimiter {
	private static final long HOUR_MILLIS = 3_600_000L;
	private static final long MINUTE_MILLIS = 60_000L;
	/** Bounds per-player bookkeeping on large servers; the least recently used player is forgotten first. */
	static final int MAX_TRACKED_PLAYERS = 1_024;

	public enum Decision {
		OK,
		/** OpenAI asked us to wait (429) or the channel is cooling down after failures. */
		BACKOFF,
		/** The player's previous request was too recent. */
		TOO_SOON,
		/** The player used their audio seconds for this hour. */
		HOURLY_CAP,
		/** Too many requests in flight server-wide. */
		BUSY;

		public boolean allowed() {
			return this == OK;
		}
	}

	private final VoiceConfig.RateLimits limits;
	static final int DEFAULT_LLM_GLOBAL_PER_MINUTE = 60;
	static final int DEFAULT_LLM_IN_FLIGHT = 4;
	private final int llmPerMinute;
	private final int llmGlobalPerMinute;
	private final int llmMaxInFlight;
	private final ArrayDeque<Long> llmCallsGlobal = new ArrayDeque<>();
	private int llmInFlight;
	private final LongSupplier clock;
	private final Map<SpeechException.Channel, Long> backoffUntil = new EnumMap<>(SpeechException.Channel.class);
	private final Map<UUID, PlayerUsage> players = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, PlayerUsage> eldest) {
			return size() > MAX_TRACKED_PLAYERS;
		}
	};
	private int sttInFlight;
	private int ttsInFlight;

	public VoiceRateLimiter(VoiceConfig.RateLimits limits, int llmPerMinute, LongSupplier clockMillis) {
		this(limits, llmPerMinute, DEFAULT_LLM_GLOBAL_PER_MINUTE, DEFAULT_LLM_IN_FLIGHT, clockMillis);
	}

	public VoiceRateLimiter(VoiceConfig.RateLimits limits, int llmPerMinute, int llmGlobalPerMinute, int llmMaxInFlight,
			LongSupplier clockMillis) {
		this.limits = Objects.requireNonNull(limits, "limits");
		this.llmPerMinute = llmPerMinute;
		this.llmGlobalPerMinute = llmGlobalPerMinute;
		this.llmMaxInFlight = Math.max(1, llmMaxInFlight);
		this.clock = Objects.requireNonNull(clockMillis, "clock");
	}

	public static VoiceRateLimiter fromConfig(VoiceConfig config) {
		VoiceConfig.Llm llm = config.llm();
		return new VoiceRateLimiter(config.rateLimits(), llm.maxPerPlayerPerMinute(), llm.maxPerMinuteGlobal(),
				llm.maxInFlight(), System::currentTimeMillis);
	}

	/**
	 * Cheap check before any audio is decoded: is STT backed off, or has the player used this hour's budget?
	 * Request spacing is not checked here, because a player may start the next sentence while the previous
	 * one is still being transcribed; {@link #sttSpacingRemaining} handles that when the request is made.
	 * Does not reserve anything.
	 */
	public synchronized Decision checkStt(UUID player) {
		long now = clock.getAsLong();
		if (now < backoffUntil.getOrDefault(SpeechException.Channel.STT, Long.MIN_VALUE)) return Decision.BACKOFF;
		PlayerUsage usage = players.get(player);
		long used = usage == null ? 0L : usage.sttMillisInLastHour(now);
		return used >= budgetMillis() ? Decision.HOURLY_CAP : Decision.OK;
	}

	/** Milliseconds until this player may start another STT request; 0 when allowed now. */
	public synchronized long sttSpacingRemaining(UUID player) {
		PlayerUsage usage = players.get(player);
		if (usage == null) return 0L;
		return Math.max(0L, limits.sttMinIntervalMs() - (clock.getAsLong() - usage.lastSttStart));
	}

	/**
	 * Reserves one STT request for {@code audioMillis} of speech. On {@link Decision#OK} the caller must call
	 * {@link #releaseStt()} exactly once when the request finishes.
	 */
	public synchronized Decision acquireStt(UUID player, long audioMillis) {
		long now = clock.getAsLong();
		if (now < backoffUntil.getOrDefault(SpeechException.Channel.STT, Long.MIN_VALUE)) return Decision.BACKOFF;
		PlayerUsage usage = players.computeIfAbsent(player, ignored -> new PlayerUsage());
		if (now - usage.lastSttStart < limits.sttMinIntervalMs()) return Decision.TOO_SOON;
		if (usage.sttMillisInLastHour(now) + Math.max(0L, audioMillis) > budgetMillis()) return Decision.HOURLY_CAP;
		if (sttInFlight >= limits.sttGlobalInFlight()) return Decision.BUSY;
		sttInFlight++;
		usage.lastSttStart = now;
		usage.sttWindow.addLast(new long[] {now, Math.max(0L, audioMillis)});
		usage.sttWindowMillis += Math.max(0L, audioMillis);
		return Decision.OK;
	}

	public synchronized void releaseStt() {
		if (sttInFlight > 0) sttInFlight--;
	}

	/** Reserves one TTS API call; on OK call {@link #releaseTts()} when it finishes. Cache hits need no permit. */
	public synchronized Decision acquireTts() {
		if (clock.getAsLong() < backoffUntil.getOrDefault(SpeechException.Channel.TTS, Long.MIN_VALUE)) {
			return Decision.BACKOFF;
		}
		if (ttsInFlight >= limits.ttsGlobalInFlight()) return Decision.BUSY;
		ttsInFlight++;
		return Decision.OK;
	}

	public synchronized void releaseTts() {
		if (ttsInFlight > 0) ttsInFlight--;
	}

	/** At most one spoken callout per owner per {@code calloutMinIntervalMs}; records the callout when allowed. */
	public synchronized Decision tryCallout(UUID owner) {
		long now = clock.getAsLong();
		PlayerUsage usage = players.computeIfAbsent(owner, ignored -> new PlayerUsage());
		if (now - usage.lastCallout < limits.calloutMinIntervalMs()) return Decision.TOO_SOON;
		usage.lastCallout = now;
		return Decision.OK;
	}

	/**
	 * Reserves one LLM interpretation: at most {@code llm.maxPerPlayerPerMinute} per player and
	 * {@code llm.maxPerMinuteGlobal} server-wide in any 60 s window, and {@code llm.maxInFlight} waiting at once.
	 * On {@link Decision#OK} the caller must call {@link #releaseLlm()} exactly once when the request ends.
	 */
	public synchronized Decision acquireLlm(UUID player) {
		long now = clock.getAsLong();
		if (now < backoffUntil.getOrDefault(SpeechException.Channel.LLM, Long.MIN_VALUE)) return Decision.BACKOFF;
		PlayerUsage usage = players.computeIfAbsent(player, ignored -> new PlayerUsage());
		while (!usage.llmCalls.isEmpty() && now - usage.llmCalls.peekFirst() >= MINUTE_MILLIS) usage.llmCalls.pollFirst();
		if (usage.llmCalls.size() >= llmPerMinute) return Decision.TOO_SOON;
		while (!llmCallsGlobal.isEmpty() && now - llmCallsGlobal.peekFirst() >= MINUTE_MILLIS) llmCallsGlobal.pollFirst();
		if (llmCallsGlobal.size() >= llmGlobalPerMinute || llmInFlight >= llmMaxInFlight) return Decision.BUSY;
		usage.llmCalls.addLast(now);
		llmCallsGlobal.addLast(now);
		llmInFlight++;
		return Decision.OK;
	}

	public synchronized void releaseLlm() {
		if (llmInFlight > 0) llmInFlight--;
	}

	synchronized int llmInFlight() {
		return llmInFlight;
	}

	/** Blocks the channel for {@code millis} (capped at 300 s); a longer existing back-off is kept. */
	public synchronized void backOff(SpeechException.Channel channel, long millis) {
		long capped = Math.max(0L, Math.min(OpenAiHttp.MAX_RETRY_AFTER_MILLIS, millis));
		long until = clock.getAsLong() + capped;
		backoffUntil.merge(channel, until, Math::max);
	}

	/** Milliseconds until the channel accepts requests again; 0 when open. */
	public synchronized long backoffRemaining(SpeechException.Channel channel) {
		Long until = backoffUntil.get(channel);
		// No entry means never backed off; subtracting from Long.MIN_VALUE would overflow to a huge wait.
		return until == null ? 0L : Math.max(0L, until - clock.getAsLong());
	}

	/** Remaining STT audio budget for this player in the current rolling hour, in milliseconds. */
	public synchronized long sttBudgetRemaining(UUID player) {
		PlayerUsage usage = players.get(player);
		long used = usage == null ? 0L : usage.sttMillisInLastHour(clock.getAsLong());
		return Math.max(0L, budgetMillis() - used);
	}

	public synchronized void forget(UUID player) {
		players.remove(player);
	}

	synchronized int sttInFlight() {
		return sttInFlight;
	}

	private long budgetMillis() {
		return limits.sttSecondsPerPlayerPerHour() * 1_000L;
	}

	private static final class PlayerUsage {
		private long lastSttStart = Long.MIN_VALUE / 2;
		private long lastCallout = Long.MIN_VALUE / 2;
		private final ArrayDeque<long[]> sttWindow = new ArrayDeque<>();
		private long sttWindowMillis;
		private final ArrayDeque<Long> llmCalls = new ArrayDeque<>();

		private long sttMillisInLastHour(long now) {
			while (!sttWindow.isEmpty() && now - sttWindow.peekFirst()[0] >= HOUR_MILLIS) {
				sttWindowMillis -= sttWindow.pollFirst()[1];
			}
			return sttWindowMillis;
		}
	}
}
