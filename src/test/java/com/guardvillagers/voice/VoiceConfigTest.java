package com.guardvillagers.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceConfigTest {
	@Test
	void defaultsMatchTheDesign() {
		VoiceConfig config = VoiceConfig.defaults();
		assertTrue(config.voiceInput().enabled());
		assertFalse(config.voiceInput().consentDefault(), "voice consent is opt-in");
		assertFalse(config.chat().showTranscripts(), "transcript feedback is opt-in");
		assertEquals(48, config.voiceInput().captureRangeToOwnGuards());
		assertEquals(10_000, config.voiceInput().maxUtteranceMs());
		assertEquals("OPENAI_API_KEY", config.openai().apiKeyEnv());
		assertEquals("https://api.openai.com/v1", config.openai().baseUrl());
		assertEquals("gpt-transcribe", config.openai().sttModel());
		assertEquals("gpt-4o-mini-tts", config.openai().ttsModel());
		assertEquals(15_000, config.openai().sttTimeoutMs());
		assertEquals(32, config.tts().distance());
		assertEquals(16, config.tts().cacheMiB());
		assertEquals(1_000, config.rateLimits().sttMinIntervalMs());
		assertEquals(300, config.rateLimits().sttSecondsPerPlayerPerHour());
		assertEquals("!g ", config.chat().prefix());
		assertTrue(config.warnings().isEmpty(), config.warnings().toString());
	}

	@Test
	void writesDefaultsOnFirstLoadAndRoundTrips(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("config").resolve(VoiceConfig.FILE_NAME);
		VoiceConfig created = VoiceConfig.load(file);
		assertTrue(Files.exists(file));
		String written = Files.readString(file);
		assertFalse(written.contains("sk-"), "no key is ever written");
		VoiceConfig reloaded = VoiceConfig.load(file);
		assertEquals(created.toJson(), reloaded.toJson());
		assertTrue(reloaded.warnings().isEmpty(), reloaded.warnings().toString());
	}

	@Test
	void outOfRangeAndWrongTypesAreClampedWithWarnings() {
		VoiceConfig config = VoiceConfig.parse("""
				{"voiceInput":{"maxUtteranceMs":999999,"enabled":"yes","captureRangeToOwnGuards":-5},
				 "tts":{"distance":1000,"tone":"shouting"},
				 "rateLimits":{"sttGlobalInFlight":0},
				 "openai":{"timeoutsMs":{"stt":5},"sttModel":"bad model!","apiKeyEnv":"rm -rf"},
				 "chat":{"prefix":"   "}}
				""");
		assertEquals(20_000, config.voiceInput().maxUtteranceMs());
		assertTrue(config.voiceInput().enabled());
		assertEquals(4, config.voiceInput().captureRangeToOwnGuards());
		assertEquals(128, config.tts().distance());
		assertEquals("serious", config.tts().tone());
		assertEquals(1, config.rateLimits().sttGlobalInFlight());
		assertEquals(1_000, config.openai().sttTimeoutMs());
		assertEquals("gpt-transcribe", config.openai().sttModel());
		assertEquals("OPENAI_API_KEY", config.openai().apiKeyEnv());
		assertEquals("!g ", config.chat().prefix());
		assertTrue(config.warnings().size() >= 9, config.warnings().toString());
	}

	@Test
	void malformedJsonFallsBackToDefaults() {
		VoiceConfig config = VoiceConfig.parse("{ nope");
		assertEquals(VoiceConfig.defaults().toJson(), config.toJson());
		assertFalse(config.warnings().isEmpty());
	}

	@Test
	void baseUrlMustBeHttpsExceptLocalhost() {
		assertEquals("https://proxy.example.com/v1", VoiceConfig.parse(
				"{\"openai\":{\"baseUrl\":\"https://proxy.example.com/v1/\"}}").openai().baseUrl());
		assertEquals("http://127.0.0.1:8080/v1", VoiceConfig.parse(
				"{\"openai\":{\"baseUrl\":\"http://127.0.0.1:8080/v1\"}}").openai().baseUrl());
		assertEquals("http://localhost/v1", VoiceConfig.parse(
				"{\"openai\":{\"baseUrl\":\"http://localhost/v1\"}}").openai().baseUrl());
		for (String bad : new String[] {"http://evil.example.com/v1", "ftp://x/y", "https://user:pw@x.com/v1", "not a url"}) {
			VoiceConfig config = VoiceConfig.parse("{\"openai\":{\"baseUrl\":\"" + bad + "\"}}");
			assertEquals("https://api.openai.com/v1", config.openai().baseUrl(), bad);
			assertFalse(config.warnings().isEmpty(), bad);
		}
	}

	@Test
	void apiKeyComesFromTheNamedEnvironmentVariable() {
		VoiceConfig config = VoiceConfig.parse("{\"openai\":{\"apiKeyEnv\":\"GUARD_KEY\"}}");
		assertEquals(Optional.of("sk-abc"), config.apiKey(Map.of("GUARD_KEY", " sk-abc ")::get));
		assertEquals(Optional.empty(), config.apiKey(Map.of("OPENAI_API_KEY", "sk-other")::get));
		assertEquals(Optional.empty(), config.apiKey(Map.of("GUARD_KEY", "  ")::get));
	}
}
