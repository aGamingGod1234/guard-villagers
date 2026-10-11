package com.guardvillagers.voice;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One server's voice pipeline: captured PCM -> rate limits -> speech-to-text -> transcript listener, and
 * text -> cached text-to-speech -> {@link VoiceBridge} playback. Audio lives only in memory and is dropped as
 * soon as the request finishes. Transcripts are logged only at DEBUG.
 */
public final class VoicePipeline implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("guardvillagers/voice");
	static final long FAILURE_NOTICE_INTERVAL_MILLIS = 30_000L;
	static final long TRANSIENT_FAILURE_BACKOFF_MILLIS = 5_000L;

	public enum Status {
		/** Simple Voice Chat is not installed or its server is not running. */
		SVC_NOT_INSTALLED,
		/** The environment variable named in {@code openai.apiKeyEnv} is not set. */
		KEY_MISSING,
		/** Disabled in config. */
		DISABLED,
		/** OpenAI rejected the key; fixed by {@code /guards voice reload} after correcting it. */
		KEY_REJECTED,
		CONSENT_OFF,
		READY
	}

	public enum SpeakResult {
		PLAYING,
		/** No voice-chat bridge: show the line in chat instead. */
		NO_BRIDGE,
		TTS_UNAVAILABLE,
		RATE_LIMITED,
		FAILED,
		SPEAKER_GONE,
		CLOSED
	}

	private final VoiceConfig config;
	private final SpeechToText stt;
	private final TextToSpeech tts;
	private final TtsCache cache;
	private final VoiceRateLimiter limiter;
	private final Executor serverExecutor;
	private final LongSupplier clockMillis;
	private final java.util.function.BiConsumer<Runnable, Long> schedule;
	private final Set<SpeechException.Channel> rejectedKey = Collections.synchronizedSet(
			EnumSet.noneOf(SpeechException.Channel.class));
	private final Map<UUID, Long> lastFailureNotice = new LinkedHashMap<>();
	private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
	private volatile VoiceTranscriptListener listener = transcript -> LOGGER.debug(
			"Voice transcript from {} has no listener wired", transcript.player());
	private volatile VoiceConsentStore consent = VoiceConsentStore.inMemory();
	private volatile Function<UUID, String> promptHints = player -> null;
	private volatile Supplier<Optional<VoiceBridge>> bridge = VoiceBridge::current;
	private volatile boolean closed;
	/** The HTTP transport {@link #create} built for this pipeline; closed with it. */
	private AutoCloseable transport;

	/**
	 * @param stt null when no key is configured
	 * @param tts null when no key is configured or TTS is disabled
	 * @param serverExecutor runs listener callbacks and playback on the server thread
	 */
	public VoicePipeline(VoiceConfig config, SpeechToText stt, TextToSpeech tts, VoiceRateLimiter limiter,
			Executor serverExecutor, LongSupplier clockMillis) {
		this(config, stt, tts, limiter, serverExecutor, clockMillis,
				(task, delay) -> CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(task));
	}

	VoicePipeline(VoiceConfig config, SpeechToText stt, TextToSpeech tts, VoiceRateLimiter limiter,
			Executor serverExecutor, LongSupplier clockMillis, java.util.function.BiConsumer<Runnable, Long> schedule) {
		this.schedule = Objects.requireNonNull(schedule, "schedule");
		this.config = Objects.requireNonNull(config, "config");
		this.stt = stt;
		this.tts = tts;
		this.cache = TtsCache.ofMiB(config.tts().cacheMiB());
		this.limiter = Objects.requireNonNull(limiter, "limiter");
		this.serverExecutor = Objects.requireNonNull(serverExecutor, "serverExecutor");
		this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
	}

	/** Builds the OpenAI-backed pipeline; without a key both backends are absent and voice reports KEY_MISSING. */
	public static VoicePipeline create(VoiceConfig config, Optional<String> apiKey, Executor serverExecutor) {
		SpeechToText stt = null;
		TextToSpeech tts = null;
		OpenAiHttp http = null;
		if (apiKey.isPresent()) {
			http = OpenAiHttp.create(config.openai().baseUrl(), apiKey.get());
			stt = new OpenAiSpeechToText(http, config.openai().sttModel(), config.openai().sttLanguage(),
					Duration.ofMillis(config.openai().sttTimeoutMs()));
			if (config.tts().enabled()) {
				tts = new OpenAiTextToSpeech(http, config.openai().ttsModel(), config.tts().tone(),
						Duration.ofMillis(config.openai().ttsTimeoutMs()));
			}
		}
		VoicePipeline pipeline = new VoicePipeline(config, stt, tts, VoiceRateLimiter.fromConfig(config), serverExecutor,
				System::currentTimeMillis);
		pipeline.transport = http;
		return pipeline;
	}

	public VoiceConfig config() {
		return config;
	}

	public VoiceRateLimiter rateLimiter() {
		return limiter;
	}

	public TtsCache ttsCache() {
		return cache;
	}

	public void setTranscriptListener(VoiceTranscriptListener listener) {
		this.listener = Objects.requireNonNull(listener, "listener");
	}

	public void setConsentStore(VoiceConsentStore consent) {
		this.consent = Objects.requireNonNull(consent, "consent");
	}

	/** Names (groups, guards) passed to STT as a spelling hint; may return null. */
	public void setPromptHints(Function<UUID, String> promptHints) {
		this.promptHints = Objects.requireNonNull(promptHints, "promptHints");
	}

	void setBridgeSupplier(Supplier<Optional<VoiceBridge>> bridge) {
		this.bridge = bridge;
	}

	// ---- status and consent ----

	public boolean inputAvailable() {
		return !closed && config.voiceInput().enabled() && stt != null && !rejectedKey.contains(SpeechException.Channel.STT);
	}

	public boolean outputAvailable() {
		return !closed && config.tts().enabled() && tts != null && !rejectedKey.contains(SpeechException.Channel.TTS);
	}

	public boolean isConsented(UUID player) {
		return consent.get(player).orElse(config.voiceInput().consentDefault());
	}

	/** Stores the choice; revoking also drops any utterance being captured or transcribed for the player. */
	public void setConsent(UUID player, boolean granted) {
		consent.set(player, granted);
		if (!granted) bridge.get().ifPresent(active -> active.cancelCapture(player));
	}

	public Status status(UUID player) {
		if (bridge.get().isEmpty()) return Status.SVC_NOT_INSTALLED;
		if (stt == null) return Status.KEY_MISSING;
		if (!config.voiceInput().enabled()) return Status.DISABLED;
		if (rejectedKey.contains(SpeechException.Channel.STT)) return Status.KEY_REJECTED;
		if (!isConsented(player)) return Status.CONSENT_OFF;
		return Status.READY;
	}

	/**
	 * Cheap gate run on the server thread before any audio is decoded: input on, player consented, and STT
	 * would accept a request now. Proximity to the player's own guards is checked by {@link VoiceRuntime}.
	 */
	public boolean shouldCapture(UUID player) {
		return inputAvailable() && isConsented(player) && limiter.checkStt(player).allowed();
	}

	// ---- speech in ----

	/**
	 * Transcribes one finished utterance and hands a non-blank transcript to the listener on the server thread.
	 * The returned future always completes normally once the request is over, so the capture engine can start
	 * the player's next utterance; it is cancelled if the pipeline closes.
	 *
	 * @param endpointMs time from the last audio packet to end-of-speech detection, for the latency log
	 */
	public CompletableFuture<Void> transcribe(UUID player, short[] pcm48k, long endpointMs) {
		if (!inputAvailable() || !isConsented(player) || pcm48k.length == 0) return CompletableFuture.completedFuture(null);
		long wait = limiter.sttSpacingRemaining(player);
		if (wait <= 0L) return startTranscription(player, pcm48k, endpointMs);
		// The capture engine runs one request per player at a time, so waiting out the spacing here delays this
		// sentence instead of dropping it. Cancelling the returned future before the wait ends skips the request;
		// cancelling it later cancels the request itself.
		CompletableFuture<Void> result = new CompletableFuture<>();
		this.schedule.accept(() -> {
			if (result.isDone()) return;
			link(result, startTranscription(player, pcm48k, endpointMs + wait));
		}, wait);
		return result;
	}

	/** Completes {@code outer} with {@code inner}, and cancels {@code inner} when {@code outer} is cancelled. */
	private static void link(CompletableFuture<Void> outer, CompletableFuture<Void> inner) {
		inner.whenComplete((ignored, failure) -> outer.complete(null));
		outer.whenComplete((ignored, failure) -> {
			if (outer.isCancelled()) inner.cancel(true);
		});
	}

	/**
	 * Starts one STT request. The permit and in-flight entry are released when the request itself ends, however it
	 * ends: the returned future is only a view, and the capture engine cancels it on disconnect, consent off or its
	 * deadline. Cancelling the returned future cancels the request.
	 */
	private CompletableFuture<Void> startTranscription(UUID player, short[] pcm48k, long endpointMs) {
		if (closed) return CompletableFuture.completedFuture(null);
		long audioMillis = pcm48k.length * 1000L / SpeechToText.INPUT_SAMPLE_RATE;
		VoiceRateLimiter.Decision decision = limiter.acquireStt(player, audioMillis);
		if (!decision.allowed()) {
			LOGGER.debug("Voice input from {} dropped: {}", player, decision);
			return CompletableFuture.completedFuture(null);
		}
		long started = System.nanoTime();
		CompletableFuture<SpeechToText.Transcript> request;
		try {
			request = Objects.requireNonNull(stt.transcribe(player, pcm48k, safeHint(player)), "stt returned null");
		} catch (RuntimeException failure) {
			request = CompletableFuture.failedFuture(failure);
		}
		inFlight.add(request);
		CompletableFuture<SpeechToText.Transcript> tracked = request;
		request.whenComplete((ignored, failure) -> {
			inFlight.remove(tracked);
			limiter.releaseStt();
		});
		CompletableFuture<Void> result = new CompletableFuture<>();
		request.whenComplete((transcript, failure) -> {
			try {
				deliver(player, transcript, failure, audioMillis, endpointMs, started);
			} finally {
				result.complete(null);
			}
		});
		result.whenComplete((ignored, failure) -> {
			if (result.isCancelled()) tracked.cancel(true);
		});
		return result;
	}

	private void deliver(UUID player, SpeechToText.Transcript transcript, Throwable failure, long audioMillis,
			long endpointMs, long started) {
		long sttMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		if (closed) return;
		if (failure != null) {
			if (SpeechException.find(failure) == null && isCancellation(failure)) return;
			onFailure(player, failure, SpeechException.Channel.STT);
			LOGGER.info("Voice input latency player={} audioMs={} endpointMs={} sttMs={} result=failed",
					player, audioMillis, endpointMs, sttMs);
			return;
		}
		LOGGER.info("Voice input latency player={} audioMs={} endpointMs={} sttMs={} totalMs={} result={}",
				player, audioMillis, endpointMs, sttMs, endpointMs + sttMs,
				transcript.isBlank() ? "no-speech" : "recognized");
		if (transcript.isBlank()) return;
		LOGGER.debug("Voice transcript from {}: {}", player, transcript.text());
		VoiceTranscriptListener target = listener;
		VoiceTranscriptListener.VoiceTranscript event = new VoiceTranscriptListener.VoiceTranscript(
				player, transcript.text(), endpointMs, sttMs, System.nanoTime());
		runOnServer(() -> {
			if (!closed && isConsented(player)) target.onTranscript(event);
		});
	}

	private static boolean isCancellation(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof java.util.concurrent.CancellationException) return true;
		}
		return false;
	}

	private String safeHint(UUID player) {
		try {
			return promptHints.apply(player);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private void onFailure(UUID player, Throwable failure, SpeechException.Channel channel) {
		SpeechException speech = SpeechException.find(failure);
		SpeechException.Kind kind = speech == null ? SpeechException.Kind.UNAVAILABLE : speech.kind();
		switch (kind) {
			case AUTHENTICATION -> {
				if (rejectedKey.add(channel)) {
					LOGGER.warn("OpenAI rejected the API key for {} (HTTP {}). Guard voice {} is off until "
							+ "the key is fixed and /guards voice reload runs.", channel, speech.httpStatus(),
							channel == SpeechException.Channel.STT ? "input" : "output");
				}
				return;
			}
			case RATE_LIMITED -> limiter.backOff(channel, speech.retryAfterMillis());
			case TIMEOUT, UNAVAILABLE, PROVIDER -> limiter.backOff(channel, TRANSIENT_FAILURE_BACKOFF_MILLIS);
			default -> {
			}
		}
		LOGGER.debug("Voice {} request failed: {}", channel, failure.toString());
		if (player != null && channel == SpeechException.Channel.STT && kind != SpeechException.Kind.MALFORMED
				&& kind != SpeechException.Kind.REJECTED && shouldNotify(player)) {
			VoiceTranscriptListener target = listener;
			runOnServer(() -> target.onRecognitionFailed(player));
		}
	}

	private synchronized boolean shouldNotify(UUID player) {
		long now = clockMillis.getAsLong();
		Long last = lastFailureNotice.get(player);
		if (last != null && now - last < FAILURE_NOTICE_INTERVAL_MILLIS) return false;
		lastFailureNotice.put(player, now);
		if (lastFailureNotice.size() > VoiceRateLimiter.MAX_TRACKED_PLAYERS) {
			lastFailureNotice.remove(lastFailureNotice.keySet().iterator().next());
		}
		return true;
	}

	// ---- speech out ----

	/**
	 * Speaks {@code text} from {@code speaker} (normally a guard) through the voice-chat bridge. The future
	 * completes when playback starts or with the reason it did not; callers should always show the line in
	 * chat too, so nobody waits on audio. Call from the server thread.
	 */
	public CompletableFuture<SpeakResult> speak(Entity speaker, String text) {
		if (closed) return CompletableFuture.completedFuture(SpeakResult.CLOSED);
		if (bridge.get().isEmpty()) return CompletableFuture.completedFuture(SpeakResult.NO_BRIDGE);
		if (!outputAvailable()) return CompletableFuture.completedFuture(SpeakResult.TTS_UNAVAILABLE);
		long started = System.nanoTime();
		UUID speakerId = speaker.getUUID();
		CompletableFuture<SpeakResult> result = new CompletableFuture<>();
		synthesize(speakerId, text).whenComplete((pcm, failure) -> {
			long ttsMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			if (failure != null) {
				SpeechException speech = SpeechException.find(failure);
				boolean limited = speech != null && speech.kind() == SpeechException.Kind.RATE_LIMITED;
				LOGGER.info("Voice output latency speaker={} ttsMs={} result=failed", speakerId, ttsMs);
				result.complete(limited ? SpeakResult.RATE_LIMITED : SpeakResult.FAILED);
				return;
			}
			runOnServer(() -> {
				if (closed) {
					result.complete(SpeakResult.CLOSED);
					return;
				}
				if (speaker.isRemoved() || !speaker.isAlive()) {
					result.complete(SpeakResult.SPEAKER_GONE);
					return;
				}
				Optional<VoiceBridge> active = bridge.get();
				if (active.isEmpty()) {
					result.complete(SpeakResult.NO_BRIDGE);
					return;
				}
				try {
					active.get().play(speaker, pcm, config.tts().distance());
					LOGGER.info("Voice output latency speaker={} ttsMs={} audioMs={} result=playing",
							speakerId, ttsMs, pcm.length * 1000L / TextToSpeech.OUTPUT_SAMPLE_RATE);
					result.complete(SpeakResult.PLAYING);
				} catch (RuntimeException exception) {
					LOGGER.warn("Guard voice playback failed: {}", exception.toString());
					result.complete(SpeakResult.FAILED);
				}
			});
		});
		return result;
	}

	/**
	 * 48 kHz PCM for {@code text} in the speaker's stable voice. Cached lines return at once without an API
	 * call; misses take a global TTS permit.
	 */
	public CompletableFuture<short[]> synthesize(UUID speakerId, String text) {
		if (!outputAvailable()) {
			return CompletableFuture.failedFuture(new SpeechException(SpeechException.Channel.TTS,
					SpeechException.Kind.REJECTED, "text-to-speech is unavailable"));
		}
		String normalized = text == null ? "" : text.strip();
		String voice = OpenAiTextToSpeech.voiceFor(speakerId);
		String key = TtsCache.key(tts.cacheNamespace(), voice, normalized);
		return cache.getOrSynthesize(key, () -> {
			VoiceRateLimiter.Decision decision = limiter.acquireTts();
			if (!decision.allowed()) {
				return CompletableFuture.failedFuture(new SpeechException(SpeechException.Channel.TTS,
						SpeechException.Kind.RATE_LIMITED, "local limit: " + decision));
			}
			CompletableFuture<short[]> request;
			try {
				request = tts.synthesize(normalized, voice);
			} catch (RuntimeException failure) {
				request = CompletableFuture.failedFuture(failure);
			}
			inFlight.add(request);
			CompletableFuture<short[]> tracked = request;
			return request.whenComplete((pcm, failure) -> {
				inFlight.remove(tracked);
				limiter.releaseTts();
				if (failure != null && !closed) onFailure(null, failure, SpeechException.Channel.TTS);
			});
		});
	}

	private void runOnServer(Runnable task) {
		try {
			serverExecutor.execute(() -> {
				try {
					task.run();
				} catch (RuntimeException exception) {
					LOGGER.error("Guard voice callback failed", exception);
				}
			});
		} catch (RuntimeException rejected) {
			// The server is stopping; nothing left to deliver to.
		}
	}

	/** Cancels in-flight OpenAI requests, stops delivering results and shuts the HTTP client down. */
	@Override
	public void close() {
		closed = true;
		for (CompletableFuture<?> request : inFlight.toArray(CompletableFuture[]::new)) request.cancel(true);
		inFlight.clear();
		cache.clear();
		AutoCloseable owned = transport;
		transport = null;
		if (owned != null) {
			try {
				owned.close();
			} catch (Exception exception) {
				LOGGER.debug("Guard voice HTTP client did not close cleanly: {}", exception.toString());
			}
		}
	}
}
