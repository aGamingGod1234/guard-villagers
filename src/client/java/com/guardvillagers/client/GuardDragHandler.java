package com.guardvillagers.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class GuardDragHandler {
	private static final ItemStack GUARD_HEAD_ICON = new ItemStack(Items.PLAYER_HEAD);

	private ClientGuardRosterStore.GuardRosterEntry draggedGuard;
	private int sourceGroupIndex = -1;
	private boolean fromUnassigned;
	private double dragX;
	private double dragY;
	private boolean active;

	public void beginDrag(ClientGuardRosterStore.GuardRosterEntry guard, int sourceGroup, boolean unassigned, double mouseX, double mouseY) {
		this.draggedGuard = guard;
		this.sourceGroupIndex = sourceGroup;
		this.fromUnassigned = unassigned;
		this.dragX = mouseX;
		this.dragY = mouseY;
		this.active = true;
	}

	public void updateDrag(double mouseX, double mouseY) {
		this.dragX = mouseX;
		this.dragY = mouseY;
	}

	public boolean isActive() {
		return this.active;
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

	public void render(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font textRenderer) {
		if (!this.active || this.draggedGuard == null) {
			return;
		}

		int cardW = 82;
		int cardH = 48;
		int x = (int) this.dragX - cardW / 2;
		int y = (int) this.dragY - cardH / 2;

		graphics.pose().pushMatrix();
		graphics.pose().translate((float) this.dragX, (float) this.dragY);
		graphics.pose().scale(1.1F, 1.1F);
		graphics.pose().translate((float) -this.dragX, (float) -this.dragY);

		graphics.fill(x, y, x + cardW, y + cardH, 0xEE1E2A38);
		drawBorder(graphics, x, y, cardW, cardH, 0xFF6A8FBF);

		graphics.item(GUARD_HEAD_ICON, x + 4, y + 4);
		String name = this.draggedGuard.displayName();
		if (name.length() > 10) {
			name = name.substring(0, 9) + "...";
		}
		graphics.text(textRenderer, Component.literal(name), x + 22, y + 4, 0xFFEAF1FA, false);
		graphics.text(textRenderer, Component.literal("Lv " + this.draggedGuard.level()), x + 22, y + 14, 0xFFB6C6D6, false);

		drawArmorIcon(graphics, this.draggedGuard.helmet(), x + 4, y + 26);
		drawArmorIcon(graphics, this.draggedGuard.chest(), x + 22, y + 26);
		drawArmorIcon(graphics, this.draggedGuard.legs(), x + 40, y + 26);
		drawArmorIcon(graphics, this.draggedGuard.boots(), x + 58, y + 26);

		graphics.pose().popMatrix();
	}

	private static void drawArmorIcon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		if (stack == null || stack.isEmpty()) {
			drawBorder(graphics, x, y, 16, 16, 0x884D5F72);
			return;
		}
		graphics.item(stack, x, y);
	}

	private static void drawBorder(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
		graphics.fill(x, y, x + w, y + 1, color);
		graphics.fill(x, y + h - 1, x + w, y + h, color);
		graphics.fill(x, y, x + 1, y + h, color);
		graphics.fill(x + w - 1, y, x + w, y + h, color);
	}

	public record DropResult(ClientGuardRosterStore.GuardRosterEntry guard, int sourceGroupIndex, boolean fromUnassigned) {
	}
}
