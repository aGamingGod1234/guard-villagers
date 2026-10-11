package com.guardvillagers.navigation;

/** Server-thread quota with FIFO reservations for deferred work and reusable, allocation-free tickets. */
public final class PathRequestBudget {
	public static final class Ticket {
		private Ticket previous;
		private Ticket next;
		private PathRequestBudget queuedIn;
		private PathRequestBudget reservedIn;
		private long reservation;

		public void cancel() {
			if (this.queuedIn != null) this.queuedIn.unlink(this);
			this.reservedIn = null;
		}
	}

	private final int limit;
	private int used;
	private long tick = Long.MIN_VALUE;
	private long generation;
	private Ticket first;
	private Ticket last;

	public PathRequestBudget(int limit) {
		if (limit < 1) throw new IllegalArgumentException("limit must be positive");
		this.limit = limit;
	}

	public boolean tryAcquire(Ticket ticket, long now) {
		if (now != this.tick) {
			if (now < this.tick) {
				while (this.first != null) this.unlink(this.first);
			}
			this.tick = now;
			this.generation++;
			this.used = 0;
			// Reserve capacity before newly arriving work can consume it. An abandoned reservation
			// expires at the next tick, so a removed guard cannot block the queue.
			while (this.first != null && this.used < this.limit) {
				Ticket waiting = this.first;
				this.unlink(waiting);
				waiting.reservedIn = this;
				waiting.reservation = this.generation;
				this.used++;
			}
		}
		if (ticket.reservedIn == this && ticket.reservation == this.generation) {
			ticket.reservedIn = null;
			return true;
		}
		if (this.used < this.limit) {
			ticket.cancel();
			this.used++;
			return true;
		}
		if (ticket.queuedIn != this) {
			ticket.cancel();
			ticket.queuedIn = this;
			ticket.previous = this.last;
			if (this.last == null) this.first = ticket;
			else this.last.next = ticket;
			this.last = ticket;
		}
		return false;
	}

	private void unlink(Ticket ticket) {
		if (ticket.previous == null) this.first = ticket.next;
		else ticket.previous.next = ticket.next;
		if (ticket.next == null) this.last = ticket.previous;
		else ticket.next.previous = ticket.previous;
		ticket.previous = null;
		ticket.next = null;
		ticket.queuedIn = null;
	}
}
