package com.guardvillagers.command.order;

import java.util.Objects;

/**
 * An order stored in the {@link OrderBook}. Times are in game ticks. {@code expiresAtTick} is {@link #NEVER} for orders
 * that last until changed.
 */
public record ActiveOrder(long id, Order order, OrderSource source, long issuedAtTick, long expiresAtTick) {
	public static final long NEVER = Long.MAX_VALUE;

	public ActiveOrder {
		Objects.requireNonNull(order, "order");
		Objects.requireNonNull(source, "source");
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
