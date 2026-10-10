package com.guardvillagers.command.language.llm;

import com.guardvillagers.command.order.Order;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Outcome of one LLM interpretation. {@code orders} passed the schema checks but not yet {@code OrderValidator};
 * {@code schemaRejections} lists why other model orders were dropped. The future never fails: errors are a status.
 */
public record LlmResult(Status status, List<Order> orders, List<String> schemaRejections, Optional<String> reply, Optional<Duration> retryAfter) {
	public enum Status {
		OK,
		/** This player hit {@code llm.maxPerPlayerPerMinute}. No request was sent. */
		PLAYER_RATE_LIMITED,
		/** OpenAI returned 429 or is still in its Retry-After window. */
		RATE_LIMITED,
		TIMEOUT,
		/** Key rejected (401/403). Stays off until reset. */
		UNAUTHORIZED,
		/** No key, network failure, non-2xx status or oversized response. */
		UNAVAILABLE,
		/** The response or the tool arguments were not the agreed shape. */
		MALFORMED
	}

	public LlmResult {
		orders = List.copyOf(orders);
		schemaRejections = List.copyOf(schemaRejections);
	}

	public static LlmResult failure(Status status) {
		return new LlmResult(status, List.of(), List.of(), Optional.empty(), Optional.empty());
	}

	public static LlmResult failure(Status status, Optional<Duration> retryAfter) {
		return new LlmResult(status, List.of(), List.of(), Optional.empty(), retryAfter);
	}

	public boolean ok() {
		return this.status == Status.OK;
	}
}
