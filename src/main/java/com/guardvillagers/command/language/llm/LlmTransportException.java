package com.guardvillagers.command.language.llm;

import java.time.Duration;
import java.util.Optional;

/** A failed model call. Messages never contain the API key or the response body. */
public final class LlmTransportException extends RuntimeException {
	public enum Kind {
		/** No API key in the configured environment variable. */
		NOT_CONFIGURED,
		/** HTTP 401/403. The transport stays disabled until {@link OpenAiLlmTransport#resetAuthFailure()}. */
		UNAUTHORIZED,
		/** HTTP 429, or still inside the Retry-After window of an earlier 429. */
		RATE_LIMITED,
		TIMEOUT,
		RESPONSE_TOO_LARGE,
		/** Any other non-2xx status. */
		HTTP_ERROR,
		NETWORK
	}

	private final Kind kind;
	private final int status;
	private final Duration retryAfter;

	public LlmTransportException(Kind kind, int status, Duration retryAfter, String message) {
		super(message);
		this.kind = kind;
		this.status = status;
		this.retryAfter = retryAfter;
	}

	public LlmTransportException(Kind kind, String message) {
		this(kind, 0, null, message);
	}

	public Kind kind() {
		return this.kind;
	}

	/** HTTP status, or 0 when no response was received. */
	public int status() {
		return this.status;
	}

	public Optional<Duration> retryAfter() {
		return Optional.ofNullable(this.retryAfter);
	}
}
