package com.guardvillagers.command.order;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single gate every order passes before it reaches the order book, whether it came from the grammar or the LLM.
 * Scope names are re-resolved case-insensitively against the speaker's own roster and replaced with the roster
 * spelling; parameters must match the action. Ownership is re-checked again by the executor at execution time.
 */
public final class OrderValidator {
	public static final int MAX_ORDERS = 3;
	public static final int MAX_REPLY_CODE_POINTS = 120;

	public record Validation(Optional<Order> order, Optional<Rejection> rejection) {
		static Validation accepted(Order order) {
			return new Validation(Optional.of(order), Optional.empty());
		}

		static Validation rejected(Rejection rejection) {
			return new Validation(Optional.empty(), Optional.of(rejection));
		}

		public boolean isAccepted() {
			return this.order.isPresent();
		}
	}

	public record Rejected(Order order, Rejection reason) {
	}

	public record Batch(List<Order> accepted, List<Rejected> rejected) {
		public Batch {
			accepted = List.copyOf(accepted);
			rejected = List.copyOf(rejected);
		}
	}

	public Validation validate(Order order, OwnerRoster roster) {
		Objects.requireNonNull(order, "order");
		Objects.requireNonNull(roster, "roster");
		Action action = order.action();

		if (action.requiresFormation() && order.formation().isEmpty()) {
			return Validation.rejected(Rejection.MISSING_FORMATION);
		}
		if (!action.requiresFormation() && order.formation().isPresent()) {
			return Validation.rejected(Rejection.UNEXPECTED_FORMATION);
		}
		if (action.requiresTarget() && order.target().isEmpty()) {
			return Validation.rejected(Rejection.MISSING_TARGET);
		}
		if (!action.requiresTarget() && order.target().isPresent()) {
			return Validation.rejected(Rejection.UNEXPECTED_TARGET);
		}
		if (order.radius().isPresent()) {
			if (!action.allowsRadius()) {
				return Validation.rejected(Rejection.UNEXPECTED_RADIUS);
			}
			int radius = order.radius().getAsInt();
			if (radius < Order.MIN_RADIUS || radius > Order.MAX_RADIUS) {
				return Validation.rejected(Rejection.RADIUS_OUT_OF_RANGE);
			}
		}
		if (action.isAppointment() && order.scope().kind() != Scope.Kind.GUARD) {
			return Validation.rejected(Rejection.APPOINT_NEEDS_GUARD);
		}

		Scope scope = order.scope();
		Scope resolved;
		switch (scope.kind()) {
			case ALL, COMMANDER -> resolved = scope;
			case ROLE -> {
				if (scope.role().isEmpty()) {
					return Validation.rejected(Rejection.UNKNOWN_ROLE);
				}
				resolved = scope;
			}
			case GROUP -> {
				List<String> matches = matching(roster.groupNames(), scope.name());
				if (matches.isEmpty()) {
					return Validation.rejected(Rejection.UNKNOWN_SCOPE);
				}
				// "Blue" and "BLUE" are two different groups, so a case-insensitive name cannot pick one.
				if (matches.stream().distinct().count() > 1) {
					return Validation.rejected(Rejection.AMBIGUOUS_SCOPE);
				}
				resolved = Scope.group(matches.getFirst());
			}
			case GUARD -> {
				List<String> matches = matching(roster.guardNames(), scope.name());
				if (matches.isEmpty()) {
					return Validation.rejected(Rejection.UNKNOWN_SCOPE);
				}
				// Two guards with the same name may both follow an order, but a rank can only go to one of them.
				if (action.isAppointment() ? matches.size() > 1 : matches.stream().distinct().count() > 1) {
					return Validation.rejected(Rejection.AMBIGUOUS_SCOPE);
				}
				resolved = Scope.guard(matches.getFirst());
			}
			default -> throw new IllegalStateException("Unhandled scope kind " + scope.kind());
		}
		return Validation.accepted(order.withScope(resolved));
	}

	/** Validates up to {@link #MAX_ORDERS}; any beyond that are rejected with {@link Rejection#TOO_MANY_ORDERS}. */
	public Batch validateAll(List<Order> orders, OwnerRoster roster) {
		List<Order> accepted = new ArrayList<>();
		List<Rejected> rejected = new ArrayList<>();
		for (int i = 0; i < orders.size(); i++) {
			Order order = orders.get(i);
			if (i >= MAX_ORDERS) {
				rejected.add(new Rejected(order, Rejection.TOO_MANY_ORDERS));
				continue;
			}
			Validation validation = validate(order, roster);
			if (validation.isAccepted()) {
				accepted.add(validation.order().orElseThrow());
			} else {
				rejected.add(new Rejected(order, validation.rejection().orElseThrow()));
			}
		}
		return new Batch(accepted, rejected);
	}

	/**
	 * Makes a model or template reply safe to show or speak: control characters removed, whitespace collapsed, capped
	 * at {@link #MAX_REPLY_CODE_POINTS} code points. Never parse the result for actions.
	 */
	public static String capReply(String reply) {
		if (reply == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		int count = 0;
		boolean pendingSpace = false;
		for (int i = 0; i < reply.length() && count < MAX_REPLY_CODE_POINTS; ) {
			int cp = reply.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isWhitespace(cp) || Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) {
				pendingSpace = out.length() > 0;
				continue;
			}
			if (pendingSpace) {
				if (count + 1 >= MAX_REPLY_CODE_POINTS) {
					break;
				}
				out.append(' ');
				count++;
				pendingSpace = false;
			}
			out.appendCodePoint(cp);
			count++;
		}
		return out.toString();
	}

	private static List<String> matching(Collection<String> names, String wanted) {
		List<String> matches = new ArrayList<>();
		String key = wanted.strip();
		for (String name : names) {
			if (name != null && name.strip().equalsIgnoreCase(key)) {
				matches.add(name.strip());
			}
		}
		return matches;
	}
}
