package com.guardvillagers.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Drag state for guard cards. A press only "arms" the drag; it becomes active once the cursor moves a few
 * pixels, so plain clicks never pick a card up by accident.
 */
public final class GuardDragHandler {
	private static final ItemStack GUARD_HEAD_ICON = new ItemStack(Items.PLAYER_HEAD);
	private static final double DRAG_START_DISTANCE_SQ = 4.0D * 4.0D;
	private static final int CARD_WIDTH = 86;
	private static final int CARD_HEIGHT = 44;

	private ClientGuardRosterStore.GuardRosterEntry draggedGuard;
	private int sourceGroupIndex = -1;
	private boolean fromUnassigned;
	private double pressX;
	private double pressY;
	private double dragX;
	private double dragY;
	private boolean armed;
	private boolean active;
	private String cachedName = "";
	private ClientGuardRosterStore.GuardRosterEntry cachedNameFor;

	public void arm(ClientGuardRosterStore.GuardRosterEntry guard, int sourceGroup, boolean unassigned, double mouseX, double mouseY) {
		this.draggedGuard = guard;
		this.sourceGroupIndex = sourceGroup;
		this.fromUnassigned = unassigned;
		this.pressX = mouseX;
		this.pressY = mouseY;
		this.dragX = mouseX;
		this.dragY = mouseY;
		this.armed = true;
		this.active = false;
	}

	/** Returns true when this call turned an armed press into an active drag. */
	public boolean updateDrag(double mouseX, double mouseY) {
		this.dragX = mouseX;
		this.dragY = mouseY;
		if (this.armed && !this.active) {
			double dx = mouseX - this.pressX;
			double dy = mouseY - this.pressY;
			if ((dx * dx) + (dy * dy) >= DRAG_START_DISTANCE_SQ) {
				this.active = true;
				return true;
			}
		}
		return false;
	}

	public boolean isActive() {
		return this.active;
	}

	public boolean isArmed() {
		return this.armed;
	}

	public ClientGuardRosterStore.GuardRosterEntry draggedGuard() {
		return this.draggedGuard;
	}

	public int sourceGroupIndex() {
		return this.sourceGroupIndex;
	}

	public boolean isFromUnassigned() {
		return this.fromUnassigned;
	}

	public void cancel() {
		this.armed = false;
		this.active = false;
		this.draggedGuard = null;
		this.sourceGroupIndex = -1;
		this.fromUnassigned = false;
	}

	public DropResult drop() {
		DropResult result = new DropResult(this.draggedGuard, this.sourceGroupIndex, this.fromUnassigned);
		this.cancel();
		return result;
	}

	/**
	 * @param targetLabel what releasing here would do, drawn under the card; null when not over a target
	 * @param accentColor border colour, reflecting whether the drop is valid
	 */
	public void render(GuiGraphicsExtractor graphics, Font font, Component targetLabel, int accentColor) {
		if (!this.active || this.draggedGuard == null) {
			return;
		}

		int x = (int) this.dragX - CARD_WIDTH / 2;
		int y = (int) this.dragY - CARD_HEIGHT / 2;

		graphics.fill(x + 2, y + 2, x + CARD_WIDTH + 2, y + CARD_HEIGHT + 2, 0x66000000);
		graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, 0xEE1E2A38);
		graphics.outline(x, y, CARD_WIDTH, CARD_HEIGHT, accentColor);

		graphics.item(GUARD_HEAD_ICON, x + 4, y + 4);
		if (this.cachedNameFor != this.draggedGuard) {
			this.cachedNameFor = this.draggedGuard;
			this.cachedName = GuardTacticsScreen.ellipsize(font, this.draggedGuard.displayName(), CARD_WIDTH - 26);
		}
		graphics.text(font, this.cachedName, x + 22, y + 5, 0xFFEAF1FA, false);
		if (this.draggedGuard.loaded()) {
			graphics.text(font, GuardTacticsScreen.levelText(this.draggedGuard.level()), x + 22, y + 15, 0xFFB6C6D6, false);
		}

		drawArmorIcon(graphics, this.draggedGuard.helmet(), x + 4, y + 25);
		drawArmorIcon(graphics, this.draggedGuard.chest(), x + 23, y + 25);
		drawArmorIcon(graphics, this.draggedGuard.legs(), x + 42, y + 25);
		drawArmorIcon(graphics, this.draggedGuard.boots(), x + 61, y + 25);

		if (targetLabel != null) {
			int labelWidth = font.width(targetLabel) + 6;
			int labelX = (int) this.dragX - labelWidth / 2;
			int labelY = y + CARD_HEIGHT + 3;
			graphics.fill(labelX, labelY, labelX + labelWidth, labelY + 12, 0xEE0E141B);
			graphics.outline(labelX, labelY, labelWidth, 12, accentColor);
			graphics.text(font, targetLabel, labelX + 3, labelY + 2, 0xFFEAF1FA, false);
		}
	}

	private static void drawArmorIcon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		if (stack == null || stack.isEmpty()) {
			graphics.outline(x, y, 16, 16, 0x884D5F72);
			return;
		}
		graphics.item(stack, x, y);
	}

	public record DropResult(ClientGuardRosterStore.GuardRosterEntry guard, int sourceGroupIndex, boolean fromUnassigned) {
	}
}
