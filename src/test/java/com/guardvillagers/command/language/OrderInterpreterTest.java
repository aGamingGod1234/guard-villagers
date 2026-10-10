package com.guardvillagers.command.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.guardvillagers.command.language.llm.LlmConfig;
import com.guardvillagers.command.language.llm.LlmInterpreter;
import com.guardvillagers.command.language.llm.LlmResult;
import com.guardvillagers.command.language.llm.LlmTransport;
import com.guardvillagers.command.language.llm.LlmTransportException;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OrderValidator;
import com.guardvillagers.command.order.OwnerRoster;
import com.guardvillagers.command.order.Rejection;
import com.guardvillagers.command.order.Scope;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OrderInterpreterTest {
	private static final OwnerRoster ROSTER = OwnerRoster.of(List.of("Alpha"), List.of("Liam", "Noah", "Noah"));
	private static final UUID PLAYER = UUID.randomUUID();

	private final AtomicInteger calls = new AtomicInteger();
	private final AtomicReference<String> nextArguments = new AtomicReference<>("{\"orders\":[],\"reply\":\"\"}");

	private OrderInterpreter withLlm() {
		LlmTransport fake = body -> {
			this.calls.incrementAndGet();
			JsonObject call = new JsonObject();
			call.addProperty("type", "function_call");
			call.addProperty("name", "issue_orders");
			call.addProperty("arguments", this.nextArguments.get());
			return CompletableFuture.completedFuture("{\"output\":[" + call + "]}");
		};
		LlmInterpreter llm = new LlmInterpreter(fake, LlmConfig.defaults(), System::currentTimeMillis);
		return new OrderInterpreter(new GrammarInterpreter(), new OrderValidator(), Optional.of(llm));
	}

	private static String order(String action, String kind, String name) {
		return "{\"action\":\"" + action + "\",\"scope_kind\":\"" + kind + "\",\"scope_name\":" + (name == null ? "null" : "\"" + name + "\"")
				+ ",\"formation\":null,\"target\":null,\"radius\":null}";
	}

	@Test
	void grammarMatchNeverCallsLlm() throws Exception {
		Interpretation result = withLlm().interpret(PLAYER, "alpha hold the line", ROSTER, false).get();
		assertEquals(Interpretation.Origin.GRAMMAR, result.origin());
		assertEquals(List.of(Order.of(Action.HOLD, Scope.group("Alpha"))), result.orders());
		assertEquals(0, this.calls.get());
	}

	@Test
	void grammarOnlySaysNotUnderstoodWhenAddressed() throws Exception {
		OrderInterpreter interpreter = OrderInterpreter.grammarOnly();
		assertFalse(interpreter.llmAvailable());
		Interpretation addressed = interpreter.interpret(PLAYER, "commander, dig a moat", ROSTER, false).get();
		assertTrue(addressed.shouldSayNotUnderstood());
		Interpretation chatter = interpreter.interpret(PLAYER, "nice house you built", ROSTER, false).get();
		assertFalse(chatter.shouldSayNotUnderstood());
		Interpretation say = interpreter.interpret(PLAYER, "nice house you built", ROSTER, true).get();
		assertTrue(say.shouldSayNotUnderstood(), "/guards say is always addressed");
	}

	@Test
	void unaddressedChatterNeverReachesLlm() throws Exception {
		Interpretation result = withLlm().interpret(PLAYER, "anyone want to go mining", ROSTER, false).get();
		assertEquals(Interpretation.Origin.NONE, result.origin());
		assertEquals(0, this.calls.get());
		assertFalse(result.shouldSayNotUnderstood());
	}

	@Test
	void llmFallbackIsValidatedLikeGrammar() throws Exception {
		this.nextArguments.set("{\"orders\":[" + order("hold", "group", "alpha") + "," + order("follow", "group", "Bravo") + "],"
				+ "\"reply\":\"Alpha digging in.\"}");
		Interpretation result = withLlm().interpret(PLAYER, "alpha dig in", ROSTER, false).get();
		assertEquals(1, this.calls.get());
		assertEquals(Interpretation.Origin.LLM, result.origin());
		assertEquals(List.of(Order.of(Action.HOLD, Scope.group("Alpha"))), result.orders());
		assertEquals(1, result.rejected().size());
		assertEquals(Rejection.UNKNOWN_SCOPE, result.rejected().getFirst().reason());
		assertEquals(Optional.of("Alpha digging in."), result.reply());
	}

	@Test
	void replyDroppedWhenEveryOrderIsInvalid() throws Exception {
		this.nextArguments.set("{\"orders\":[" + order("appoint_commander", "all", null) + "],\"reply\":\"Done, I made everyone commander.\"}");
		Interpretation result = withLlm().interpret(PLAYER, "commander, everyone is in charge", ROSTER, false).get();
		assertFalse(result.understood());
		assertEquals(Optional.empty(), result.reply());
		assertTrue(result.shouldSayNotUnderstood());
	}

	@Test
	void clarifyingQuestionIsKept() throws Exception {
		this.nextArguments.set("{\"orders\":[],\"reply\":\"Which group should hold?\"}");
		Interpretation result = withLlm().interpret(PLAYER, "guards, do the thing", ROSTER, false).get();
		assertFalse(result.understood());
		assertEquals(Optional.of("Which group should hold?"), result.reply());
		assertFalse(result.shouldSayNotUnderstood());
	}

	@Test
	void grammarRejectionDoesNotFallBackToLlm() throws Exception {
		// Two guards are named Noah: the grammar understood, the validator refuses to pick one.
		Interpretation result = withLlm().interpret(PLAYER, "make noah commander", ROSTER, false).get();
		assertEquals(0, this.calls.get());
		assertFalse(result.understood());
		assertEquals(Rejection.AMBIGUOUS_SCOPE, result.rejected().getFirst().reason());
	}

	@Test
	void transientLlmFailureIsFlagged() throws Exception {
		LlmTransport limited = body -> CompletableFuture.failedFuture(
				new LlmTransportException(LlmTransportException.Kind.RATE_LIMITED, 429, Duration.ofSeconds(3), "429"));
		OrderInterpreter interpreter = new OrderInterpreter(new GrammarInterpreter(), new OrderValidator(),
				Optional.of(new LlmInterpreter(limited, LlmConfig.defaults(), System::currentTimeMillis)));
		Interpretation result = interpreter.interpret(PLAYER, "alpha dig in", ROSTER, false).get();
		assertEquals(Optional.of(LlmResult.Status.RATE_LIMITED), result.llmStatus());
		assertEquals(Optional.of(Duration.ofSeconds(3)), result.retryAfter());
		assertTrue(result.llmTemporarilyUnavailable());
	}
}
