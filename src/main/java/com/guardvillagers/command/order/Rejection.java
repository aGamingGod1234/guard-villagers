package com.guardvillagers.command.order;

import java.util.Locale;

/** Why {@link OrderValidator} dropped an order. */
public enum Rejection {
	UNKNOWN_SCOPE,
	AMBIGUOUS_SCOPE,
	UNKNOWN_ROLE,
	MISSING_FORMATION,
	MISSING_TARGET,
	UNEXPECTED_FORMATION,
	UNEXPECTED_TARGET,
	UNEXPECTED_RADIUS,
	RADIUS_OUT_OF_RANGE,
	APPOINT_NEEDS_GUARD,
	TOO_MANY_ORDERS;

	/** Lang key, e.g. {@code guardvillagers.order.rejected.unknown_scope}. */
	public String translationKey() {
		return "guardvillagers.order.rejected." + name().toLowerCase(Locale.ROOT);
	}
}
