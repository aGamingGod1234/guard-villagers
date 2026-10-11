package com.guardvillagers.command.order;

import java.util.Locale;
import java.util.Optional;

/** Every order a player can give by voice, chat or the LLM. The wire name is the LLM schema value. */
public enum Action {
	FOLLOW("follow", Persistence.UNTIL_CHANGED),
	STAY("stay", Persistence.UNTIL_CHANGED),
	HOLD("hold", Persistence.UNTIL_CHANGED),
	COME_HERE("come_here", Persistence.TIMED),
	ATTACK_TARGET("attack_target", Persistence.TIMED),
	DEFEND_VILLAGE("defend_village", Persistence.UNTIL_CHANGED),
	PATROL_HERE("patrol_here", Persistence.UNTIL_CHANGED),
	FORM("form", Persistence.TIMED),
	RETREAT("retreat", Persistence.TIMED),
	AS_YOU_WERE("as_you_were", Persistence.INSTANT),
	STATUS_REPORT("status_report", Persistence.INSTANT),
	APPOINT_COMMANDER("appoint_commander", Persistence.INSTANT),
	APPOINT_CAPTAIN("appoint_captain", Persistence.INSTANT);

	/** How long an order stays in the {@link OrderBook}. */
	public enum Persistence {
		/** Stays until another order replaces it or "as you were" clears it. */
		UNTIL_CHANGED,
		/** Expires after the configured voice-order expiry (default 60 s) or when completed early. */
		TIMED,
		/** Executes once and is never stored. */
		INSTANT
	}

	private final String wireName;
	private final Persistence persistence;

	Action(String wireName, Persistence persistence) {
		this.wireName = wireName;
		this.persistence = persistence;
	}

	public String wireName() {
		return this.wireName;
	}

	public Persistence persistence() {
		return this.persistence;
	}

	public boolean requiresFormation() {
		return this == FORM;
	}

	public boolean requiresTarget() {
		return this == ATTACK_TARGET;
	}

	public boolean allowsRadius() {
		return this == PATROL_HERE;
	}

	public boolean isAppointment() {
		return this == APPOINT_COMMANDER || this == APPOINT_CAPTAIN;
	}

	/** Lang key for the action's display name, e.g. {@code guardvillagers.order.action.hold}. */
	public String translationKey() {
		return "guardvillagers.order.action." + this.wireName;
	}

	/** Lang key for the commander's acknowledgement line, e.g. {@code guardvillagers.order.ack.hold}. */
	public String ackKey() {
		return "guardvillagers.order.ack." + this.wireName;
	}

	public static Optional<Action> fromWireName(String name) {
		if (name == null) {
			return Optional.empty();
		}
		String key = name.toLowerCase(Locale.ROOT);
		for (Action action : values()) {
			if (action.wireName.equals(key)) {
				return Optional.of(action);
			}
		}
		return Optional.empty();
	}
}
