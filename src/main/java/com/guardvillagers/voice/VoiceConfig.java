package com.guardvillagers.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Server voice settings from {@code config/guardvillagers-voice.json}. Missing or out-of-range values fall back
 * to safe defaults (clamped and reported in {@link #warnings()}), so a bad file never stops the server.
 * The OpenAI key is never stored here: only the name of the environment variable that holds it.
 */
public record VoiceConfig(
		int version,
		VoiceInput voiceInput,
		Commander commander,
		OpenAi openai,
		Tts tts,
		Llm llm,
		RateLimits rateLimits,
		Chat chat,
		List<String> warnings
) {
	public static final String FILE_NAME = "guardvillagers-voice.json";
	public static final int CURRENT_VERSION = 1;
	private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
	private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9][a-z0-9.:_-]{0,79}");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public record VoiceInput(boolean enabled, boolean consentDefault, int captureRangeToOwnGuards,
			int maxUtteranceMs, int endpointSilenceMs) {
	}

	public record Commander(boolean autoCommandDefault, int planIntervalTicks, int voiceOrderExpirySeconds) {
	}

	public record OpenAi(String apiKeyEnv, String baseUrl, String sttModel, String sttLanguage, String ttsModel,
			int sttTimeoutMs, int ttsTimeoutMs, int llmTimeoutMs) {
	}

	public record Tts(boolean enabled, int distance, int cacheMiB, String tone) {
	}

	/**
	 * @param maxPerMinuteGlobal interpretations per minute for the whole server
	 * @param maxInFlight        interpretations waiting on OpenAI at once, server-wide
	 */
	public record Llm(boolean enabled, String model, int maxPerPlayerPerMinute, int maxPerMinuteGlobal, int maxInFlight, String reasoningEffort) {
		public Llm(boolean enabled, String model, int maxPerPlayerPerMinute, int maxPerMinuteGlobal, int maxInFlight) {
			this(enabled, model, maxPerPlayerPerMinute, maxPerMinuteGlobal, maxInFlight,
					com.guardvillagers.command.language.llm.LlmConfig.DEFAULT_MODEL.equals(model) ? "low" : "");
		}
	}

	public record RateLimits(int sttMinIntervalMs, int sttSecondsPerPlayerPerHour, int sttGlobalInFlight,
			int ttsGlobalInFlight, int calloutMinIntervalMs) {
	}

	public record Chat(String prefix, boolean prefixEnabled, boolean showTranscripts) {
	}

	public VoiceConfig {
		warnings = List.copyOf(warnings);
	}

	public static VoiceConfig defaults() {
		return parse(new JsonObject(), new ArrayList<>());
	}

	/** Reads the API key from the configured environment variable; empty when unset or blank. */
	public Optional<String> apiKey(Function<String, String> environment) {
		String value = environment.apply(openai.apiKeyEnv());
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
	}

	public Optional<String> apiKey() {
		return apiKey(System::getenv);
	}

	/**
	 * Loads the file, creating it with defaults when it does not exist. An unreadable or malformed file
	 * yields defaults plus a warning; the file on disk is then left untouched for the operator to fix.
	 */
	public static VoiceConfig load(Path file) {
		List<String> warnings = new ArrayList<>();
		if (!Files.exists(file)) {
			VoiceConfig defaults = defaults();
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, defaults.toJson(), StandardCharsets.UTF_8);
			} catch (IOException | RuntimeException exception) {
				warnings.add("could not write default " + file.getFileName() + ": " + exception.getMessage());
			}
			return new VoiceConfig(defaults.version, defaults.voiceInput, defaults.commander, defaults.openai,
					defaults.tts, defaults.llm, defaults.rateLimits, defaults.chat, warnings);
		}
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) throw new JsonParseException("top level is not an object");
			root = parsed.getAsJsonObject();
		} catch (IOException | JsonParseException | IllegalStateException exception) {
			warnings.add(file.getFileName() + " is unreadable, using defaults: " + exception.getMessage());
			root = new JsonObject();
		}
		return parse(root, warnings);
	}

	public static VoiceConfig parse(String json) {
		List<String> warnings = new ArrayList<>();
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(json);
			root = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
			if (!parsed.isJsonObject()) warnings.add("top level is not an object, using defaults");
		} catch (JsonParseException exception) {
			warnings.add("malformed JSON, using defaults: " + exception.getMessage());
			root = new JsonObject();
		}
		return parse(root, warnings);
	}

	private static VoiceConfig parse(JsonObject root, List<String> warnings) {
		Reader r = new Reader(root, warnings);
		int version = r.integer("", "version", CURRENT_VERSION, 1, Integer.MAX_VALUE);
		VoiceInput voiceInput = new VoiceInput(
				r.bool("voiceInput", "enabled", true),
				r.bool("voiceInput", "consentDefault", false),
				r.integer("voiceInput", "captureRangeToOwnGuards", 48, 4, 256),
				r.integer("voiceInput", "maxUtteranceMs", 10_000, 1_000, 20_000),
				r.integer("voiceInput", "endpointSilenceMs", 300, 160, 2_000)
		);
		Commander commander = new Commander(
				r.bool("commander", "autoCommandDefault", true),
				r.integer("commander", "planIntervalTicks", 40, 10, 400),
				r.integer("commander", "voiceOrderExpirySeconds", 60, 5, 3_600)
		);
		JsonObject timeouts = r.object("openai").has("timeoutsMs") && r.object("openai").get("timeoutsMs").isJsonObject()
				? r.object("openai").getAsJsonObject("timeoutsMs") : new JsonObject();
		Reader t = new Reader(wrap("openai.timeoutsMs", timeouts), warnings);
		OpenAi openai = new OpenAi(
				r.pattern("openai", "apiKeyEnv", "OPENAI_API_KEY", ENV_NAME),
				baseUrl(r.string("openai", "baseUrl", "https://api.openai.com/v1"), warnings),
				r.pattern("openai", "sttModel", "gpt-transcribe", MODEL_ID),
				r.pattern("openai", "sttLanguage", "en", Pattern.compile("|[a-z]{2}")),
				r.pattern("openai", "ttsModel", "gpt-4o-mini-tts", MODEL_ID),
				t.integer("openai.timeoutsMs", "stt", 15_000, 1_000, 60_000),
				t.integer("openai.timeoutsMs", "tts", 15_000, 1_000, 60_000),
				t.integer("openai.timeoutsMs", "llm", 8_000, 1_000, 60_000)
		);
		String tone = r.string("tts", "tone", "serious").toLowerCase(Locale.ROOT);
		if (!OpenAiTextToSpeech.TONES.containsKey(tone)) {
			warnings.add("tts.tone '" + tone + "' is unknown, using serious");
			tone = "serious";
		}
		Tts tts = new Tts(
				r.bool("tts", "enabled", true),
				r.integer("tts", "distance", 32, 4, 128),
				r.integer("tts", "cacheMiB", 16, 0, 256),
				tone
		);
		Llm llm = new Llm(
				r.bool("llm", "enabled", true),
				r.pattern("llm", "model", com.guardvillagers.command.language.llm.LlmConfig.DEFAULT_MODEL, MODEL_ID),
				r.integer("llm", "maxPerPlayerPerMinute", 6, 0, 60),
				r.integer("llm", "maxPerMinuteGlobal", 60, 0, 600),
				r.integer("llm", "maxInFlight", 4, 1, 32),
				r.pattern("llm", "reasoningEffort",
						com.guardvillagers.command.language.llm.LlmConfig.DEFAULT_MODEL.equals(
								r.string("llm", "model", com.guardvillagers.command.language.llm.LlmConfig.DEFAULT_MODEL)) ? "low" : "",
						Pattern.compile("|none|minimal|low|medium|high"))
		);
		RateLimits rateLimits = new RateLimits(
				r.integer("rateLimits", "sttMinIntervalMs", 1_000, 0, 60_000),
				r.integer("rateLimits", "sttSecondsPerPlayerPerHour", 300, 0, 3_600),
				r.integer("rateLimits", "sttGlobalInFlight", 4, 1, 32),
				r.integer("rateLimits", "ttsGlobalInFlight", 4, 1, 32),
				r.integer("rateLimits", "calloutMinIntervalMs", 4_000, 0, 60_000)
		);
		String prefix = r.string("chat", "prefix", "!g ");
		if (prefix.isBlank() || prefix.length() > 16) {
			warnings.add("chat.prefix must be 1 to 16 characters and not blank, using '!g '");
			prefix = "!g ";
		}
		Chat chat = new Chat(prefix, r.bool("chat", "prefixEnabled", true), r.bool("chat", "showTranscripts", false));
		return new VoiceConfig(version, voiceInput, commander, openai, tts, llm, rateLimits, chat, warnings);
	}

	private static JsonObject wrap(String name, JsonObject object) {
		JsonObject root = new JsonObject();
		root.add(name, object);
		return root;
	}

	/** {@code https} anywhere; plain {@code http} only to the local machine (tests, a future local backend). */
	static String baseUrl(String value, List<String> warnings) {
		String fallback = "https://api.openai.com/v1";
		try {
			URI uri = new URI(value);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			boolean local = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
			if (host.isEmpty() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
					|| !(scheme.equals("https") || scheme.equals("http") && local)) {
				warnings.add("openai.baseUrl must be https (http only for localhost), using " + fallback);
				return fallback;
			}
			return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
		} catch (URISyntaxException exception) {
			warnings.add("openai.baseUrl is not a URL, using " + fallback);
			return fallback;
		}
	}

	public String toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("version", version);
		JsonObject input = new JsonObject();
		input.addProperty("enabled", voiceInput.enabled());
		input.addProperty("consentDefault", voiceInput.consentDefault());
		input.addProperty("captureRangeToOwnGuards", voiceInput.captureRangeToOwnGuards());
		input.addProperty("maxUtteranceMs", voiceInput.maxUtteranceMs());
		input.addProperty("endpointSilenceMs", voiceInput.endpointSilenceMs());
		root.add("voiceInput", input);
		JsonObject cmd = new JsonObject();
		cmd.addProperty("autoCommandDefault", commander.autoCommandDefault());
		cmd.addProperty("planIntervalTicks", commander.planIntervalTicks());
		cmd.addProperty("voiceOrderExpirySeconds", commander.voiceOrderExpirySeconds());
		root.add("commander", cmd);
		JsonObject ai = new JsonObject();
		ai.addProperty("apiKeyEnv", openai.apiKeyEnv());
		ai.addProperty("baseUrl", openai.baseUrl());
		ai.addProperty("sttModel", openai.sttModel());
		ai.addProperty("sttLanguage", openai.sttLanguage());
		ai.addProperty("ttsModel", openai.ttsModel());
		JsonObject timeouts = new JsonObject();
		timeouts.addProperty("stt", openai.sttTimeoutMs());
		timeouts.addProperty("tts", openai.ttsTimeoutMs());
		timeouts.addProperty("llm", openai.llmTimeoutMs());
		ai.add("timeoutsMs", timeouts);
		root.add("openai", ai);
		JsonObject speech = new JsonObject();
		speech.addProperty("enabled", tts.enabled());
		speech.addProperty("distance", tts.distance());
		speech.addProperty("cacheMiB", tts.cacheMiB());
		speech.addProperty("tone", tts.tone());
		root.add("tts", speech);
		JsonObject model = new JsonObject();
		model.addProperty("enabled", llm.enabled());
		model.addProperty("model", llm.model());
		model.addProperty("reasoningEffort", llm.reasoningEffort());
		model.addProperty("maxPerPlayerPerMinute", llm.maxPerPlayerPerMinute());
		model.addProperty("maxPerMinuteGlobal", llm.maxPerMinuteGlobal());
		model.addProperty("maxInFlight", llm.maxInFlight());
		root.add("llm", model);
		JsonObject limits = new JsonObject();
		limits.addProperty("sttMinIntervalMs", rateLimits.sttMinIntervalMs());
		limits.addProperty("sttSecondsPerPlayerPerHour", rateLimits.sttSecondsPerPlayerPerHour());
		limits.addProperty("sttGlobalInFlight", rateLimits.sttGlobalInFlight());
		limits.addProperty("ttsGlobalInFlight", rateLimits.ttsGlobalInFlight());
		limits.addProperty("calloutMinIntervalMs", rateLimits.calloutMinIntervalMs());
		root.add("rateLimits", limits);
		JsonObject text = new JsonObject();
		text.addProperty("prefix", chat.prefix());
		text.addProperty("prefixEnabled", chat.prefixEnabled());
		text.addProperty("showTranscripts", chat.showTranscripts());
		root.add("chat", text);
		return GSON.toJson(root) + System.lineSeparator();
	}

	private record Reader(JsonObject root, List<String> warnings) {
		JsonObject object(String section) {
			if (section.isEmpty()) return root;
			JsonElement element = root.get(section);
			return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
		}

		private JsonPrimitive primitive(String section, String key) {
			JsonElement element = object(section).get(key);
			return element != null && element.isJsonPrimitive() ? element.getAsJsonPrimitive() : null;
		}

		private String path(String section, String key) {
			return section.isEmpty() ? key : section + "." + key;
		}

		boolean bool(String section, String key, boolean fallback) {
			JsonPrimitive value = primitive(section, key);
			if (value == null) return fallback;
			if (!value.isBoolean()) {
				warnings.add(path(section, key) + " must be true or false, using " + fallback);
				return fallback;
			}
			return value.getAsBoolean();
		}

		int integer(String section, String key, int fallback, int min, int max) {
			JsonPrimitive value = primitive(section, key);
			if (value == null) return fallback;
			if (!value.isNumber()) {
				warnings.add(path(section, key) + " must be a number, using " + fallback);
				return fallback;
			}
			double raw = value.getAsDouble();
			if (!Double.isFinite(raw)) return fallback;
			long rounded = Math.round(raw);
			int clamped = (int) Math.max(min, Math.min(max, rounded));
			if (clamped != rounded) {
				warnings.add(path(section, key) + "=" + rounded + " is outside " + min + ".." + max
						+ ", clamped to " + clamped);
			}
			return clamped;
		}

		String string(String section, String key, String fallback) {
			JsonPrimitive value = primitive(section, key);
			if (value == null) return fallback;
			if (!value.isString()) {
				warnings.add(path(section, key) + " must be a string, using " + fallback);
				return fallback;
			}
			return value.getAsString();
		}

		String pattern(String section, String key, String fallback, Pattern pattern) {
			String value = string(section, key, fallback).trim();
			if (!pattern.matcher(value).matches()) {
				warnings.add(path(section, key) + " is not valid, using " + fallback);
				return fallback;
			}
			return value;
		}
	}
}
