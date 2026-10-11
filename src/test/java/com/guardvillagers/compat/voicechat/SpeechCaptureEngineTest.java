package com.guardvillagers.compat.voicechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpeechCaptureEngineTest {
	private static final UUID PLAYER = new UUID(1, 1);
	private static final short[] SPEECH = frame(2_000);
	private static final short[] SILENCE = frame(0);
	private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
	private final ManualScheduler scheduler = new ManualScheduler();
	private final List<Call> calls = new ArrayList<>();
	private final AtomicInteger decodersClosed = new AtomicInteger();
	private SpeechCaptureEngine engine;

	private record Call(UUID player, short[] samples, long endpointMillis, CompletableFuture<Void> result) {
	}

	private SpeechCaptureEngine engine(int maxSamples) {
		engine = new SpeechCaptureEngine((player, samples, endpoint) -> {
			CompletableFuture<Void> result = new CompletableFuture<>();
			calls.add(new Call(player, samples, endpoint, result));
			return result;
		}, scheduler, 160, 300, 48_000 / 2, maxSamples, 20_000, nanos::get);
		return engine;
	}

	@AfterEach
	void close() {
		if (engine != null) engine.close();
	}

	private SpeechCaptureEngine.Decoder decoder(short[] output) {
		return new SpeechCaptureEngine.Decoder() {
			@Override
			public short[] decode(byte[] opus) {
				return output.clone();
			}

			@Override
			public void close() {
				decodersClosed.incrementAndGet();
			}
		};
	}

	private void packet(short[] decoded) {
		engine.accept(PLAYER, new byte[] {1}, () -> decoder(decoded));
	}

	@Test
	void silenceNeverStartsAnUtterance() {
		engine(480_000);
		packet(SILENCE);
		assertFalse(engine.capturing(PLAYER));
		assertEquals(1, decodersClosed.get(), "the probe decoder is closed");
		scheduler.runAll();
		assertTrue(calls.isEmpty());
	}

	@Test
	void utteranceEndsAfterTheSilenceGap() {
		engine(480_000);
		packet(SPEECH);
		packet(SPEECH);
		assertEquals(300, scheduler.lastDelayMillis, "short utterances wait 300 ms of silence");
		nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(300));
		scheduler.runAll();
		assertEquals(1, calls.size());
		assertEquals(SPEECH.length * 2, calls.getFirst().samples().length);
		assertEquals(300, calls.getFirst().endpointMillis());
		assertFalse(engine.capturing(PLAYER));
		assertEquals(1, decodersClosed.get());
	}

	@Test
	void longUtterancesUseTheShorterEndpoint() {
		engine(480_000);
		for (int i = 0; i < 26; i++) packet(SPEECH); // 26 x 960 > 24,000 samples
		assertEquals(160, scheduler.lastDelayMillis);
	}

	@Test
	void sampleCapFinishesImmediately() {
		engine(960 * 3);
		for (int i = 0; i < 3; i++) packet(SPEECH);
		assertEquals(1, calls.size());
		assertEquals(960 * 3, calls.getFirst().samples().length);
	}

	@Test
	void oneTranscriptionPerPlayerAndNewestWaitingUtteranceWins() {
		engine(960);
		packet(SPEECH); // utterance 1 -> transcribing
		packet(frame(1_000)); // utterance 2 waits
		packet(frame(3_000)); // utterance 3 replaces 2
		assertEquals(1, calls.size());
		calls.getFirst().result().complete(null);
		assertEquals(2, calls.size());
		assertEquals(3_000, calls.get(1).samples()[0], "the newest utterance is sent");
		calls.get(1).result().completeExceptionally(new RuntimeException("stt down"));
		packet(SPEECH);
		assertEquals(3, calls.size(), "a failed request frees the player");
	}

	@Test
	void cancelDropsCaptureWaitingAndRunningWork() {
		engine(960);
		packet(SPEECH);
		packet(frame(1_000));
		CompletableFuture<Void> running = calls.getFirst().result();
		engine.cancel(PLAYER);
		assertTrue(running.isCancelled());
		assertEquals(1, calls.size(), "the waiting utterance is discarded, not sent");
	}

	@Test
	void transcriptionDeadlineCancelsAStuckRequest() {
		engine(960);
		packet(SPEECH);
		scheduler.runAll();
		assertTrue(calls.getFirst().result().isCancelled());
		packet(SPEECH);
		assertEquals(2, calls.size());
	}

	@Test
	void decoderFailuresBackOffExponentially() {
		engine(480_000);
		AtomicInteger attempts = new AtomicInteger();
		SpeechCaptureEngine.DecoderFactory broken = () -> {
			attempts.incrementAndGet();
			throw new IllegalStateException("no opus");
		};
		engine.accept(PLAYER, new byte[] {1}, broken);
		engine.accept(PLAYER, new byte[] {1}, broken);
		assertEquals(1, attempts.get(), "retry waits 1 s");
		nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
		engine.accept(PLAYER, new byte[] {1}, broken);
		assertEquals(2, attempts.get());
		nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
		engine.accept(PLAYER, new byte[] {1}, broken);
		assertEquals(2, attempts.get(), "second failure waits 2 s");
		nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
		packet(SPEECH);
		assertTrue(engine.capturing(PLAYER), "a working decoder resets the back-off");
	}

	@Test
	void closeCancelsEverything() {
		engine(960);
		packet(SPEECH);
		CompletableFuture<Void> running = calls.getFirst().result();
		engine.close();
		assertTrue(running.isCancelled());
		packet(SPEECH);
		assertEquals(1, calls.size());
	}

	private static short[] frame(int amplitude) {
		short[] samples = new short[960];
		for (int i = 0; i < samples.length; i++) samples[i] = (short) (i % 2 == 0 ? amplitude : -amplitude);
		return samples;
	}

	/** Runs scheduled tasks only when the test says so. */
	private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
		private final List<Runnable> tasks = new ArrayList<>();
		private long lastDelayMillis;

		ManualScheduler() {
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			lastDelayMillis = unit.toMillis(delay);
			FakeFuture future = new FakeFuture(command);
			tasks.add(future);
			return future;
		}

		void runAll() {
			List<Runnable> due = new ArrayList<>(tasks);
			tasks.clear();
			due.forEach(Runnable::run);
		}
	}

	private static final class FakeFuture implements ScheduledFuture<Object>, Runnable {
		private final Runnable command;
		private boolean cancelled;

		FakeFuture(Runnable command) {
			this.command = command;
		}

		@Override
		public void run() {
			if (!cancelled) command.run();
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return 0;
		}

		@Override
		public int compareTo(Delayed other) {
			return 0;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			cancelled = true;
			return true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isDone() {
			return cancelled;
		}

		@Override
		public Object get() {
			return null;
		}

		@Override
		public Object get(long timeout, TimeUnit unit) {
			return null;
		}
	}
}
