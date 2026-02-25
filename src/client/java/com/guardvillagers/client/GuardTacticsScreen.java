package com.guardvillagers.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

public final class GuardTacticsScreen extends GenericContainerScreen {
	private static final int HELP_LEFT = 6;
	private static final int HELP_TOP = 20;
	private static final int HELP_HEIGHT = 40;

	public GuardTacticsScreen(GenericContainerScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		super.drawForeground(context, mouseX, mouseY);

		boolean hierarchyMode = this.isHierarchyMode();
		int panelRight = this.backgroundWidth - HELP_LEFT;
		context.fill(HELP_LEFT, HELP_TOP, panelRight, HELP_TOP + HELP_HEIGHT, 0x66000000);

		context.drawText(this.textRenderer, hierarchyMode ? "Hierarchy Mode" : "Zone Mode", HELP_LEFT + 4, HELP_TOP + 4, hierarchyMode ? 0xFFD46A : 0x68D8FF, false);
		if (hierarchyMode) {
			context.drawText(this.textRenderer, "Click guard then target row/slot to move it", HELP_LEFT + 4, HELP_TOP + 15, 0xE0E0E0, false);
			context.drawText(this.textRenderer, "Right-click row header to cycle role name", HELP_LEFT + 4, HELP_TOP + 24, 0xE0E0E0, false);
			context.drawText(this.textRenderer, "Custom names: /guards hierarchy rename <row> <name>", HELP_LEFT + 4, HELP_TOP + 33, 0xBDBDBD, false);
		} else {
			context.drawText(this.textRenderer, "Left-click map: anchor/fill region", HELP_LEFT + 4, HELP_TOP + 15, 0xE0E0E0, false);
			context.drawText(this.textRenderer, "Right-click map: clear one chunk", HELP_LEFT + 4, HELP_TOP + 24, 0xE0E0E0, false);
			context.drawText(this.textRenderer, "Use color cards and pan/zoom controls below", HELP_LEFT + 4, HELP_TOP + 33, 0xBDBDBD, false);
		}
	}

	private boolean isHierarchyMode() {
		if (this.handler == null) {
			return false;
		}
		Slot modeSlot = this.handler.getSlot(50);
		if (modeSlot == null) {
			return false;
		}
		ItemStack stack = modeSlot.getStack();
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return stack.isOf(Items.COMPASS);
	}
}
