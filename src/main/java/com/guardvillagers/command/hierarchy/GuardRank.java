package com.guardvillagers.command.hierarchy;

/** Rank of an owned guard in its owner's chain of command. Stored as a byte in entity NBT and synced to clients. */
public enum GuardRank {
	SOLDIER(0),
	CAPTAIN(1),
	COMMANDER(2);

	private final int id;

	GuardRank(int id) {
		this.id = id;
	}

	public int id() {
		return this.id;
	}

	public boolean atLeast(GuardRank other) {
		return this.id >= other.id;
	}

	public static GuardRank fromId(int id) {
		for (GuardRank rank : values()) {
			if (rank.id == id) {
				return rank;
			}
		}
		return SOLDIER;
	}
}
