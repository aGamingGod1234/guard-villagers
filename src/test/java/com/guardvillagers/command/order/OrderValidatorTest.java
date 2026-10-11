package com.guardvillagers.command.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guardvillagers.entity.FormationType;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class OrderValidatorTest {
	private static final OwnerRoster ROSTER = OwnerRoster.of(List.of("Alpha", "Archers"), List.of("Liam", "Ava", "Noah", "Noah"));
	private final OrderValidator validator = new OrderValidator();

	private Rejection rejection(Order order) {
		OrderValidator.Validation validation = this.validator.validate(order, ROSTER);
		assertFalse(validation.isAccepted(), "expected rejection for " + order);
		return validation.rejection().orElseThrow();
	}

	private Order accepted(Order order) {
		OrderValidator.Validation validation = this.validator.validate(order, ROSTER);
		assertTrue(validation.isAccepted(), () -> "expected acceptance, got " + validation.rejection());
		return validation.order().orElseThrow();
	}

	@Test
	void resolvesScopeNamesCaseInsensitivelyToRosterSpelling() {
		assertEquals(Scope.group("Alpha"), accepted(Order.of(Action.HOLD, Scope.group("ALPHA"))).scope());
		assertEquals(Scope.guard("Liam"), accepted(Order.of(Action.COME_HERE, Scope.guard("liam"))).scope());
		assertEquals(Scope.ALL, accepted(Order.of(Action.FOLLOW, Scope.ALL)).scope());
		assertEquals(Scope.role(Scope.Role.BOWMEN), accepted(Order.of(Action.FOLLOW, Scope.role(Scope.Role.BOWMEN))).scope());
	}

	@Test
	void dropsUnknownScopeNames() {
		assertEquals(Rejection.UNKNOWN_SCOPE, rejection(Order.of(Action.HOLD, Scope.group("Bravo"))));
		assertEquals(Rejection.UNKNOWN_SCOPE, rejection(Order.of(Action.HOLD, Scope.guard("Steve"))));
		// A guard name is not a group name and vice versa.
		assertEquals(Rejection.UNKNOWN_SCOPE, rejection(Order.of(Action.HOLD, Scope.group("Liam"))));
		assertEquals(Rejection.UNKNOWN_ROLE, rejection(Order.of(Action.HOLD, new Scope(Scope.Kind.ROLE, "wizards"))));
	}

	@Test
	void dropsWrongParameterCombinations() {
		assertEquals(Rejection.MISSING_FORMATION, rejection(Order.of(Action.FORM, Scope.ALL)));
		assertEquals(Rejection.MISSING_TARGET, rejection(Order.of(Action.ATTACK_TARGET, Scope.ALL)));
		assertEquals(Rejection.UNEXPECTED_FORMATION,
				rejection(new Order(Action.HOLD, Scope.ALL, Optional.of(FormationType.LINE), Optional.empty(), OptionalInt.empty())));
		assertEquals(Rejection.UNEXPECTED_TARGET,
				rejection(new Order(Action.FOLLOW, Scope.ALL, Optional.empty(), Optional.of(TargetRef.MY_TARGET), OptionalInt.empty())));
		assertEquals(Rejection.UNEXPECTED_RADIUS,
				rejection(new Order(Action.HOLD, Scope.ALL, Optional.empty(), Optional.empty(), OptionalInt.of(16))));
		assertEquals(Rejection.RADIUS_OUT_OF_RANGE, rejection(Order.patrol(Scope.ALL, OptionalInt.of(7))));
		assertEquals(Rejection.RADIUS_OUT_OF_RANGE, rejection(Order.patrol(Scope.ALL, OptionalInt.of(129))));
		accepted(Order.patrol(Scope.ALL, OptionalInt.of(8)));
		accepted(Order.patrol(Scope.ALL, OptionalInt.of(128)));
		accepted(Order.form(Scope.group("alpha"), FormationType.CIRCLE));
		accepted(Order.attack(Scope.ALL, TargetRef.NEAREST_HOSTILE));
	}

	@Test
	void appointmentsNeedOneSpecificGuard() {
		assertEquals(Rejection.APPOINT_NEEDS_GUARD, rejection(Order.of(Action.APPOINT_COMMANDER, Scope.group("Alpha"))));
		assertEquals(Rejection.APPOINT_NEEDS_GUARD, rejection(Order.of(Action.APPOINT_CAPTAIN, Scope.ALL)));
		assertEquals(Scope.guard("Ava"), accepted(Order.of(Action.APPOINT_CAPTAIN, Scope.guard("ava"))).scope());
		// Two guards called Noah: either may be ordered, but a rank cannot go to "Noah".
		accepted(Order.of(Action.FOLLOW, Scope.guard("Noah")));
		assertEquals(Rejection.AMBIGUOUS_SCOPE, rejection(Order.of(Action.APPOINT_COMMANDER, Scope.guard("Noah"))));
	}

	@Test
	void groupsDifferingOnlyInCaseAreAmbiguous() {
		OwnerRoster roster = OwnerRoster.of(List.of("Blue", "BLUE"), List.of());
		OrderValidator.Validation validation = this.validator.validate(Order.of(Action.HOLD, Scope.group("blue")), roster);
		assertEquals(Optional.of(Rejection.AMBIGUOUS_SCOPE), validation.rejection());
	}

	@Test
	void batchCapsAtThreeOrders() {
		List<Order> four = List.of(Order.of(Action.HOLD, Scope.ALL), Order.of(Action.STAY, Scope.ALL),
				Order.of(Action.FOLLOW, Scope.ALL), Order.of(Action.RETREAT, Scope.ALL));
		OrderValidator.Batch batch = this.validator.validateAll(four, ROSTER);
		assertEquals(3, batch.accepted().size());
		assertEquals(1, batch.rejected().size());
		assertEquals(Rejection.TOO_MANY_ORDERS, batch.rejected().getFirst().reason());
	}

	@Test
	void capsAndCleansReplies() {
		assertEquals("Holding the line.", OrderValidator.capReply("  Holding\n\tthe   line.\u0000 "));
		String longReply = "x".repeat(500);
		assertEquals(OrderValidator.MAX_REPLY_CODE_POINTS, OrderValidator.capReply(longReply).length());
		assertEquals("", OrderValidator.capReply(null));
		String emoji = "🛡".repeat(200);
		assertEquals(OrderValidator.MAX_REPLY_CODE_POINTS, OrderValidator.capReply(emoji).codePointCount(0, OrderValidator.capReply(emoji).length()));
	}
}
