package com.guardvillagers.voice;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenAI {@code /audio/transcriptions} client (port of agent-arena {@code OpenAiSttProvider}): uploads a 48 kHz
 * mono WAV as multipart form data and returns the trimmed text, capped at 512 code points.
 */
public final class OpenAiSpeechToText implements SpeechToText {
	public static final int MAX_RESPONSE_BYTES = 256 * 1024;
	public static final int MAX_TRANSCRIPT_CODE_POINTS = 512;
	public static final int MAX_PROMPT_CHARS = 1_000;
	/** Hard upper bound regardless of config; the capture engine caps utterances lower. */
	public static final int MAX_INPUT_SAMPLES = INPUT_SAMPLE_RATE * 20;
	private final OpenAiHttp http;
	private final String model;
	private final String language;
	private final Duration timeout;

	public OpenAiSpeechToText(OpenAiHttp http, String model, String language, Duration timeout) {
		this.http = http;
		this.model = model;
		this.language = language == null ? "" : language;
		this.timeout = timeout;
	}

	@Override
	public CompletableFuture<Transcript> transcribe(UUID player, short[] pcm48k, String promptHint) {
		if (pcm48k == null || pcm48k.length == 0 || pcm48k.length > MAX_INPUT_SAMPLES) {
			return CompletableFuture.failedFuture(new SpeechException(SpeechException.Channel.STT,
					SpeechException.Kind.REJECTED, "audio must be 1 sample to 20 seconds of 48 kHz mono PCM"));
		}
		String boundary = "----guardvillagers" + Long.toHexString(ThreadLocalRandom.current().nextLong())
				+ Long.toHexString(ThreadLocalRandom.current().nextLong());
		Multipart form = new Multipart(boundary);
		form.field("model", model);
		form.field("response_format", "json");
		if (!language.isEmpty()) form.field("language", language);
		if (promptHint != null && !promptHint.isBlank()) {
			String prompt = promptHint.strip();
			form.field("prompt", prompt.length() > MAX_PROMPT_CHARS ? prompt.substring(0, MAX_PROMPT_CHARS) : prompt);
		}
		form.file("file", "speech.wav", "audio/wav", PcmResampler.toWav(pcm48k, INPUT_SAMPLE_RATE));
		return OpenAiHttp.map(http.post("audio/transcriptions", "multipart/form-data; boundary=" + boundary,
						HttpRequest.BodyPublishers.ofByteArray(form.finish()), timeout, MAX_RESPONSE_BYTES,
						SpeechException.Channel.STT), OpenAiSpeechToText::parse);
	}

	static Transcript parse(byte[] body) {
		try {
			JsonElement root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
			JsonElement text = root.isJsonObject() ? root.getAsJsonObject().get("text") : null;
			if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) {
				throw new SpeechException(SpeechException.Channel.STT, SpeechException.Kind.MALFORMED,
						"OpenAI returned no transcript");
			}
			return new Transcript(capCodePoints(text.getAsString().strip(), MAX_TRANSCRIPT_CODE_POINTS));
		} catch (JsonParseException | IllegalStateException exception) {
			throw new SpeechException(SpeechException.Channel.STT, SpeechException.Kind.MALFORMED,
					"OpenAI returned malformed transcription JSON");
		}
	}

	static String capCodePoints(String text, int max) {
		if (text.codePointCount(0, text.length()) <= max) return text;
		return text.substring(0, text.offsetByCodePoints(0, max));
	}

	/** RFC 7578 multipart body. Field names are fixed ASCII; values are UTF-8 text. */
	static final class Multipart {
		private final String boundary;
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Multipart(String boundary) {
			this.boundary = boundary;
		}

		void field(String name, String value) {
			write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
			write(value);
			write("\r\n");
		}

		void file(String name, String filename, String contentType, byte[] data) {
			write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
					+ filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
			out.writeBytes(data);
			write("\r\n");
		}

		byte[] finish() {
			write("--" + boundary + "--\r\n");
			return out.toByteArray();
		}

		private void write(String text) {
			out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
		}
	}
}
