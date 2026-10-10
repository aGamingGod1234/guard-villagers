package com.guardvillagers.command.language.llm;

import java.time.Duration;
import java.util.Set;

/**
 * Settings for the optional LLM interpreter. The voice config file (W5) maps {@code llm.*}, {@code openai.apiKeyEnv},
 * {@code openai.baseUrl} and {@code openai.timeoutsMs.llm} onto this record. Out-of-range values are clamped, never
 * rejected. The API key itself is never part of the config: {@code apiKeyEnv} only names the environment variable.
 */
public record LlmConfig(
		boolean enabled,
		String model,
		String apiKeyEnv,
		String baseUrl,
		Duration timeout,
		int maxPerPlayerPerMinute,
		String reasoningEffort,
		int maxOutputTokens) {

	/**
	 * OpenAI's current small, cost-efficient model ("most efficient model for focused, high-volume tasks", checked on
	 * developers.openai.com/api/docs/models on 2026-10-10). Server owners can change it in the config file.
	 */
	public static final String DEFAULT_MODEL = "gpt-6-luna";
	public static final String DEFAULT_API_KEY_ENV = "OPENAI_API_KEY";
	public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
	public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(8000);
	public static final int DEFAULT_MAX_PER_PLAYER_PER_MINUTE = 6;
	/** Low effort keeps latency down for a short classification-like task. Empty string omits the parameter. */
	public static final String DEFAULT_REASONING_EFFORT = "low";
	public static final int DEFAULT_MAX_OUTPUT_TOKENS = 1024;

	private static final Set<String> EFFORTS = Set.of("", "none", "minimal", "low", "medium", "high");

	public LlmConfig {
		model = model == null || !model.matches("[A-Za-z0-9._:-]{1,64}") ? DEFAULT_MODEL : model;
		apiKeyEnv = apiKeyEnv == null || !apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") ? DEFAULT_API_KEY_ENV : apiKeyEnv;
		baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
		long millis = timeout == null ? DEFAULT_TIMEOUT.toMillis() : timeout.toMillis();
		timeout = Duration.ofMillis(Math.clamp(millis, 500L, 30_000L));
		maxPerPlayerPerMinute = Math.clamp(maxPerPlayerPerMinute, 0, 60);
		reasoningEffort = reasoningEffort == null || !EFFORTS.contains(reasoningEffort) ? DEFAULT_REASONING_EFFORT : reasoningEffort;
		maxOutputTokens = Math.clamp(maxOutputTokens, 256, 4096);
	}

	public static LlmConfig defaults() {
		return new LlmConfig(true, DEFAULT_MODEL, DEFAULT_API_KEY_ENV, DEFAULT_BASE_URL, DEFAULT_TIMEOUT,
				DEFAULT_MAX_PER_PLAYER_PER_MINUTE, DEFAULT_REASONING_EFFORT, DEFAULT_MAX_OUTPUT_TOKENS);
	}

	public LlmConfig withBaseUrl(String url) {
		return new LlmConfig(this.enabled, this.model, this.apiKeyEnv, url, this.timeout, this.maxPerPlayerPerMinute,
				this.reasoningEffort, this.maxOutputTokens);
	}

	public LlmConfig withTimeout(Duration newTimeout) {
		return new LlmConfig(this.enabled, this.model, this.apiKeyEnv, this.baseUrl, newTimeout, this.maxPerPlayerPerMinute,
				this.reasoningEffort, this.maxOutputTokens);
	}

	public LlmConfig withMaxPerPlayerPerMinute(int max) {
		return new LlmConfig(this.enabled, this.model, this.apiKeyEnv, this.baseUrl, this.timeout, max,
				this.reasoningEffort, this.maxOutputTokens);
	}
}
