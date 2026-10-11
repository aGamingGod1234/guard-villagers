package com.guardvillagers.command.order;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An order stored in the {@link OrderBook}. Times are in game ticks. {@code expiresAtTick} is {@link #NEVER} for orders
 * that last until changed.
 */
public record ActiveOrder(long id, Order order, OrderSource source, long issuedAtTick, long expiresAtTick,
		Optional<Set<UUID>> recipients) {
	public static final long NEVER = Long.MAX_VALUE;

	public ActiveOrder {
		Objects.requireNonNull(order, "order");
		Objects.requireNonNull(source, "source");
		recipients = Objects.requireNonNull(recipients, "recipients").map(Set::copyOf);
	}

	/** Legacy orders have no recipient snapshot; new orders bind the guards actually addressed. */
	public ActiveOrder(long id, Order order, OrderSource source, long issuedAtTick, long expiresAtTick) {
		this(id, order, source, issuedAtTick, expiresAtTick, Optional.empty());
	}

	public ActiveOrder withRecipients(Set<UUID> recipients) {
		return new ActiveOrder(this.id, this.order, this.source, this.issuedAtTick, this.expiresAtTick, Optional.of(recipients));
	}

	public int priority() {
		return this.source.priority();
	}

	public boolean isExpired(long nowTick) {
		return this.expiresAtTick != NEVER && nowTick >= this.expiresAtTick;
	}

	/** Ticks left, or {@link #NEVER} for until-changed orders. */
	public long remainingTicks(long nowTick) {
		return this.expiresAtTick == NEVER ? NEVER : Math.max(0L, this.expiresAtTick - nowTick);
	}
}
