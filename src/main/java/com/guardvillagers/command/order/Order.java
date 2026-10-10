package com.guardvillagers.command.order;

import com.guardvillagers.entity.FormationType;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One order, shared by the grammar, the LLM interpreter, chat and voice. Positions are never part of an order: "here"
 * is the speaker's position at execution time. Run every order through {@link OrderValidator} before executing it.
 */
public record Order(Action action, Scope scope, Optional<FormationType> formation, Optional<TargetRef> target, OptionalInt radius) {
	public static final int MIN_RADIUS = 8;
	public static final int MAX_RADIUS = 128;

	public Order {
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(formation, "formation");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(radius, "radius");
	}

	public static Order of(Action action, Scope scope) {
		return new Order(action, scope, Optional.empty(), Optional.empty(), OptionalInt.empty());
	}

	public static Order form(Scope scope, FormationType formation) {
		return new Order(Action.FORM, scope, Optional.of(formation), Optional.empty(), OptionalInt.empty());
	}

	public static Order attack(Scope scope, TargetRef target) {
		return new Order(Action.ATTACK_TARGET, scope, Optional.empty(), Optional.of(target), OptionalInt.empty());
	}

	public static Order patrol(Scope scope, OptionalInt radius) {
		return new Order(Action.PATROL_HERE, scope, Optional.empty(), Optional.empty(), radius);
	}

	public Order withScope(Scope newScope) {
		return new Order(this.action, newScope, this.formation, this.target, this.radius);
	}
}
