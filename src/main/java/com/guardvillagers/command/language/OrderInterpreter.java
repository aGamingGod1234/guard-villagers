package com.guardvillagers.command.language;

import com.guardvillagers.command.language.llm.LlmInterpreter;
import com.guardvillagers.command.language.llm.LlmResult;
import com.guardvillagers.command.order.OrderValidator;
import com.guardvillagers.command.order.OwnerRoster;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point from transcript (voice, {@code /guards say}, chat prefix) to validated orders. The grammar runs first,
 * synchronously and offline. The LLM runs only when the grammar finds nothing, the utterance is addressed to guards
 * (or the caller says it is, as {@code /guards say} does), and an {@link LlmInterpreter} exists (enabled and keyed).
 * Both paths go through the same {@link OrderValidator}.
 *
 * <p>Thread use: call from any thread. The LLM future completes on an HTTP thread, so hop back to the server thread
 * before touching the world.
 */
public final class OrderInterpreter implements AutoCloseable {
	private final GrammarInterpreter grammar;
	private final OrderValidator validator;
	private final Optional<LlmInterpreter> llm;

	public OrderInterpreter(GrammarInterpreter grammar, OrderValidator validator, Optional<LlmInterpreter> llm) {
		this.grammar = Objects.requireNonNull(grammar, "grammar");
		this.validator = Objects.requireNonNull(validator, "validator");
		this.llm = Objects.requireNonNull(llm, "llm");
	}

	public static OrderInterpreter grammarOnly() {
		return new OrderInterpreter(new GrammarInterpreter(), new OrderValidator(), Optional.empty());
	}

	public boolean llmAvailable() {
		return this.llm.isPresent();
	}

	public GrammarInterpreter grammar() {
		return this.grammar;
	}

	/** Grammar and validation only; never touches the network. */
	public Interpretation interpretLocally(String text, OwnerRoster roster) {
		GrammarInterpreter.Result result = this.grammar.parse(text, roster);
		if (!result.matched()) {
			return new Interpretation(Interpretation.Origin.NONE, List.of(), List.of(), result.addressed(), Optional.empty(),
					Optional.empty(), Optional.empty());
		}
		OrderValidator.Batch batch = this.validator.validateAll(result.orders(), roster);
		Interpretation.Origin origin = batch.accepted().isEmpty() ? Interpretation.Origin.NONE : Interpretation.Origin.GRAMMAR;
		return new Interpretation(origin, batch.accepted(), batch.rejected(), true, Optional.empty(), Optional.empty(), Optional.empty());
	}

	/**
	 * Full pipeline. Completes immediately on the grammar path.
	 *
	 * @param explicitlyAddressed true when the input channel itself is addressed to guards ({@code /guards say}, chat
	 *                            prefix); voice passes false and relies on the utterance naming a scope
	 */
	public CompletableFuture<Interpretation> interpret(UUID speaker, String text, OwnerRoster roster, boolean explicitlyAddressed) {
		Interpretation local = interpretLocally(text, roster);
		boolean grammarMatched = local.understood() || !local.rejected().isEmpty();
		boolean addressed = local.addressed() || explicitlyAddressed;
		if (grammarMatched || this.llm.isEmpty() || !addressed) {
			return CompletableFuture.completedFuture(withAddressed(local, addressed));
		}
		return this.llm.get().interpret(speaker, text, roster).thenApply(result -> fromLlm(result, roster));
	}

	@Override
	public void close() {
		this.llm.ifPresent(LlmInterpreter::close);
	}

	private Interpretation fromLlm(LlmResult result, OwnerRoster roster) {
		if (!result.ok()) {
			return new Interpretation(Interpretation.Origin.NONE, List.of(), List.of(), true, Optional.empty(),
					Optional.of(result.status()), result.retryAfter());
		}
		OrderValidator.Batch batch = this.validator.validateAll(result.orders(), roster);
		boolean clarifying = result.orders().isEmpty() && result.schemaRejections().isEmpty();
		// A reply is shown only alongside at least one valid order, or as a clarifying question with no orders.
		Optional<String> reply = (!batch.accepted().isEmpty() || clarifying) ? result.reply() : Optional.empty();
		Interpretation.Origin origin = batch.accepted().isEmpty() ? Interpretation.Origin.NONE : Interpretation.Origin.LLM;
		return new Interpretation(origin, batch.accepted(), batch.rejected(), true, reply, Optional.of(result.status()), Optional.empty());
	}

	private static Interpretation withAddressed(Interpretation local, boolean addressed) {
		if (local.addressed() == addressed) {
			return local;
		}
		return new Interpretation(local.origin(), local.orders(), local.rejected(), addressed, local.reply(), local.llmStatus(), local.retryAfter());
	}
}
