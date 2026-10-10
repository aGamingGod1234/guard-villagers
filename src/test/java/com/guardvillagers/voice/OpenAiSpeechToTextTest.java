package com.guardvillagers.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiSpeechToTextTest {
	private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private FakeOpenAi server;
	private OpenAiSpeechToText stt;

	@BeforeEach
	void start() throws Exception {
		server = new FakeOpenAi();
		stt = new OpenAiSpeechToText(server.http(), "gpt-transcribe", "en", Duration.ofSeconds(5));
	}

	@AfterEach
	void stop() {
		server.close();
	}

	@Test
	void sendsMultipartWavWithModelFieldsAndBearerKey() {
		server.response = FakeOpenAi.Response.json("{\"text\":\"  alpha hold the line  \"}");
		short[] pcm = {0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE};

		SpeechToText.Transcript transcript = stt.transcribe(PLAYER, pcm, "Alpha, Liam").join();

		assertEquals("alpha hold the line", transcript.text());
		FakeOpenAi.Request request = server.requests.getFirst();
		assertEquals("POST", request.method());
		assertEquals("/v1/audio/transcriptions", request.path());
		assertEquals("Bearer sk-test-key", request.header("Authorization"));
		String contentType = request.header("Content-Type");
		assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType);
		Map<String, Part> parts = parseMultipart(request.body(), contentType.substring(contentType.indexOf('=') + 1));
		assertEquals("gpt-transcribe", parts.get("model").text());
		assertEquals("json", parts.get("response_format").text());
		assertEquals("en", parts.get("language").text());
		assertEquals("Alpha, Liam", parts.get("prompt").text());
		Part file = parts.get("file");
		assertTrue(file.headers().contains("filename=\"speech.wav\""), file.headers());
		assertTrue(file.headers().contains("Content-Type: audio/wav"), file.headers());
		assertWav(file.data(), pcm);
	}

	@Test
	void omitsPromptAndLanguageWhenAbsent() {
		stt = new OpenAiSpeechToText(server.http(), "gpt-transcribe", "", Duration.ofSeconds(5));
		stt.transcribe(PLAYER, new short[] {1, 2}).join();
		FakeOpenAi.Request request = server.requests.getFirst();
		String type = request.header("Content-Type");
		Map<String, Part> parts = parseMultipart(request.body(), type.substring(type.indexOf('=') + 1));
		assertTrue(!parts.containsKey("prompt") && !parts.containsKey("language"), parts.keySet().toString());
	}

	@Test
	void wavHeaderIsCanonical48kMono16Bit() {
		byte[] wav = PcmResampler.toWav(new short[] {1, -2, 3}, 48_000);
		assertWav(wav, new short[] {1, -2, 3});
	}

	@Test
	void transcriptIsCappedAt512CodePoints() {
		String emoji = "🛡"; // one code point, two chars
		server.response = FakeOpenAi.Response.json("{\"text\":\"" + emoji.repeat(600) + "\"}");
		String text = stt.transcribe(PLAYER, new short[] {5}).join().text();
		assertEquals(512, text.codePointCount(0, text.length()));
	}

	@Test
	void rateLimitCarriesRetryAfter() {
		server.response = new FakeOpenAi.Response(429, Map.of("Retry-After", "7"), new byte[0], false);
		SpeechException failure = failure(() -> stt.transcribe(PLAYER, new short[] {5}).join());
		assertEquals(SpeechException.Kind.RATE_LIMITED, failure.kind());
		assertEquals(429, failure.httpStatus());
		assertEquals(7_000L, failure.retryAfterMillis());
	}

	@Test
	void retryAfterIsCappedAndDefaulted() {
		server.response = new FakeOpenAi.Response(429, Map.of("Retry-After", "100000"), new byte[0], false);
		assertEquals(OpenAiHttp.MAX_RETRY_AFTER_MILLIS, failure(() -> stt.transcribe(PLAYER, new short[] {5}).join()).retryAfterMillis());
		server.response = new FakeOpenAi.Response(429, Map.of(), new byte[0], false);
		assertEquals(1_000L, failure(() -> stt.transcribe(PLAYER, new short[] {5}).join()).retryAfterMillis());
	}

	@Test
	void authenticationAndServerErrorsAreClassified() {
		server.response = new FakeOpenAi.Response(401, Map.of(), "{\"error\":\"bad key sk-test-key\"}".getBytes(), false);
		SpeechException auth = failure(() -> stt.transcribe(PLAYER, new short[] {5}).join());
		assertEquals(SpeechException.Kind.AUTHENTICATION, auth.kind());
		assertTrue(!auth.getMessage().contains("sk-test-key"), "error body or key must not leak into messages");
		server.response = new FakeOpenAi.Response(503, Map.of(), new byte[0], false);
		assertEquals(SpeechException.Kind.PROVIDER, failure(() -> stt.transcribe(PLAYER, new short[] {5}).join()).kind());
	}

	@Test
	void oversizedResponsesAreRejectedWithAndWithoutContentLength() {
		byte[] huge = ("{\"text\":\"" + "a".repeat(OpenAiSpeechToText.MAX_RESPONSE_BYTES) + "\"}").getBytes();
		server.response = new FakeOpenAi.Response(200, Map.of(), huge, false);
		assertEquals(SpeechException.Kind.RESPONSE_TOO_LARGE, failure(() -> stt.transcribe(PLAYER, new short[] {5}).join()).kind());
		server.response = new FakeOpenAi.Response(200, Map.of(), huge, true);
		assertEquals(SpeechException.Kind.RESPONSE_TOO_LARGE, failure(() -> stt.transcribe(PLAYER, new short[] {5}).join()).kind());
	}

	@Test
	void malformedJsonAndBadInputFail() {
		server.response = FakeOpenAi.Response.json("not json");
		assertEquals(SpeechException.Kind.MALFORMED, failure(() -> stt.transcribe(PLAYER, new short[] {5}).join()).kind());
		server.response = FakeOpenAi.Response.json("{\"nope\":1}");
		assertEquals(SpeechException.Kind.MALFORMED, failure(() -> stt.transcribe(PLAYER, new short[] {5}).join()).kind());
		int before = server.requests.size();
		assertEquals(SpeechException.Kind.REJECTED, failure(() -> stt.transcribe(PLAYER, new short[0]).join()).kind());
		assertEquals(SpeechException.Kind.REJECTED,
				failure(() -> stt.transcribe(PLAYER, new short[OpenAiSpeechToText.MAX_INPUT_SAMPLES + 1]).join()).kind());
		assertEquals(before, server.requests.size(), "invalid input must not reach OpenAI");
	}

	@Test
	void unreachableServerIsUnavailable() {
		server.close();
		assertEquals(SpeechException.Kind.UNAVAILABLE, failure(() -> stt.transcribe(PLAYER, new short[] {5}).join()).kind());
	}

	static SpeechException failure(Runnable call) {
		CompletionException thrown = assertThrows(CompletionException.class, call::run);
		SpeechException speech = SpeechException.find(thrown);
		assertNotNull(speech, () -> "not a speech failure: " + thrown.getCause());
		return speech;
	}

	static void assertWav(byte[] wav, short[] pcm) {
		ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
		assertEquals(36 + pcm.length * 2, buffer.getInt(4));
		assertEquals("WAVEfmt ", new String(wav, 8, 8, StandardCharsets.US_ASCII));
		assertEquals(16, buffer.getInt(16));
		assertEquals(1, buffer.getShort(20), "PCM format");
		assertEquals(1, buffer.getShort(22), "mono");
		assertEquals(48_000, buffer.getInt(24));
		assertEquals(96_000, buffer.getInt(28), "byte rate");
		assertEquals(2, buffer.getShort(32), "block align");
		assertEquals(16, buffer.getShort(34), "bits per sample");
		assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
		assertEquals(pcm.length * 2, buffer.getInt(40));
		assertEquals(44 + pcm.length * 2, wav.length);
		short[] decoded = new short[pcm.length];
		for (int i = 0; i < pcm.length; i++) decoded[i] = buffer.getShort(44 + 2 * i);
		assertArrayEquals(pcm, decoded);
	}

	record Part(String headers, byte[] data) {
		String text() {
			return new String(data, StandardCharsets.UTF_8);
		}
	}

	static Map<String, Part> parseMultipart(byte[] body, String boundary) {
		String raw = new String(body, StandardCharsets.ISO_8859_1);
		assertTrue(raw.endsWith("--" + boundary + "--\r\n"), "multipart must end with the closing boundary");
		Map<String, Part> parts = new LinkedHashMap<>();
		for (String section : raw.split("--" + java.util.regex.Pattern.quote(boundary))) {
			if (section.isEmpty() || section.equals("--\r\n")) continue;
			assertTrue(section.startsWith("\r\n") && section.endsWith("\r\n"), "part framing");
			String content = section.substring(2, section.length() - 2);
			int split = content.indexOf("\r\n\r\n");
			String headers = content.substring(0, split);
			java.util.regex.Matcher name = java.util.regex.Pattern.compile("name=\"([^\"]+)\"").matcher(headers);
			assertTrue(name.find(), headers);
			parts.put(name.group(1), new Part(headers, content.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1)));
		}
		return parts;
	}
}
