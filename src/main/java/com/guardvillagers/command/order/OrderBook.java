package com.guardvillagers.command.order;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Active player orders for one owner, with priority and expiry. Time comes from an injected clock in game ticks so
 * the book is deterministic in tests and pauses with the server.
 *
 * <p>Rules: a new order replaces orders on the same scope whose priority is not higher; an order for every guard
 * ({@link Scope#ALL}) also replaces narrower orders whose priority is not higher. An order is refused when a
 * higher-priority order already covers the same scope. "As you were" clears the scope (everything for ALL) up to its
 * own priority. Instant actions (status, appointments) are never stored.
 */
public final class OrderBook {
	public static final int MAX_ACTIVE = 32;
	public static final long DEFAULT_TIMED_EXPIRY_TICKS = 60L * 20L;

	public enum Status {
		/** Stored; {@link IssueResult#order()} holds it. */
		STORED,
		/** Instant action; execute it once, nothing stored. */
		INSTANT,
		/** "As you were": {@link IssueResult#removed()} lists what was cleared. */
		CLEARED,
		/** A higher-priority order covers this scope; nothing changed. */
		OUTRANKED
	}

	public record IssueResult(Status status, Optional<ActiveOrder> order, List<ActiveOrder> removed) {
		public IssueResult {
			removed = List.copyOf(removed);
		}
	}

	private static final Comparator<ActiveOrder> EFFECTIVE_ORDER = Comparator
			.comparingInt(ActiveOrder::priority)
			.thenComparingInt((ActiveOrder entry) -> entry.order().scope().specificity())
			.thenComparingLong(ActiveOrder::id);

	private final LongSupplier clock;
	private final long timedExpiryTicks;
	private final List<ActiveOrder> entries = new ArrayList<>();
	private long nextId = 1L;

	public OrderBook(LongSupplier gameTimeTicks) {
		this(gameTimeTicks, DEFAULT_TIMED_EXPIRY_TICKS);
	}

	public OrderBook(LongSupplier gameTimeTicks, long timedExpiryTicks) {
		this.clock = Objects.requireNonNull(gameTimeTicks, "gameTimeTicks");
		this.timedExpiryTicks = Math.max(1L, timedExpiryTicks);
	}

	public synchronized IssueResult issue(Order order, OrderSource source) {
		Objects.requireNonNull(order, "order");
		Objects.requireNonNull(source, "source");
		long now = this.clock.getAsLong();
		purgeExpired(now);
		Scope scope = order.scope();

		if (order.action() == Action.AS_YOU_WERE) {
			List<ActiveOrder> removed = removeIf(entry -> entry.priority() <= source.priority()
					&& (scope.kind() == Scope.Kind.ALL || entry.order().scope().sameAs(scope)));
			return new IssueResult(Status.CLEARED, Optional.empty(), removed);
		}
		if (order.action().persistence() == Action.Persistence.INSTANT) {
			return new IssueResult(Status.INSTANT, Optional.empty(), List.of());
		}
		for (ActiveOrder entry : this.entries) {
			if (entry.order().scope().sameAs(scope) && entry.priority() > source.priority()) {
				return new IssueResult(Status.OUTRANKED, Optional.empty(), List.of());
			}
		}
		List<ActiveOrder> removed = removeIf(entry -> entry.priority() <= source.priority()
				&& (entry.order().scope().sameAs(scope) || scope.kind() == Scope.Kind.ALL));
		if (this.entries.size() >= MAX_ACTIVE) {
			ActiveOrder weakest = this.entries.stream().min(EFFECTIVE_ORDER).orElseThrow();
			this.entries.remove(weakest);
			removed = concat(removed, weakest);
		}
		long expires = order.action().persistence() == Action.Persistence.TIMED ? now + this.timedExpiryTicks : ActiveOrder.NEVER;
		ActiveOrder stored = new ActiveOrder(this.nextId++, order, source, now, expires);
		this.entries.add(stored);
		return new IssueResult(Status.STORED, Optional.of(stored), removed);
	}

	/**
	 * The order a guard should obey: among live orders whose scope the caller says covers that guard, the highest
	 * priority wins, then the narrowest scope, then the newest.
	 */
	public synchronized Optional<ActiveOrder> effectiveFor(Predicate<Scope> coversGuard) {
		long now = this.clock.getAsLong();
		return this.entries.stream()
				.filter(entry -> !entry.isExpired(now) && coversGuard.test(entry.order().scope()))
				.max(EFFECTIVE_ORDER);
	}

	/** Live orders, oldest first. */
	public synchronized List<ActiveOrder> active() {
		long now = this.clock.getAsLong();
		return this.entries.stream().filter(entry -> !entry.isExpired(now)).toList();
	}

	/** Removes and returns orders whose time is up. Call from the owner's tick to announce or undo them. */
	public synchronized List<ActiveOrder> expire() {
		return purgeExpired(this.clock.getAsLong());
	}

	/** Ends an order early (target died, formation reached). */
	public synchronized boolean complete(long id) {
		return this.entries.removeIf(entry -> entry.id() == id);
	}

	public synchronized List<ActiveOrder> clear(Scope scope) {
		return removeIf(entry -> scope.kind() == Scope.Kind.ALL || entry.order().scope().sameAs(scope));
	}

	/**
	 * Clears every order that covers the whole of {@code scope}: the scope itself, and orders for every guard. A
	 * typed command for one group therefore also drops an "everyone" order, since that order covered the group.
	 */
	public synchronized List<ActiveOrder> clearCovering(Scope scope) {
		return removeIf(entry -> scope.kind() == Scope.Kind.ALL || entry.order().scope().kind() == Scope.Kind.ALL
				|| entry.order().scope().sameAs(scope));
	}

	public synchronized List<ActiveOrder> clearAll() {
		return removeIf(entry -> true);
	}

	/** Reloads persisted orders (for example from saved data). Ids continue after the highest restored id. */
	public synchronized void restore(Collection<ActiveOrder> saved) {
		for (ActiveOrder entry : saved) {
			if (this.entries.size() >= MAX_ACTIVE) {
				break;
			}
			this.entries.add(entry);
			this.nextId = Math.max(this.nextId, entry.id() + 1L);
		}
	}

	public long timedExpiryTicks() {
		return this.timedExpiryTicks;
	}

	private List<ActiveOrder> purgeExpired(long now) {
		return removeIf(entry -> entry.isExpired(now));
	}

	private List<ActiveOrder> removeIf(Predicate<ActiveOrder> filter) {
		List<ActiveOrder> removed = new ArrayList<>();
		for (Iterator<ActiveOrder> it = this.entries.iterator(); it.hasNext(); ) {
			ActiveOrder entry = it.next();
			if (filter.test(entry)) {
				it.remove();
				removed.add(entry);
			}
		}
		return removed;
	}

	private static List<ActiveOrder> concat(List<ActiveOrder> list, ActiveOrder extra) {
		List<ActiveOrder> out = new ArrayList<>(list);
		out.add(extra);
		return out;
	}
}
