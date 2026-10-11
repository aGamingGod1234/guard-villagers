package com.guardvillagers.command.order;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Who an order is for. {@code name} is null for {@link Kind#ALL} and {@link Kind#COMMANDER}; for {@link Kind#GROUP} and
 * {@link Kind#GUARD} it is the roster name, and for {@link Kind#ROLE} it is a {@link Role} wire name.
 * Validated orders always carry the roster's canonical spelling.
 */
public record Scope(Kind kind, String name) {
	public enum Kind {
		ALL("all"),
		COMMANDER("commander"),
		GROUP("group"),
		GUARD("guard"),
		/** "archers", "swordsmen": matched by role when no tactics group has that name. Not offered to the LLM. */
		ROLE("role");

		private final String wireName;

		Kind(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return this.wireName;
		}

		public boolean needsName() {
			return this == GROUP || this == GUARD || this == ROLE;
		}

		public static Optional<Kind> fromWireName(String name) {
			if (name == null) {
				return Optional.empty();
			}
			String key = name.toLowerCase(Locale.ROOT);
			for (Kind kind : values()) {
				if (kind.wireName.equals(key)) {
					return Optional.of(kind);
				}
			}
			return Optional.empty();
		}
	}

	public enum Role {
		BOWMEN("bowmen"),
		SWORDSMEN("swordsmen");

		private final String wireName;

		Role(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return this.wireName;
		}

		public static Optional<Role> fromWireName(String name) {
			if (name == null) {
				return Optional.empty();
			}
			String key = name.toLowerCase(Locale.ROOT);
			for (Role role : values()) {
				if (role.wireName.equals(key)) {
					return Optional.of(role);
				}
			}
			return Optional.empty();
		}
	}

	public static final Scope ALL = new Scope(Kind.ALL, null);
	public static final Scope COMMANDER = new Scope(Kind.COMMANDER, null);

	public Scope {
		Objects.requireNonNull(kind, "kind");
		if (kind.needsName()) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException(kind + " scope needs a name");
			}
			name = name.strip();
		} else {
			name = null;
		}
	}

	public static Scope group(String name) {
		return new Scope(Kind.GROUP, name);
	}

	public static Scope guard(String name) {
		return new Scope(Kind.GUARD, name);
	}

	public static Scope role(Role role) {
		return new Scope(Kind.ROLE, role.wireName());
	}

	public Optional<Role> role() {
		return this.kind == Kind.ROLE ? Role.fromWireName(this.name) : Optional.empty();
	}

	/** Narrower scopes win over broader ones at equal priority: ALL < ROLE < GROUP < COMMANDER = GUARD. */
	public int specificity() {
		return switch (this.kind) {
			case ALL -> 0;
			case ROLE -> 1;
			case GROUP -> 2;
			case COMMANDER, GUARD -> 3;
		};
	}

	/** Same scope, comparing names case-insensitively. */
	public boolean sameAs(Scope other) {
		if (other == null || other.kind != this.kind) {
			return false;
		}
		return this.name == null ? other.name == null : this.name.equalsIgnoreCase(other.name);
	}

	@Override
	public String toString() {
		return this.name == null ? this.kind.wireName() : this.kind.wireName() + ":" + this.name;
	}
}
