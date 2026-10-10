package com.guardvillagers.command.order;

/** Where an order came from, with its priority from the design's priority table. Higher wins. */
public enum OrderSource {
	/** Typed {@code /guards stay|hold|follow ...}. */
	TYPED(100),
	/** Voice, {@code /guards say} or the chat prefix. */
	VOICE(90),
	/** Whistle rally. */
	WHISTLE(80),
	/** Commander auto-assignment. */
	COMMANDER_AUTO(50);

	private final int priority;

	OrderSource(int priority) {
		this.priority = priority;
	}

	public int priority() {
		return this.priority;
	}
}
