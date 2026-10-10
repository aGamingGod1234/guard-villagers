package com.guardvillagers.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.guardvillagers.voice.VoiceRateLimiter.Decision;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VoiceRateLimiterTest {
	private static final UUID A = new UUID(0, 1);
	private static final UUID B = new UUID(0, 2);
	private final AtomicLong now = new AtomicLong(1_000_000L);

	private VoiceRateLimiter limiter(int minIntervalMs, int secondsPerHour, int sttInFlight, int ttsInFlight) {
		return new VoiceRateLimiter(new VoiceConfig.RateLimits(minIntervalMs, secondsPerHour, sttInFlight, ttsInFlight, 4_000),
				6, now::get);
	}

	@Test
	void sttRequestsPerPlayerAreSpacedButOthersAreNot() {
		VoiceRateLimiter limiter = limiter(1_000, 300, 4, 4);
		assertEquals(Decision.OK, limiter.acquireStt(A, 500));
		limiter.releaseStt();
		assertEquals(Decision.TOO_SOON, limiter.acquireStt(A, 500));
		assertEquals(Decision.OK, limiter.acquireStt(B, 500));
		limiter.releaseStt();
		assertEquals(400L, advance(600, () -> limiter.sttSpacingRemaining(A)));
		assertEquals(Decision.OK, advance(400, () -> limiter.acquireStt(A, 500)));
	}

	@Test
	void hourlyAudioBudgetRollsOff() {
		VoiceRateLimiter limiter = limiter(0, 10, 4, 4); // 10 s per hour
		assertEquals(Decision.OK, limiter.acquireStt(A, 6_000));
		limiter.releaseStt();
		assertEquals(Decision.HOURLY_CAP, limiter.acquireStt(A, 6_000), "6 + 6 s exceeds 10 s");
		assertEquals(Decision.OK, limiter.acquireStt(A, 4_000));
		limiter.releaseStt();
		assertEquals(Decision.HOURLY_CAP, limiter.checkStt(A), "budget fully used");
		assertEquals(0L, limiter.sttBudgetRemaining(A));
		now.addAndGet(3_600_000L);
		assertEquals(Decision.OK, limiter.checkStt(A));
		assertEquals(10_000L, limiter.sttBudgetRemaining(A));
	}

	@Test
	void captureGateIgnoresSpacingSoTheNextSentenceIsNotCutOff() {
		VoiceRateLimiter limiter = limiter(1_000, 300, 4, 4);
		limiter.acquireStt(A, 500);
		assertEquals(Decision.OK, limiter.checkStt(A));
	}

	@Test
	void globalInFlightCapsAndReleases() {
		VoiceRateLimiter limiter = limiter(0, 300, 1, 1);
		assertEquals(Decision.OK, limiter.acquireStt(A, 100));
		assertEquals(Decision.BUSY, limiter.acquireStt(B, 100));
		limiter.releaseStt();
		assertEquals(Decision.OK, limiter.acquireStt(B, 100));
		assertEquals(Decision.OK, limiter.acquireTts());
		assertEquals(Decision.BUSY, limiter.acquireTts());
		limiter.releaseTts();
		assertEquals(Decision.OK, limiter.acquireTts());
	}

	@Test
	void backoffBlocksTheChannelAndIsCapped() {
		VoiceRateLimiter limiter = limiter(0, 300, 4, 4);
		limiter.backOff(SpeechException.Channel.STT, 7_000);
		assertEquals(Decision.BACKOFF, limiter.checkStt(A));
		assertEquals(Decision.BACKOFF, limiter.acquireStt(A, 100));
		assertEquals(Decision.OK, limiter.acquireTts(), "other channels stay open");
		limiter.backOff(SpeechException.Channel.STT, 1_000);
		assertEquals(7_000L, limiter.backoffRemaining(SpeechException.Channel.STT), "a shorter back-off never shortens a longer one");
		now.addAndGet(7_000);
		assertEquals(Decision.OK, limiter.acquireStt(A, 100));
		limiter.backOff(SpeechException.Channel.TTS, 10_000_000L);
		assertEquals(OpenAiHttp.MAX_RETRY_AFTER_MILLIS, limiter.backoffRemaining(SpeechException.Channel.TTS));
	}

	@Test
	void calloutsAndLlmCallsAreLimitedPerPlayer() {
		VoiceRateLimiter limiter = limiter(0, 300, 4, 4);
		assertEquals(Decision.OK, limiter.tryCallout(A));
		assertEquals(Decision.TOO_SOON, limiter.tryCallout(A));
		assertEquals(Decision.OK, limiter.tryCallout(B));
		now.addAndGet(4_000);
		assertEquals(Decision.OK, limiter.tryCallout(A));
		for (int i = 0; i < 6; i++) assertEquals(Decision.OK, limiter.tryLlm(A));
		assertEquals(Decision.TOO_SOON, limiter.tryLlm(A));
		now.addAndGet(60_000);
		assertEquals(Decision.OK, limiter.tryLlm(A));
	}

	@Test
	void trackedPlayersAreBounded() {
		VoiceRateLimiter limiter = limiter(0, 300, 4, 4);
		for (int i = 0; i < VoiceRateLimiter.MAX_TRACKED_PLAYERS + 50; i++) limiter.tryCallout(new UUID(1, i));
		// The first players were forgotten, so they may call out again immediately.
		assertEquals(Decision.OK, limiter.tryCallout(new UUID(1, 0)));
	}

	private <T> T advance(long millis, java.util.function.Supplier<T> then) {
		now.addAndGet(millis);
		return then.get();
	}
}
