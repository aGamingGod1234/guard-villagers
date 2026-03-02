package com.guardvillagers.client;

import net.minecraft.text.Text;

public enum RegionColor {
	NONE(0, "None", 0x00000000, 0xFF2A2F36),
	BLUE(1, "Blue", 0x663975D8, 0xFF3975D8),
	RED(2, "Red", 0x66D84A4A, 0xFFD84A4A),
	YELLOW(3, "Yellow", 0x66D8C34A, 0xFFD8C34A),
	GREEN(4, "Green", 0x6648B36A, 0xFF48B36A);

	private final int id;
	private final String label;
	private final int overlayArgb;
	private final int swatchArgb;

	RegionColor(int id, String label, int overlayArgb, int swatchArgb) {
		this.id = id;
		this.label = label;
		this.overlayArgb = overlayArgb;
		this.swatchArgb = swatchArgb;
	}

	public int id() {
		return this.id;
	}

	public int overlayArgb() {
		return this.overlayArgb;
	}

	public int swatchArgb() {
		return this.swatchArgb;
	}

	public String label() {
		return this.label;
	}

	public Text labelText() {
		return Text.literal(this.label);
	}

	public RegionColor nextPaletteColor() {
		return switch (this) {
			case NONE, GREEN -> BLUE;
			case BLUE -> RED;
			case RED -> YELLOW;
			case YELLOW -> GREEN;
		};
	}

	public RegionColor previousPaletteColor() {
		return switch (this) {
			case NONE, BLUE -> GREEN;
			case RED -> BLUE;
			case YELLOW -> RED;
			case GREEN -> YELLOW;
		};
	}

	public static RegionColor fromId(int id) {
		for (RegionColor color : values()) {
			if (color.id == id) {
				return color;
			}
		}
		return NONE;
	}
}
