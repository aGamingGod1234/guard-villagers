package com.guardvillagers.voice;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** A classified speech or OpenAI failure. Messages never contain the API key, audio, or response bodies. */
public final class SpeechException extends RuntimeException {
	public enum Channel { STT, TTS, LLM }

	public enum Kind {
		/** 401 or 403: the key is wrong or lacks access. Voice stays off until a reload. */
		AUTHENTICATION,
		/** 429: honour {@link #retryAfterMillis()} before the next request on this channel. */
		RATE_LIMITED,
		TIMEOUT,
		/** Network or transport failure before an HTTP status arrived. */
		UNAVAILABLE,
		/** Any other non-2xx status. */
		PROVIDER,
		RESPONSE_TOO_LARGE,
		MALFORMED,
		/** Refused locally (disabled, no key, rate limit, bad input) without contacting OpenAI. */
		REJECTED
	}

	private final Channel channel;
	private final Kind kind;
	private final int httpStatus;
	private final long retryAfterMillis;

	public SpeechException(Channel channel, Kind kind, String message) {
		this(channel, kind, message, 0, 0L, null);
	}

	public SpeechException(Channel channel, Kind kind, String message, int httpStatus, long retryAfterMillis,
			Throwable cause) {
		super(channel + " " + kind + ": " + message, cause, false, false);
		this.channel = channel;
		this.kind = kind;
		this.httpStatus = httpStatus;
		this.retryAfterMillis = Math.max(0L, retryAfterMillis);
	}

	public Channel channel() {
		return channel;
	}

	public Kind kind() {
		return kind;
	}

	/** HTTP status when one was received, else 0. */
	public int httpStatus() {
		return httpStatus;
	}

	/** Server-requested back-off for {@link Kind#RATE_LIMITED}, already capped; 0 when not given. */
	public long retryAfterMillis() {
		return retryAfterMillis;
	}

	/** Unwraps completion wrappers and returns the speech failure, or null when the cause is something else. */
	public static SpeechException find(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof CompletionException || current instanceof ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current instanceof SpeechException speech ? speech : null;
	}
}
