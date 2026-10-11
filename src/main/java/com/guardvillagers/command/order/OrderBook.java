package com.guardvillagers.command.order;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Active player orders for one owner, with priority and expiry. Time comes from an injected clock in game ticks so
 * the book is deterministic in tests and pauses with the server.
 *
 * <p>Rules: a new order replaces orders on the same scope whose priority is not higher. Overlapping scopes remain
 * available for per-recipient precedence, including narrower orders under ALL. An order is refused when a
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
		/** A stronger order blocks the scope or admission at capacity; nothing changed. */
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
	private long timedExpiryTicks;
	private final List<ActiveOrder> expiredAwaitingCleanup = new ArrayList<>();
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
		return issue(order, source, Optional.empty());
	}

	public synchronized IssueResult issue(Order order, OrderSource source, Set<UUID> recipients) {
		return issue(order, source, Optional.of(Set.copyOf(recipients)));
	}

	private IssueResult issue(Order order, OrderSource source, Optional<Set<UUID>> recipients) {
		Objects.requireNonNull(order, "order");
		Objects.requireNonNull(source, "source");
		long now = this.clock.getAsLong();
		this.expiredAwaitingCleanup.addAll(purgeExpired(now));
		Scope scope = order.scope();

		if (order.action() == Action.AS_YOU_WERE) {
			List<ActiveOrder> removed = removeRecipientsMatching(
					entry -> entry.priority() <= source.priority()
							&& (recipients.isPresent() || scope.kind() == Scope.Kind.ALL || entry.order().scope().sameAs(scope)),
					scope.kind() == Scope.Kind.ALL ? Optional.empty() : recipients);
			return new IssueResult(Status.CLEARED, Optional.empty(), removed);
		}
		if (order.action().persistence() == Action.Persistence.INSTANT) {
			return new IssueResult(Status.INSTANT, Optional.empty(), List.of());
		}
		for (ActiveOrder entry : this.entries) {
			if (entry.order().scope().sameAs(scope) && entry.priority() > source.priority()
					&& intersects(entry, recipients)) {
				return new IssueResult(Status.OUTRANKED, Optional.empty(), List.of());
			}
		}
		long expires = order.action().persistence() == Action.Persistence.TIMED ? now + this.timedExpiryTicks : ActiveOrder.NEVER;
		ActiveOrder candidate = new ActiveOrder(this.nextId, order, source, now, expires, recipients);
		Predicate<ActiveOrder> replaces = entry -> entry.priority() <= source.priority()
				&& entry.order().scope().sameAs(scope);
		// Partial recipient replacement does not free a slot. Check admission before changing those entries.
		boolean freesSlot = this.entries.stream().anyMatch(entry -> replaces.test(entry) && intersects(entry, recipients)
				&& (recipients.isEmpty() || entry.recipients().isEmpty() || recipients.get().containsAll(entry.recipients().get())));
		if (this.entries.size() >= MAX_ACTIVE && !freesSlot) {
			ActiveOrder weakest = this.entries.stream().min(EFFECTIVE_ORDER).orElseThrow();
			if (EFFECTIVE_ORDER.compare(candidate, weakest) < 0) {
				return new IssueResult(Status.OUTRANKED, Optional.empty(), List.of());
			}
		}
		List<ActiveOrder> removed = removeRecipientsMatching(replaces, recipients);
		if (this.entries.size() >= MAX_ACTIVE) {
			ActiveOrder weakest = this.entries.stream().min(EFFECTIVE_ORDER).orElseThrow();
			this.entries.remove(weakest);
			removed = concat(removed, weakest);
		}
		ActiveOrder stored = candidate;
		this.nextId++;
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

	/** Recipient snapshots are authoritative even after renames, rank changes, or dimension changes. */
	public synchronized Optional<ActiveOrder> effectiveForGuard(UUID guard, Predicate<Scope> coversGuard) {
		long now = this.clock.getAsLong();
		return this.entries.stream()
				.filter(entry -> !entry.isExpired(now)
						&& entry.recipients().map(ids -> ids.contains(guard)).orElseGet(() -> coversGuard.test(entry.order().scope())))
				.max(EFFECTIVE_ORDER);
	}

	/** Removes only the addressed members, leaving other recipients' orders intact. */
	public synchronized List<ActiveOrder> removeRecipients(Set<UUID> recipients) {
		return removeRecipientsMatching(entry -> true, Optional.of(Set.copyOf(recipients)));
	}

	/** Whether an incoming persistent order would win for a concrete recipient supplied by the caller. */
	public synchronized boolean wouldBeEffective(Order order, OrderSource source, Predicate<Scope> coversGuard) {
		if (!coversGuard.test(order.scope())) return false;
		return effectiveFor(coversGuard).map(existing -> source.priority() > existing.priority()
				|| (source.priority() == existing.priority()
						&& order.scope().specificity() >= existing.order().scope().specificity())).orElse(true);
	}

	/** Admission uses bound recipients even when a guard's name, role or group has changed. */
	public synchronized boolean wouldBeEffectiveForGuard(Order order, OrderSource source, UUID guard,
			Predicate<Scope> coversGuard) {
		if (!coversGuard.test(order.scope())) return false;
		return effectiveForGuard(guard, coversGuard).map(existing -> source.priority() > existing.priority()
				|| (source.priority() == existing.priority()
						&& order.scope().specificity() >= existing.order().scope().specificity())).orElse(true);
	}

	/** Live orders, oldest first. */
	public synchronized List<ActiveOrder> active() {
		long now = this.clock.getAsLong();
		return this.entries.stream().filter(entry -> !entry.isExpired(now)).toList();
	}

	/** Removes and returns orders whose time is up. Call from the owner's tick to announce or undo them. */
	public synchronized List<ActiveOrder> expire() {
		List<ActiveOrder> expired = new ArrayList<>(this.expiredAwaitingCleanup);
		this.expiredAwaitingCleanup.clear();
		expired.addAll(purgeExpired(this.clock.getAsLong()));
		return List.copyOf(expired);
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

	public synchronized void setTimedExpiryTicks(long ticks) {
		this.timedExpiryTicks = Math.max(1L, ticks);
	}

	private static boolean intersects(ActiveOrder entry, Optional<Set<UUID>> recipients) {
		return recipients.isEmpty() || (!recipients.get().isEmpty() && (entry.recipients().isEmpty()
				|| entry.recipients().get().stream().anyMatch(recipients.get()::contains)));
	}

	private List<ActiveOrder> removeRecipientsMatching(Predicate<ActiveOrder> filter, Optional<Set<UUID>> recipients) {
		List<ActiveOrder> removed = new ArrayList<>();
		for (var it = this.entries.listIterator(); it.hasNext();) {
			ActiveOrder entry = it.next();
			if (!filter.test(entry) || !intersects(entry, recipients)) continue;
			if (recipients.isEmpty() || entry.recipients().isEmpty()) {
				it.remove();
				removed.add(entry);
				continue;
			}
			Set<UUID> affected = new HashSet<>(entry.recipients().get());
			affected.retainAll(recipients.get());
			Set<UUID> surviving = new HashSet<>(entry.recipients().get());
			surviving.removeAll(affected);
			removed.add(entry.withRecipients(affected));
			if (surviving.isEmpty()) it.remove();
			else it.set(entry.withRecipients(surviving));
		}
		return removed;
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
