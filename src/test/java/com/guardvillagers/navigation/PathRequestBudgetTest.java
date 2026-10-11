package com.guardvillagers.navigation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PathRequestBudgetTest {
	@Test
	void boundsEachTickAndReservesOldWorkBeforeNewArrivals() {
		PathRequestBudget budget = new PathRequestBudget(1);
		var first = new PathRequestBudget.Ticket();
		var waiting = new PathRequestBudget.Ticket();
		assertTrue(budget.tryAcquire(first, 0));
		assertFalse(budget.tryAcquire(waiting, 0));
		assertFalse(budget.tryAcquire(waiting, 0));
		assertFalse(budget.tryAcquire(first, 1));
		assertTrue(budget.tryAcquire(waiting, 1));
		assertFalse(budget.tryAcquire(waiting, 1));
		assertTrue(budget.tryAcquire(first, 2));
	}

	@Test
	void hundredsOfRepeatedCallersCannotStarve() {
		PathRequestBudget budget = new PathRequestBudget(64);
		var tickets = new PathRequestBudget.Ticket[512];
		int[] acquired = new int[512];
		for (int i = 0; i < tickets.length; i++) tickets[i] = new PathRequestBudget.Ticket();
		for (int tick = 0; tick < 16; tick++) {
			int count = 0;
			for (int i = 0; i < tickets.length; i++) {
				if (budget.tryAcquire(tickets[i], tick)) { acquired[i]++; count++; }
			}
			assertEquals(64, count);
		}
		for (int count : acquired) assertEquals(2, count);
	}

	@Test
	void cancelledAndAbandonedTicketsDoNotBlockLaterWork() {
		PathRequestBudget budget = new PathRequestBudget(1);
		var a = new PathRequestBudget.Ticket();
		var b = new PathRequestBudget.Ticket();
		var c = new PathRequestBudget.Ticket();
		assertTrue(budget.tryAcquire(a, 0));
		assertFalse(budget.tryAcquire(b, 0));
		assertFalse(budget.tryAcquire(c, 0));
		b.cancel();
		assertTrue(budget.tryAcquire(c, 1));
		assertFalse(budget.tryAcquire(b, 1));
		// b never returns to use its reserved slot in tick 2.
		assertFalse(budget.tryAcquire(a, 2));
		assertTrue(budget.tryAcquire(a, 3));
	}

	@Test
	void ticketsMoveBetweenBudgetsAndClockResetDropsPendingWork() {
		PathRequestBudget a = new PathRequestBudget(1);
		PathRequestBudget b = new PathRequestBudget(1);
		var ticket = new PathRequestBudget.Ticket();
		assertTrue(a.tryAcquire(ticket, 10));
		assertFalse(a.tryAcquire(ticket, 10));
		assertTrue(b.tryAcquire(ticket, 10));
		assertTrue(a.tryAcquire(ticket, 0));
		assertThrows(IllegalArgumentException.class, () -> new PathRequestBudget(0));
	}
}
