package com.guardvillagers.command.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guardvillagers.entity.FormationType;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

final class OrderBookTest {
	private final AtomicLong now = new AtomicLong(1000L);
	private final OrderBook book = new OrderBook(this.now::get, 1200L);

	/** A guard named Liam in group Alpha. */
	private static final Predicate<Scope> LIAM = scope -> switch (scope.kind()) {
		case ALL -> true;
		case GROUP -> scope.name().equals("Alpha");
		case GUARD -> scope.name().equals("Liam");
		default -> false;
	};

	@Test
	void timedOrdersExpireOnInjectedClock() {
		OrderBook.IssueResult result = this.book.issue(Order.of(Action.COME_HERE, Scope.ALL), OrderSource.VOICE);
		assertEquals(OrderBook.Status.STORED, result.status());
		ActiveOrder order = result.order().orElseThrow();
		assertEquals(2200L, order.expiresAtTick());
		assertEquals(1200L, order.remainingTicks(this.now.get()));

		this.now.set(2199L);
		assertEquals(List.of(), this.book.expire());
		assertEquals(1, this.book.active().size());

		this.now.set(2200L);
		assertEquals(0, this.book.active().size());
		assertEquals(List.of(order), this.book.expire());
		assertEquals(List.of(), this.book.expire());
	}

	@Test
	void untilChangedOrdersNeverExpire() {
		ActiveOrder hold = this.book.issue(Order.of(Action.HOLD, Scope.ALL), OrderSource.VOICE).order().orElseThrow();
		assertEquals(ActiveOrder.NEVER, hold.expiresAtTick());
		this.now.addAndGet(10_000_000L);
		assertEquals(List.of(hold), this.book.active());
	}

	@Test
	void sameScopeReplacesAndHigherPriorityIsNotOverridden() {
		this.book.issue(Order.of(Action.HOLD, Scope.group("Alpha")), OrderSource.TYPED);
		OrderBook.IssueResult voice = this.book.issue(Order.of(Action.FOLLOW, Scope.group("alpha")), OrderSource.VOICE);
		assertEquals(OrderBook.Status.OUTRANKED, voice.status());
		assertEquals(Action.HOLD, this.book.effectiveFor(LIAM).orElseThrow().order().action());

		OrderBook.IssueResult typed = this.book.issue(Order.of(Action.STAY, Scope.group("Alpha")), OrderSource.TYPED);
		assertEquals(OrderBook.Status.STORED, typed.status());
		assertEquals(1, typed.removed().size());
		assertEquals(1, this.book.active().size());
	}

	@Test
	void narrowerScopeWinsAtEqualPriorityInEitherIssueOrder() {
		this.book.issue(Order.of(Action.FOLLOW, Scope.ALL), OrderSource.VOICE);
		this.book.issue(Order.of(Action.HOLD, Scope.group("Alpha")), OrderSource.VOICE);
		assertEquals(Action.HOLD, this.book.effectiveFor(LIAM).orElseThrow().order().action());
		assertEquals(Action.FOLLOW, this.book.effectiveFor(scope -> scope.kind() == Scope.Kind.ALL).orElseThrow().order().action());

		OrderBook.IssueResult all = this.book.issue(Order.form(Scope.ALL, FormationType.LINE), OrderSource.VOICE);
		assertEquals(1, all.removed().size());
		assertEquals(Action.HOLD, this.book.effectiveFor(LIAM).orElseThrow().order().action());
	}

	@Test
	void asYouWereClearsScopeUpToItsPriority() {
		this.book.issue(Order.of(Action.HOLD, Scope.group("Alpha")), OrderSource.VOICE);
		this.book.issue(Order.of(Action.STAY, Scope.guard("Ava")), OrderSource.TYPED);
		this.book.issue(Order.of(Action.FOLLOW, Scope.group("Beta")), OrderSource.VOICE);

		OrderBook.IssueResult alphaOnly = this.book.issue(Order.of(Action.AS_YOU_WERE, Scope.group("ALPHA")), OrderSource.VOICE);
		assertEquals(OrderBook.Status.CLEARED, alphaOnly.status());
		assertEquals(1, alphaOnly.removed().size());

		OrderBook.IssueResult everyone = this.book.issue(Order.of(Action.AS_YOU_WERE, Scope.ALL), OrderSource.VOICE);
		assertEquals(1, everyone.removed().size());
		// The typed (priority 100) order survives a voice "as you were".
		assertEquals(Action.STAY, this.book.active().getFirst().order().action());
	}

	@Test
	void instantActionsAreNotStored() {
		assertEquals(OrderBook.Status.INSTANT, this.book.issue(Order.of(Action.STATUS_REPORT, Scope.ALL), OrderSource.VOICE).status());
		assertEquals(OrderBook.Status.INSTANT,
				this.book.issue(Order.of(Action.APPOINT_COMMANDER, Scope.guard("Liam")), OrderSource.VOICE).status());
		assertTrue(this.book.active().isEmpty());
	}

	@Test
	void completeEndsOrderEarly() {
		ActiveOrder attack = this.book.issue(Order.attack(Scope.ALL, TargetRef.MY_TARGET), OrderSource.VOICE).order().orElseThrow();
		assertTrue(this.book.complete(attack.id()));
		assertEquals(Optional.empty(), this.book.effectiveFor(LIAM));
	}

	@Test
	void boundedAndRestorable() {
		for (int i = 0; i < OrderBook.MAX_ACTIVE + 5; i++) {
			this.book.issue(Order.of(Action.HOLD, Scope.guard("g" + i)), OrderSource.VOICE);
		}
		assertEquals(OrderBook.MAX_ACTIVE, this.book.active().size());

		OrderBook copy = new OrderBook(this.now::get);
		copy.restore(this.book.active());
		ActiveOrder next = copy.issue(Order.of(Action.FOLLOW, Scope.guard("new")), OrderSource.VOICE).order().orElseThrow();
		long maxRestored = this.book.active().stream().mapToLong(ActiveOrder::id).max().orElseThrow();
		assertTrue(next.id() > maxRestored);
	}

	@Test
	void typedGroupOrderClearsEveryoneOrdersToo() {
		this.book.issue(Order.of(Action.HOLD, Scope.ALL), OrderSource.VOICE);
		this.book.issue(Order.of(Action.STAY, Scope.group("Beta")), OrderSource.VOICE);
		this.book.issue(Order.of(Action.FORM, Scope.group("Alpha")), OrderSource.VOICE);
		List<ActiveOrder> cleared = this.book.clearCovering(Scope.group("alpha"));
		assertEquals(2, cleared.size(), "Alpha's own order and the everyone order");
		assertEquals(List.of(Action.STAY), this.book.active().stream().map(order -> order.order().action()).toList());
	}
	@Test
	void interveningIssuesDoNotConsumeExpiryNotifications() {
		for (Action action : List.of(Action.STATUS_REPORT, Action.FOLLOW, Action.AS_YOU_WERE)) {
			var book = new OrderBook(now::get, 10);
			var expired = book.issue(Order.form(Scope.group("Alpha"), FormationType.CIRCLE), OrderSource.VOICE).order().orElseThrow();
			now.addAndGet(10);
			book.issue(Order.of(action, Scope.ALL), OrderSource.VOICE);
			assertEquals(List.of(expired), book.expire());
			assertTrue(book.expire().isEmpty());
		}
	}

	@Test
	void weakCandidateCannotEvictHigherPriority() {
		for (int i = 0; i < OrderBook.MAX_ACTIVE; i++) {
			book.issue(Order.of(Action.HOLD, Scope.guard("guard" + i)), OrderSource.TYPED);
		}
		var before = book.active();
		assertEquals(OrderBook.Status.OUTRANKED, book.issue(Order.of(Action.FOLLOW, Scope.ALL), OrderSource.VOICE).status());
		assertEquals(before, book.active());
	}

}
