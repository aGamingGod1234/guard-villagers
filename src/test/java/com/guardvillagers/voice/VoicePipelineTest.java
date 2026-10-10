package com.guardvillagers.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VoicePipelineTest {
	private static final UUID PLAYER = new UUID(7, 7);
	private final AtomicLong clock = new AtomicLong(10_000_000L);
	private final ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
	private final List<VoiceTranscriptListener.VoiceTranscript> transcripts = new ArrayList<>();
	private final List<UUID> failures = new ArrayList<>();

	private final class FakeStt implements SpeechToText {
		final AtomicInteger calls = new AtomicInteger();
		volatile CompletableFuture<Transcript> next = CompletableFuture.completedFuture(new Transcript("hold"));
		volatile String lastHint;

		@Override
		public CompletableFuture<Transcript> transcribe(UUID player, short[] pcm48k, String promptHint) {
			calls.incrementAndGet();
			lastHint = promptHint;
			return next;
		}
	}

	private static final class FakeTts implements TextToSpeech {
		final AtomicInteger calls = new AtomicInteger();

		@Override
		public CompletableFuture<short[]> synthesize(String text, String voice) {
			calls.incrementAndGet();
			return CompletableFuture.completedFuture(new short[] {(short) text.length(), 1});
		}

		@Override
		public String cacheNamespace() {
			return "fake";
		}
	}

	private VoicePipeline pipeline(VoiceConfig config, SpeechToText stt, TextToSpeech tts) {
		VoiceRateLimiter limiter = new VoiceRateLimiter(config.rateLimits(), 6, clock::get);
		VoicePipeline pipeline = new VoicePipeline(config, stt, tts, limiter, serverTasks::add, clock::get);
		pipeline.setBridgeSupplier(() -> Optional.of(new NoopBridge()));
		pipeline.setTranscriptListener(new VoiceTranscriptListener() {
			@Override
			public void onTranscript(VoiceTranscript transcript) {
				transcripts.add(transcript);
			}

			@Override
			public void onRecognitionFailed(UUID player) {
				failures.add(player);
			}
		});
		pipeline.setConsent(PLAYER, true);
		return pipeline;
	}

	private void runServerTasks() {
		Runnable task;
		while ((task = serverTasks.poll()) != null) task.run();
	}

	@Test
	void transcriptReachesTheListenerOnTheServerThread() {
		FakeStt stt = new FakeStt();
		VoicePipeline pipeline = pipeline(VoiceConfig.defaults(), stt, null);
		pipeline.setPromptHints(player -> "Alpha, Liam");
		pipeline.transcribe(PLAYER, new short[48_000], 300).join();
		assertTrue(transcripts.isEmpty(), "delivery waits for the server thread");
		runServerTasks();
		assertEquals(1, transcripts.size());
		assertEquals("hold", transcripts.getFirst().text());
		assertEquals(300, transcripts.getFirst().endpointMs());
		assertEquals("Alpha, Liam", stt.lastHint);
	}

	@Test
	void blankTranscriptsAndUnconsentedPlayersAreIgnored() {
		FakeStt stt = new FakeStt();
		stt.next = CompletableFuture.completedFuture(new SpeechToText.Transcript("   "));
		VoicePipeline pipeline = pipeline(VoiceConfig.defaults(), stt, null);
		pipeline.transcribe(PLAYER, new short[100], 0).join();
		runServerTasks();
		assertTrue(transcripts.isEmpty());
		UUID stranger = new UUID(9, 9);
		assertFalse(pipeline.isConsented(stranger), "consent defaults to off");
		assertFalse(pipeline.shouldCapture(stranger));
		pipeline.transcribe(stranger, new short[100], 0).join();
		assertEquals(1, stt.calls.get(), "no audio from an unconsented player reaches STT");
	}

	@Test
	void rateLimitBacksOffAndTellsThePlayerAtMostEvery30Seconds() {
		FakeStt stt = new FakeStt();
		stt.next = CompletableFuture.failedFuture(new SpeechException(SpeechException.Channel.STT,
				SpeechException.Kind.RATE_LIMITED, "429", 429, 4_000, null));
		VoicePipeline pipeline = pipeline(VoiceConfig.defaults(), stt, null);
		pipeline.transcribe(PLAYER, new short[100], 0).join();
		runServerTasks();
		assertEquals(List.of(PLAYER), failures);
		assertEquals(4_000L, pipeline.rateLimiter().backoffRemaining(SpeechException.Channel.STT));
		assertFalse(pipeline.shouldCapture(PLAYER), "packets are dropped before decoding during back-off");
		clock.addAndGet(5_000);
		assertTrue(pipeline.shouldCapture(PLAYER));
		pipeline.transcribe(PLAYER, new short[100], 0).join();
		runServerTasks();
		assertEquals(1, failures.size(), "second notice within 30 s is suppressed");
		clock.addAndGet(30_000);
		pipeline.transcribe(PLAYER, new short[100], 0).join();
		runServerTasks();
		assertEquals(2, failures.size());
	}

	@Test
	void rejectedKeyTurnsInputOffUntilReload() {
		FakeStt stt = new FakeStt();
		stt.next = CompletableFuture.failedFuture(new SpeechException(SpeechException.Channel.STT,
				SpeechException.Kind.AUTHENTICATION, "401", 401, 0, null));
		VoicePipeline pipeline = pipeline(VoiceConfig.defaults(), stt, null);
		pipeline.transcribe(PLAYER, new short[100], 0).join();
		assertFalse(pipeline.inputAvailable());
		assertEquals(VoicePipeline.Status.KEY_REJECTED, pipeline.status(PLAYER));
		pipeline.transcribe(PLAYER, new short[100], 0).join();
		assertEquals(1, stt.calls.get());
	}

	@Test
	void statusReportsMissingKeyAndConsent() {
		VoicePipeline noKey = pipeline(VoiceConfig.defaults(), null, null);
		assertEquals(VoicePipeline.Status.KEY_MISSING, noKey.status(PLAYER));
		VoicePipeline ready = pipeline(VoiceConfig.defaults(), new FakeStt(), null);
		assertEquals(VoicePipeline.Status.READY, ready.status(PLAYER));
		ready.setConsent(PLAYER, false);
		assertEquals(VoicePipeline.Status.CONSENT_OFF, ready.status(PLAYER));
		ready.setBridgeSupplier(Optional::empty);
		assertEquals(VoicePipeline.Status.SVC_NOT_INSTALLED, ready.status(PLAYER));
	}

	@Test
	void revokingConsentCancelsCapture() {
		VoicePipeline pipeline = pipeline(VoiceConfig.defaults(), new FakeStt(), null);
		NoopBridge bridge = new NoopBridge();
		pipeline.setBridgeSupplier(() -> Optional.of(bridge));
		pipeline.setConsent(PLAYER, false);
		assertEquals(List.of(PLAYER), bridge.cancelled);
	}

	@Test
	void repeatedLinesComeFromTheCache() {
		FakeTts tts = new FakeTts();
		VoicePipeline pipeline = pipeline(VoiceConfig.defaults(), new FakeStt(), tts);
		UUID guard = new UUID(3, 3);
		short[] first = pipeline.synthesize(guard, "Holding.").join();
		short[] second = pipeline.synthesize(guard, " Holding. ").join();
		assertArrayEquals(first, second);
		assertEquals(1, tts.calls.get(), "fixed callouts cost one API call");
		assertEquals(1, pipeline.ttsCache().hits());
		pipeline.synthesize(guard, "Area clear.").join();
		assertEquals(2, tts.calls.get(), "a different line is a new request");
	}

	@Test
	void closeCancelsInFlightRequests() {
		FakeStt stt = new FakeStt();
		CompletableFuture<SpeechToText.Transcript> hanging = new CompletableFuture<>();
		stt.next = hanging;
		VoicePipeline pipeline = pipeline(VoiceConfig.defaults(), stt, null);
		CompletableFuture<Void> done = pipeline.transcribe(PLAYER, new short[100], 0);
		assertFalse(done.isDone());
		pipeline.close();
		assertTrue(hanging.isCancelled());
		done.join();
		runServerTasks();
		assertTrue(transcripts.isEmpty());
	}

	private static final class NoopBridge implements VoiceBridge {
		final List<UUID> cancelled = new ArrayList<>();

		@Override
		public String name() {
			return "test";
		}

		@Override
		public Playback play(net.minecraft.world.entity.Entity speaker, short[] pcm48k, float distance) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void stop(UUID speakerId) {
		}

		@Override
		public void cancelCapture(UUID playerId) {
			cancelled.add(playerId);
		}
	}
}
