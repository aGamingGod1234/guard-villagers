package com.guardvillagers.compat.voicechat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Turns one player's push-to-talk Opus packets into finished utterances (ported from agent-arena, trimmed for
 * a single server and a single speech backend). Per player: an utterance starts on the first decoded frame
 * with speech, ends after a silence gap (shorter once the utterance is long) or at the sample cap, and goes
 * to the transcriber. Only one transcription per player runs at a time; a newer finished utterance waits and
 * replaces any older waiting one. Decoder failures back off from 1 s doubling to 30 s. Audio is held in memory
 * only until the transcriber has it. Contains no Simple Voice Chat types so it can be unit tested.
 */
final class SpeechCaptureEngine implements AutoCloseable {
	static final long INITIAL_DECODER_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1L);
	static final long MAX_DECODER_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30L);
	private final Transcriber transcriber;
	private final ScheduledExecutorService scheduler;
	private final long minimumSilenceMillis;
	private final long maximumSilenceMillis;
	private final int adaptiveAfterSamples;
	private final int maxSamples;
	private final long transcriptionDeadlineMillis;
	private final LongSupplier nanos;
	private final Map<UUID, Utterance> utterances = new HashMap<>();
	private final Map<UUID, PlayerState> players = new HashMap<>();
	private boolean closed;

	@FunctionalInterface
	interface Transcriber {
		/** Starts transcription; the stage completing (any way) frees the player for the next utterance. */
		CompletionStage<?> transcribe(UUID playerId, short[] samples, long endpointMillis);
	}

	@FunctionalInterface
	interface DecoderFactory {
		Decoder create();
	}

	interface Decoder {
		short[] decode(byte[] opus);

		void close();
	}

	SpeechCaptureEngine(Transcriber transcriber, ScheduledExecutorService scheduler, long minimumSilenceMillis,
			long maximumSilenceMillis, int adaptiveAfterSamples, int maxSamples, long transcriptionDeadlineMillis,
			LongSupplier nanos) {
		this.transcriber = Objects.requireNonNull(transcriber, "transcriber");
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
		if (minimumSilenceMillis < 1L || maximumSilenceMillis < minimumSilenceMillis) {
			throw new IllegalArgumentException("silence bounds must be positive and ordered");
		}
		if (adaptiveAfterSamples < 1 || maxSamples < 1 || transcriptionDeadlineMillis < 1L) {
			throw new IllegalArgumentException("limits must be positive");
		}
		this.minimumSilenceMillis = minimumSilenceMillis;
		this.maximumSilenceMillis = maximumSilenceMillis;
		this.adaptiveAfterSamples = adaptiveAfterSamples;
		this.maxSamples = maxSamples;
		this.transcriptionDeadlineMillis = transcriptionDeadlineMillis;
		this.nanos = Objects.requireNonNull(nanos, "nanos");
	}

	/** Feeds one Opus packet from {@code playerId}. Call from a single thread (the server thread). */
	void accept(UUID playerId, byte[] opus, DecoderFactory decoderFactory) {
		Objects.requireNonNull(playerId, "playerId");
		Objects.requireNonNull(opus, "opus");
		Completed finished = null;
		synchronized (this) {
			if (closed) return;
			long now = nanos.getAsLong();
			PlayerState state = players.computeIfAbsent(playerId, ignored -> new PlayerState());
			if (now < state.decoderRetryAfterNanos) return;
			Utterance utterance = utterances.get(playerId);
			Decoder decoder;
			if (utterance == null) {
				try {
					decoder = Objects.requireNonNull(decoderFactory.create(), "decoder");
				} catch (RuntimeException failure) {
					recordDecoderFailure(state, now);
					return;
				}
			} else {
				decoder = utterance.decoder;
			}
			short[] decoded;
			try {
				decoded = Objects.requireNonNull(decoder.decode(opus), "decoded");
			} catch (RuntimeException failure) {
				if (utterance == null) closeQuietly(decoder);
				else discard(playerId, utterance);
				recordDecoderFailure(state, now);
				return;
			}
			state.decoderFailures = 0;
			if (utterance == null) {
				if (!DecodedPcmSpeechDetector.hasSpeech(decoded)) {
					closeQuietly(decoder);
					return;
				}
				utterance = new Utterance(decoder, state, maxSamples);
				utterances.put(playerId, utterance);
			}
			utterance.append(decoded);
			utterance.lastPacketNanos = now;
			if (utterance.length >= maxSamples) {
				finished = finish(playerId, utterance);
			} else {
				if (utterance.timeout != null) utterance.timeout.cancel(false);
				Utterance current = utterance;
				long epoch = ++utterance.epoch;
				long delay = utterance.length >= adaptiveAfterSamples ? minimumSilenceMillis : maximumSilenceMillis;
				utterance.timeout = scheduler.schedule(() -> finishIfCurrent(playerId, current, epoch), delay,
						TimeUnit.MILLISECONDS);
			}
		}
		if (finished != null) submit(finished);
	}

	private void finishIfCurrent(UUID playerId, Utterance expected, long epoch) {
		Completed finished;
		synchronized (this) {
			if (closed || utterances.get(playerId) != expected || expected.epoch != epoch) return;
			finished = finish(playerId, expected);
		}
		submit(finished);
	}

	private Completed finish(UUID playerId, Utterance utterance) {
		utterances.remove(playerId, utterance);
		utterance.epoch++;
		if (utterance.timeout != null) utterance.timeout.cancel(false);
		closeQuietly(utterance.decoder);
		long endpointMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanos.getAsLong() - utterance.lastPacketNanos));
		return new Completed(playerId, utterance.state, Arrays.copyOf(utterance.samples, utterance.length), endpointMillis);
	}

	private void discard(UUID playerId, Utterance utterance) {
		utterances.remove(playerId, utterance);
		utterance.epoch++;
		if (utterance.timeout != null) utterance.timeout.cancel(false);
		closeQuietly(utterance.decoder);
	}

	private void recordDecoderFailure(PlayerState state, long now) {
		state.decoderFailures = Math.min(state.decoderFailures + 1, 31);
		long delay = Math.min(MAX_DECODER_RETRY_NANOS, INITIAL_DECODER_RETRY_NANOS << Math.min(state.decoderFailures - 1, 5));
		state.decoderRetryAfterNanos = now + delay;
	}

	/** Starts transcription now, or parks the utterance until the player's current one finishes. */
	private void submit(Completed utterance) {
		synchronized (this) {
			if (closed || players.get(utterance.playerId) != utterance.state) return;
			if (utterance.state.transcribing) {
				utterance.state.pending = utterance;
				return;
			}
			utterance.state.transcribing = true;
		}
		CompletableFuture<?> source;
		try {
			source = Objects.requireNonNull(
					transcriber.transcribe(utterance.playerId, utterance.samples, utterance.endpointMillis),
					"transcriber returned null").toCompletableFuture();
		} catch (RuntimeException failure) {
			source = CompletableFuture.failedFuture(failure);
		}
		CompletableFuture<?> request = source;
		ScheduledFuture<?> deadline;
		synchronized (this) {
			if (closed || players.get(utterance.playerId) != utterance.state) {
				request.cancel(true);
				return;
			}
			utterance.state.inFlight = request;
			Runnable expire = () -> request.cancel(true);
			deadline = scheduler.schedule(expire, transcriptionDeadlineMillis, TimeUnit.MILLISECONDS);
		}
		request.whenComplete((ignored, failure) -> {
			deadline.cancel(false);
			Completed next;
			synchronized (this) {
				if (closed || players.get(utterance.playerId) != utterance.state) return;
				utterance.state.transcribing = false;
				utterance.state.inFlight = null;
				next = utterance.state.pending;
				utterance.state.pending = null;
			}
			if (next != null) submit(next);
		});
	}

	/** Drops everything for the player: audio being captured, a waiting utterance, and the running request. */
	void cancel(UUID playerId) {
		CompletableFuture<?> running;
		synchronized (this) {
			Utterance active = utterances.get(playerId);
			if (active != null) discard(playerId, active);
			PlayerState state = players.remove(playerId);
			running = state == null ? null : state.inFlight;
		}
		if (running != null) running.cancel(true);
	}

	synchronized boolean capturing(UUID playerId) {
		return utterances.containsKey(playerId);
	}

	@Override
	public void close() {
		List<CompletableFuture<?>> running = new ArrayList<>();
		synchronized (this) {
			if (closed) return;
			closed = true;
			for (Utterance utterance : utterances.values()) {
				utterance.epoch++;
				if (utterance.timeout != null) utterance.timeout.cancel(false);
				closeQuietly(utterance.decoder);
			}
			utterances.clear();
			for (PlayerState state : players.values()) {
				if (state.inFlight != null) running.add(state.inFlight);
			}
			players.clear();
		}
		running.forEach(request -> request.cancel(true));
		scheduler.shutdownNow();
	}

	private static void closeQuietly(Decoder decoder) {
		try {
			decoder.close();
		} catch (RuntimeException ignored) {
			// A broken decoder is discarded either way.
		}
	}

	private static final class PlayerState {
		private boolean transcribing;
		private Completed pending;
		private CompletableFuture<?> inFlight;
		private int decoderFailures;
		private long decoderRetryAfterNanos = Long.MIN_VALUE;
	}

	private static final class Utterance {
		private final Decoder decoder;
		private final PlayerState state;
		private final int maxSamples;
		private short[] samples;
		private int length;
		private long lastPacketNanos;
		private ScheduledFuture<?> timeout;
		private long epoch;

		private Utterance(Decoder decoder, PlayerState state, int maxSamples) {
			this.decoder = decoder;
			this.state = state;
			this.maxSamples = maxSamples;
			this.samples = new short[Math.min(48_000, maxSamples)];
		}

		private void append(short[] decoded) {
			int accepted = Math.min(decoded.length, maxSamples - length);
			if (accepted <= 0) return;
			int required = length + accepted;
			if (required > samples.length) {
				samples = Arrays.copyOf(samples, Math.min(maxSamples, Math.max(required, samples.length * 2)));
			}
			System.arraycopy(decoded, 0, samples, length, accepted);
			length = required;
		}
	}

	private record Completed(UUID playerId, PlayerState state, short[] samples, long endpointMillis) {
	}
}
