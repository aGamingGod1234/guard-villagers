package com.guardvillagers.command.language;

import com.guardvillagers.command.language.llm.LlmResult;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OrderValidator;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What the speaker meant, after validation. {@code orders} are safe to put in the order book (ownership is still
 * re-checked at execution). {@code reply} is an LLM line, already capped at 120 characters, shown or spoken only as
 * given; never parse it.
 */
public record Interpretation(
		Origin origin,
		List<Order> orders,
		List<OrderValidator.Rejected> rejected,
		boolean addressed,
		Optional<String> reply,
		Optional<LlmResult.Status> llmStatus,
		Optional<Duration> retryAfter) {

	public enum Origin {
		GRAMMAR,
		LLM,
		/** Nothing understood. */
		NONE
	}

	public Interpretation {
		orders = List.copyOf(orders);
		rejected = List.copyOf(rejected);
	}

	public boolean understood() {
		return !this.orders.isEmpty();
	}

	/**
	 * True when the player spoke to the guards but nothing usable came out and there is no clarifying reply: answer
	 * "I did not catch that." Unaddressed chatter that parses to nothing is ignored, since players talk to each other.
	 */
	public boolean shouldSayNotUnderstood() {
		return this.orders.isEmpty() && this.addressed && this.reply.isEmpty();
	}

	/** True when the LLM was tried and failed for a transient reason (429, timeout): "Commander couldn't hear you". */
	public boolean llmTemporarilyUnavailable() {
		return this.llmStatus.map(s -> s == LlmResult.Status.RATE_LIMITED || s == LlmResult.Status.TIMEOUT
				|| s == LlmResult.Status.UNAVAILABLE).orElse(false);
	}
}
