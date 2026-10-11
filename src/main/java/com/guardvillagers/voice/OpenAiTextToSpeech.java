package com.guardvillagers.voice;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI {@code /audio/speech} client (port of agent-arena {@code OpenAiTtsProvider}): requests raw 24 kHz
 * mono s16le PCM and resamples it to 48 kHz for Simple Voice Chat.
 */
public final class OpenAiTextToSpeech implements TextToSpeech {
	public static final int SOURCE_SAMPLE_RATE = 24_000;
	public static final int MAX_SECONDS = 20;
	public static final int MAX_RESPONSE_BYTES = SOURCE_SAMPLE_RATE * 2 * MAX_SECONDS;
	public static final List<String> VOICES = List.of(
			"cedar", "marin", "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse");
	static final List<String> LEGACY_VOICES = List.of(
			"alloy", "ash", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer");
	public static final Map<String, String> TONES = Map.of(
			"neutral", "Speak clearly and naturally.",
			"warm", "Speak warmly.",
			"excited", "Sound excited.",
			"serious", "Use a serious tone.",
			"dramatic", "Use a dramatic tone.",
			"whisper", "Speak in a whisper.",
			"robotic", "Use a robotic delivery.",
			"angry", "Sound angry.");
	private final OpenAiHttp http;
	private final String model;
	private final String tone;
	private final Duration timeout;

	public OpenAiTextToSpeech(OpenAiHttp http, String model, String tone, Duration timeout) {
		if (!TONES.containsKey(tone)) throw new IllegalArgumentException("unknown tone " + tone);
		this.http = http;
		this.model = model;
		this.tone = tone;
		this.timeout = timeout;
	}

	/** Stable built-in voice for a speaker: each guard sounds the same every time, a successor sounds different. */
	public static String voiceFor(UUID speaker) {
		return voiceFor(speaker, "gpt-4o-mini-tts");
	}

	public static String voiceFor(UUID speaker, String model) {
		return voiceForProfile(speaker.toString(), voicesFor(model));
	}

	private static List<String> voicesFor(String model) {
		return isLegacyModel(model) ? LEGACY_VOICES : VOICES;
	}

	private static boolean isLegacyModel(String model) {
		return "tts-1".equals(model) || "tts-1-hd".equals(model);
	}

	/** agent-arena {@code openAiVoiceForProfile}: built-in names pass through, anything else hashes into the list. */
	public static String voiceForProfile(String profile) {
		return voiceForProfile(profile, VOICES);
	}

	private static String voiceForProfile(String profile, List<String> voices) {
		if (profile == null || profile.isBlank()) throw new IllegalArgumentException("profile must not be blank");
		if (voices.contains(profile)) return profile;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(profile.getBytes(StandardCharsets.UTF_8));
			long index = Integer.toUnsignedLong(ByteBuffer.wrap(digest, 0, 4).getInt());
			return voices.get((int) (index % voices.size()));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	@Override
	public String cacheNamespace() {
		return "openai/" + model + "/" + tone;
	}

	@Override
	public CompletableFuture<short[]> synthesize(String text, String voice) {
		if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
			return CompletableFuture.failedFuture(new SpeechException(SpeechException.Channel.TTS,
					SpeechException.Kind.REJECTED, "text must contain 1 to " + MAX_TEXT_CODE_POINTS + " code points"));
		}
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("input", text);
		body.addProperty("voice", voiceForProfile(voice, voicesFor(model)));
		if (!isLegacyModel(model)) body.addProperty("instructions", TONES.get(tone));
		body.addProperty("response_format", "pcm");
		return OpenAiHttp.map(http.postJson("audio/speech", body.toString(), timeout, MAX_RESPONSE_BYTES,
				SpeechException.Channel.TTS), OpenAiTextToSpeech::decode);
	}

	static short[] decode(byte[] pcm24k) {
		if (pcm24k.length == 0 || pcm24k.length % 2 != 0) {
			throw new SpeechException(SpeechException.Channel.TTS, SpeechException.Kind.MALFORMED,
					"OpenAI returned invalid mono PCM");
		}
		return PcmResampler.resample(PcmResampler.fromLittleEndian(pcm24k), SOURCE_SAMPLE_RATE, OUTPUT_SAMPLE_RATE,
				MAX_SECONDS);
	}
}
