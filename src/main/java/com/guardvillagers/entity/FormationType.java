package com.guardvillagers.entity;

public enum FormationType {
	LINE(0),
	SQUARE(1),
	CIRCLE(2),
	FOLLOW(3);

	private final int id;

	FormationType(int id) {
		this.id = id;
	}

	public int getId() {
		return this.id;
	}

	public static FormationType fromId(int id) {
		for (FormationType type : values()) {
			if (type.id == id) {
				return type;
			}
		}
		return FOLLOW;
	}
}
