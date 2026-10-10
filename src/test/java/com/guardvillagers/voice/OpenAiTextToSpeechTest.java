package com.guardvillagers.voice;

import static com.guardvillagers.voice.OpenAiSpeechToTextTest.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiTextToSpeechTest {
	private FakeOpenAi server;
	private OpenAiTextToSpeech tts;

	@BeforeEach
	void start() throws Exception {
		server = new FakeOpenAi();
		tts = new OpenAiTextToSpeech(server.http(), "gpt-4o-mini-tts", "serious", Duration.ofSeconds(5));
	}

	@AfterEach
	void stop() {
		server.close();
	}

	@Test
	void requestsRawPcmWithModelVoiceAndTone() {
		server.response = FakeOpenAi.Response.bytes(le(new short[] {100, 200}));
		tts.synthesize("Holding.", "onyx").join();
		FakeOpenAi.Request request = server.requests.getFirst();
		assertEquals("/v1/audio/speech", request.path());
		assertEquals("Bearer sk-test-key", request.header("Authorization"));
		assertTrue(request.header("Content-Type").startsWith("application/json"));
		JsonObject body = JsonParser.parseString(new String(request.body(), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("gpt-4o-mini-tts", body.get("model").getAsString());
		assertEquals("Holding.", body.get("input").getAsString());
		assertEquals("onyx", body.get("voice").getAsString());
		assertEquals("pcm", body.get("response_format").getAsString());
		assertEquals("Use a serious tone.", body.get("instructions").getAsString());
	}

	@Test
	void resamples24kTo48kWithDoubleLengthAndExactBoundaries() {
		short[] source = {0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE, 42};
		server.response = FakeOpenAi.Response.bytes(le(source));
		short[] out = tts.synthesize("Area clear.", "ash").join();
		assertEquals(source.length * 2, out.length);
		assertEquals(source[0], out[0], "first sample is preserved");
		assertEquals(500, out[1], "midpoint is interpolated");
		assertEquals(source[source.length - 1], out[out.length - 2], "last source sample lands on an even index");
		assertEquals(source[source.length - 1], out[out.length - 1], "positions past the end hold the last value");
	}

	@Test
	void rejectsInvalidAudioAndOversizedResponses() {
		server.response = FakeOpenAi.Response.bytes(new byte[] {1, 2, 3});
		assertEquals(SpeechException.Kind.MALFORMED, failure(() -> tts.synthesize("Hi.", "ash").join()).kind());
		server.response = FakeOpenAi.Response.bytes(new byte[0]);
		assertEquals(SpeechException.Kind.MALFORMED, failure(() -> tts.synthesize("Hi.", "ash").join()).kind());
		server.response = new FakeOpenAi.Response(200, Map.of(), new byte[OpenAiTextToSpeech.MAX_RESPONSE_BYTES + 2], true);
		assertEquals(SpeechException.Kind.RESPONSE_TOO_LARGE, failure(() -> tts.synthesize("Hi.", "ash").join()).kind());
	}

	@Test
	void rejectsBlankAndOverlongTextWithoutCallingOpenAi() {
		assertEquals(SpeechException.Kind.REJECTED, failure(() -> tts.synthesize("  ", "ash").join()).kind());
		assertEquals(SpeechException.Kind.REJECTED, failure(() -> tts.synthesize("a".repeat(281), "ash").join()).kind());
		assertEquals(0, server.requests.size());
	}

	@Test
	void rateLimitedTtsReportsRetryAfter() {
		server.response = new FakeOpenAi.Response(429, Map.of("Retry-After", "2"), new byte[0], false);
		SpeechException failure = failure(() -> tts.synthesize("Hi.", "ash").join());
		assertEquals(SpeechException.Kind.RATE_LIMITED, failure.kind());
		assertEquals(SpeechException.Channel.TTS, failure.channel());
		assertEquals(2_000L, failure.retryAfterMillis());
	}

	@Test
	void voicesAreStablePerSpeakerAndBuiltInNamesPassThrough() {
		UUID guard = UUID.fromString("6f1c1c3e-1111-4222-8333-944444444444");
		assertEquals(OpenAiTextToSpeech.voiceFor(guard), OpenAiTextToSpeech.voiceFor(guard));
		assertTrue(OpenAiTextToSpeech.VOICES.contains(OpenAiTextToSpeech.voiceFor(guard)));
		assertEquals("nova", OpenAiTextToSpeech.voiceForProfile("nova"));
		long distinct = java.util.stream.IntStream.range(0, 64)
				.mapToObj(i -> OpenAiTextToSpeech.voiceFor(new UUID(i, i * 31L))).distinct().count();
		assertTrue(distinct > 4, "different guards should get a spread of voices");
		assertNotEquals(TtsCache.key("a", "bc"), TtsCache.key("ab", "c"));
	}

	static byte[] le(short[] samples) {
		ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (short sample : samples) buffer.putShort(sample);
		return buffer.array();
	}
}
