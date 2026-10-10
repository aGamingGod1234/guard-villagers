package com.guardvillagers.client;

import net.minecraft.network.chat.Component;

public enum RegionColor {
	NONE(0, "None", 0x00000000, 0xFF2A2F36),
	BLUE(1, "Blue", 0x663975D8, 0xFF3975D8),
	RED(2, "Red", 0x66D84A4A, 0xFFD84A4A),
	YELLOW(3, "Yellow", 0x66D8C34A, 0xFFD8C34A),
	GREEN(4, "Green", 0x6648B36A, 0xFF48B36A),
	PURPLE(5, "Purple", 0x669B59D8, 0xFF9B59D8),
	ORANGE(6, "Orange", 0x66E67E22, 0xFFE67E22),
	CYAN(7, "Cyan", 0x6600BCD4, 0xFF00BCD4),
	PINK(8, "Pink", 0x66E91E8C, 0xFFE91E8C),
	BROWN(9, "Brown", 0x66795548, 0xFF795548),
	WHITE(10, "White", 0x66ECEFF1, 0xFFECEFF1);

	private static final RegionColor[] PALETTE_COLORS = {
		BLUE, RED, YELLOW, GREEN, PURPLE, ORANGE, CYAN, PINK, BROWN, WHITE
	};
	// values() clones its array on every call; fromId runs per painted chunk per frame.
	private static final RegionColor[] VALUES = values();
	private static final String[] HOTKEY_LABELS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

	private final int id;
	private final String label;
	private final int overlayArgb;
	private final int swatchArgb;
	private final Component displayName;

	RegionColor(int id, String label, int overlayArgb, int swatchArgb) {
		this.id = id;
		this.label = label;
		this.overlayArgb = overlayArgb;
		this.swatchArgb = swatchArgb;
		this.displayName = Component.translatable("color.guardvillagers.zone." + this.name().toLowerCase(java.util.Locale.ROOT));
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

	public Component labelText() {
		return this.displayName;
	}

	/**
	 * Index in the paint palette, or -1 for {@link #NONE}. The palette index doubles as the number-key shortcut
	 * (1-9, then 0) and as the glyph drawn on zones so colours stay distinguishable without relying on hue.
	 */
	public int paletteIndex() {
		for (int i = 0; i < PALETTE_COLORS.length; i++) {
			if (PALETTE_COLORS[i] == this) {
				return i;
			}
		}
		return -1;
	}

	/** Number-key shortcut / zone glyph for this colour, or an empty string for {@link #NONE}. */
	public String hotkeyLabel() {
		int index = this.paletteIndex();
		return index < 0 ? "" : HOTKEY_LABELS[index];
	}

	/** Maps a number key (1-9, 0) to its palette colour, or null when the digit is out of range. */
	public static RegionColor forHotkeyDigit(int digit) {
		if (digit < 0 || digit > 9) {
			return null;
		}
		int index = digit == 0 ? 9 : digit - 1;
		return index < PALETTE_COLORS.length ? PALETTE_COLORS[index] : null;
	}

	/** True when dark text reads better than white on this colour's swatch. */
	public boolean prefersDarkText() {
		int r = (this.swatchArgb >> 16) & 0xFF;
		int g = (this.swatchArgb >> 8) & 0xFF;
		int b = this.swatchArgb & 0xFF;
		return (r * 299 + g * 587 + b * 114) / 1000 > 150;
	}

	public static RegionColor[] paletteColors() {
		return PALETTE_COLORS;
	}

	public static int paletteCount() {
		return PALETTE_COLORS.length;
	}

	public static RegionColor paletteAt(int index) {
		if (index < 0 || index >= PALETTE_COLORS.length) {
			return BLUE;
		}
		return PALETTE_COLORS[index];
	}

	public RegionColor nextPaletteColor() {
		for (int i = 0; i < PALETTE_COLORS.length; i++) {
			if (PALETTE_COLORS[i] == this) {
				return PALETTE_COLORS[(i + 1) % PALETTE_COLORS.length];
			}
		}
		return BLUE;
	}

	public RegionColor previousPaletteColor() {
		for (int i = 0; i < PALETTE_COLORS.length; i++) {
			if (PALETTE_COLORS[i] == this) {
				return PALETTE_COLORS[(i - 1 + PALETTE_COLORS.length) % PALETTE_COLORS.length];
			}
		}
		return BLUE;
	}

	public static RegionColor fromId(int id) {
		for (RegionColor color : VALUES) {
			if (color.id == id) {
				return color;
			}
		}
		return NONE;
	}
}
