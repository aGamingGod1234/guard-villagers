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
 * caps, per-owner callout spacing, per-player LLM calls per minute, and Retry-After back-off per channel.
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
	private final int llmPerMinute;
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
		this.limits = Objects.requireNonNull(limits, "limits");
		this.llmPerMinute = llmPerMinute;
		this.clock = Objects.requireNonNull(clockMillis, "clock");
	}

	public static VoiceRateLimiter fromConfig(VoiceConfig config) {
		return new VoiceRateLimiter(config.rateLimits(), config.llm().maxPerPlayerPerMinute(), System::currentTimeMillis);
	}

	/**
	 * Cheap check before any audio is decoded: would an STT request for this player be refused right now?
	 * Does not reserve anything.
	 */
	public synchronized Decision checkStt(UUID player) {
		long now = clock.getAsLong();
		if (now < backoffUntil.getOrDefault(SpeechException.Channel.STT, Long.MIN_VALUE)) return Decision.BACKOFF;
		PlayerUsage usage = players.get(player);
		if (usage != null) {
			if (now - usage.lastSttStart < limits.sttMinIntervalMs()) return Decision.TOO_SOON;
			if (usage.sttMillisInLastHour(now) >= budgetMillis()) return Decision.HOURLY_CAP;
		} else if (budgetMillis() <= 0) {
			return Decision.HOURLY_CAP;
		}
		return Decision.OK;
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

	/** At most {@code llm.maxPerPlayerPerMinute} interpretations per player in any 60 s window. */
	public synchronized Decision tryLlm(UUID player) {
		long now = clock.getAsLong();
		if (now < backoffUntil.getOrDefault(SpeechException.Channel.LLM, Long.MIN_VALUE)) return Decision.BACKOFF;
		PlayerUsage usage = players.computeIfAbsent(player, ignored -> new PlayerUsage());
		while (!usage.llmCalls.isEmpty() && now - usage.llmCalls.peekFirst() >= MINUTE_MILLIS) usage.llmCalls.pollFirst();
		if (usage.llmCalls.size() >= llmPerMinute) return Decision.TOO_SOON;
		usage.llmCalls.addLast(now);
		return Decision.OK;
	}

	/** Blocks the channel for {@code millis} (capped at 300 s); a longer existing back-off is kept. */
	public synchronized void backOff(SpeechException.Channel channel, long millis) {
		long capped = Math.max(0L, Math.min(OpenAiHttp.MAX_RETRY_AFTER_MILLIS, millis));
		long until = clock.getAsLong() + capped;
		backoffUntil.merge(channel, until, Math::max);
	}

	/** Milliseconds until the channel accepts requests again; 0 when open. */
	public synchronized long backoffRemaining(SpeechException.Channel channel) {
		return Math.max(0L, backoffUntil.getOrDefault(channel, Long.MIN_VALUE) - clock.getAsLong());
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
