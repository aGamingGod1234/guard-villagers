package com.guardvillagers.command.order;

import java.util.Locale;
import java.util.Optional;

/**
 * What an attack order points at. Resolved server-side at execution time: {@link #MY_TARGET} is a raycast from the
 * speaker's eyes (falling back to the last entity the speaker hit), {@link #MY_ATTACKER} is the speaker's last attacker.
 */
public enum TargetRef {
	MY_TARGET("my_target"),
	MY_ATTACKER("my_attacker"),
	NEAREST_HOSTILE("nearest_hostile");

	private final String wireName;

	TargetRef(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return this.wireName;
	}

	public static Optional<TargetRef> fromWireName(String name) {
		if (name == null) {
			return Optional.empty();
		}
		String key = name.toLowerCase(Locale.ROOT);
		for (TargetRef ref : values()) {
			if (ref.wireName.equals(key)) {
				return Optional.of(ref);
			}
		}
		return Optional.empty();
	}
}
