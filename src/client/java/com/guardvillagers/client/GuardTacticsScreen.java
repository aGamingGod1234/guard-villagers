package com.guardvillagers.client;

import com.guardvillagers.tactics.GuardTacticsScreenHandler;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public final class GuardTacticsScreen extends HandledScreen<GuardTacticsScreenHandler> {
	private static final int PANEL_WIDTH = 340;
	private static final int PANEL_HEIGHT = 222;
	private static final int PANEL_PADDING = 11;
	private static final int HEADER_TOP = 7;
	private static final int MODE_HINT_TOP = 44;
	private static final int TOP_ROW_Y = 24;
	private static final int TOP_ROW_HEIGHT = 26;
	private static final int TOP_ROW_GAP = 4;
	private static final int MAIN_GRID_Y = 62;
	private static final int MAIN_CELL_WIDTH = 32;
	private static final int MAIN_CELL_HEIGHT = 30;
	private static final int MAIN_CELL_GAP = 3;
	private static final int CONTROL_ROW_Y = 166;
	private static final int CONTROL_ROW_HEIGHT = 24;
	private static final int FOOTER_Y = 197;
	private static final int[] TOP_SLOTS = {4, 5, 6, 7, 8};

	private int panelX;
	private int panelY;

	public GuardTacticsScreen(GuardTacticsScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.backgroundWidth = PANEL_WIDTH;
		this.backgroundHeight = PANEL_HEIGHT;
	}

	@Override
	protected void init() {
		super.init();
		this.panelX = (this.width - PANEL_WIDTH) / 2;
		this.panelY = (this.height - PANEL_HEIGHT) / 2;
		this.x = this.panelX;
		this.y = this.panelY;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		this.renderBackground(context, mouseX, mouseY, delta);
		this.drawBackground(context, delta, mouseX, mouseY);
		this.drawForeground(context, mouseX, mouseY);
		this.drawHoveredTooltip(context, mouseX, mouseY);
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		int panelRight = this.panelX + PANEL_WIDTH;
		int panelBottom = this.panelY + PANEL_HEIGHT;
		context.fill(this.panelX - 2, this.panelY - 2, panelRight + 2, panelBottom + 2, 0xEE0B141F);
		context.fill(this.panelX, this.panelY, panelRight, panelBottom, 0xF7172635);
		context.fill(this.panelX, this.panelY, panelRight, this.panelY + 18, 0xFF203649);
		context.fill(this.panelX, panelBottom - 30, panelRight, panelBottom, 0xFF1A2A3B);

		this.renderTopRow(context, mouseX, mouseY);
		this.renderMainGrid(context, mouseX, mouseY);
		this.renderControlRow(context, mouseX, mouseY);
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		boolean hierarchyMode = this.isHierarchyMode();
		context.drawText(this.textRenderer, this.title.getString(), this.panelX + PANEL_PADDING, this.panelY + HEADER_TOP, 0xFFF0F6FF, false);
		context.drawText(
			this.textRenderer,
			hierarchyMode ? "Hierarchy Director" : "Zone Planner",
			this.panelX + PANEL_PADDING,
			this.panelY + MODE_HINT_TOP,
			hierarchyMode ? 0xFFDDA366 : 0xFF73CBF3,
			false
		);
		context.drawText(
			this.textRenderer,
			hierarchyMode ? "Click guard card, then click destination row/slot." : "Left click: anchor/fill. Right click: clear chunk.",
			this.panelX + PANEL_PADDING + 120,
			this.panelY + MODE_HINT_TOP,
			0xFFD0DAE6,
			false
		);
		context.drawText(
			this.textRenderer,
			hierarchyMode ? "Row headers control role names and row assignment." : "Color cards define patrol zones for hierarchy columns.",
			this.panelX + PANEL_PADDING,
			this.panelY + FOOTER_Y,
			0xFF9DB1C6,
			false
		);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubleClick) {
		double mouseX = click.x();
		double mouseY = click.y();
		int button = click.button();
		if (button == 0 || button == 1) {
			int slotId = this.findSlotAt(mouseX, mouseY);
			if (slotId >= 0) {
				this.onMouseClick(this.handler.getSlot(slotId), slotId, button, SlotActionType.PICKUP);
				return true;
			}
			if (this.isMouseWithinPanel(mouseX, mouseY)) {
				return true;
			}
		}
		return super.mouseClicked(click, doubleClick);
	}

	@Override
	protected boolean isPointWithinBounds(int x, int y, int width, int height, double pointX, double pointY) {
		return false;
	}

	private void drawHoveredTooltip(DrawContext context, int mouseX, int mouseY) {
		int slotId = this.findSlotAt(mouseX, mouseY);
		if (slotId < 0) {
			return;
		}
		ItemStack stack = this.handler.getSlot(slotId).getStack();
		if (stack.isEmpty()) {
			return;
		}
		context.drawItemTooltip(this.textRenderer, stack, mouseX, mouseY);
	}

	private void renderTopRow(DrawContext context, int mouseX, int mouseY) {
		int totalWidth = this.mainGridWidth();
		int cellWidth = (totalWidth - (TOP_ROW_GAP * 4)) / 5;
		int left = this.panelX + PANEL_PADDING;
		int top = this.panelY + TOP_ROW_Y;
		for (int i = 0; i < TOP_SLOTS.length; i++) {
			int x = left + i * (cellWidth + TOP_ROW_GAP);
			this.drawSlotCard(context, TOP_SLOTS[i], x, top, cellWidth, TOP_ROW_HEIGHT, mouseX, mouseY);
		}
	}

	private void renderMainGrid(DrawContext context, int mouseX, int mouseY) {
		int left = this.panelX + PANEL_PADDING;
		int top = this.panelY + MAIN_GRID_Y;
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				int slotId = 18 + row * 9 + col;
				int x = left + col * (MAIN_CELL_WIDTH + MAIN_CELL_GAP);
				int y = top + row * (MAIN_CELL_HEIGHT + MAIN_CELL_GAP);
				this.drawSlotCard(context, slotId, x, y, MAIN_CELL_WIDTH, MAIN_CELL_HEIGHT, mouseX, mouseY);
			}
		}
	}

	private void renderControlRow(DrawContext context, int mouseX, int mouseY) {
		int left = this.panelX + PANEL_PADDING;
		int top = this.panelY + CONTROL_ROW_Y;
		for (int col = 0; col < 9; col++) {
			int slotId = 45 + col;
			int x = left + col * (MAIN_CELL_WIDTH + MAIN_CELL_GAP);
			this.drawSlotCard(context, slotId, x, top, MAIN_CELL_WIDTH, CONTROL_ROW_HEIGHT, mouseX, mouseY);
		}
	}

	private void drawSlotCard(DrawContext context, int slotId, int x, int y, int width, int height, int mouseX, int mouseY) {
		ItemStack stack = this.handler.getSlot(slotId).getStack();
		boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		int fillColor = this.backgroundColorForSlot(slotId, stack);
		if (hovered) {
			fillColor = brighten(fillColor, 28);
		}
		context.fill(x, y, x + width, y + height, fillColor);
		this.drawBorder(context, x, y, width, height, hovered ? 0xFF73CBF3 : 0xFF2A4054);

		if (!stack.isEmpty()) {
			int itemX = x + 4;
			int itemY = y + Math.max(2, (height - 16) / 2);
			context.drawItem(stack, itemX, itemY);
			if (this.shouldRenderLabel(slotId, stack)) {
				String label = this.trimLabel(stack.getName().getString(), width - 24);
				context.drawText(this.textRenderer, label, x + 22, y + Math.max(3, (height - 8) / 2), 0xFFE9F2FC, false);
			}
		}
	}

	private int findSlotAt(double mouseX, double mouseY) {
		int totalWidth = this.mainGridWidth();
		int topCellWidth = (totalWidth - (TOP_ROW_GAP * 4)) / 5;
		int left = this.panelX + PANEL_PADDING;
		int topRowY = this.panelY + TOP_ROW_Y;
		for (int i = 0; i < TOP_SLOTS.length; i++) {
			int x = left + i * (topCellWidth + TOP_ROW_GAP);
			if (contains(mouseX, mouseY, x, topRowY, topCellWidth, TOP_ROW_HEIGHT)) {
				return TOP_SLOTS[i];
			}
		}

		int mainY = this.panelY + MAIN_GRID_Y;
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				int x = left + col * (MAIN_CELL_WIDTH + MAIN_CELL_GAP);
				int y = mainY + row * (MAIN_CELL_HEIGHT + MAIN_CELL_GAP);
				if (contains(mouseX, mouseY, x, y, MAIN_CELL_WIDTH, MAIN_CELL_HEIGHT)) {
					return 18 + row * 9 + col;
				}
			}
		}

		int controlY = this.panelY + CONTROL_ROW_Y;
		for (int col = 0; col < 9; col++) {
			int x = left + col * (MAIN_CELL_WIDTH + MAIN_CELL_GAP);
			if (contains(mouseX, mouseY, x, controlY, MAIN_CELL_WIDTH, CONTROL_ROW_HEIGHT)) {
				return 45 + col;
			}
		}
		return -1;
	}

	private boolean isMouseWithinPanel(double mouseX, double mouseY) {
		return contains(mouseX, mouseY, this.panelX, this.panelY, PANEL_WIDTH, PANEL_HEIGHT);
	}

	private int mainGridWidth() {
		return (MAIN_CELL_WIDTH * 9) + (MAIN_CELL_GAP * 8);
	}

	private boolean isHierarchyMode() {
		ItemStack stack = this.handler.getSlot(50).getStack();
		return !stack.isEmpty() && stack.isOf(Items.COMPASS);
	}

	private boolean shouldRenderLabel(int slotId, ItemStack stack) {
		if (slotId >= 45 || slotId <= 8) {
			return true;
		}
		return stack.isOf(Items.BOOK) || stack.isOf(Items.PLAYER_HEAD);
	}

	private String trimLabel(String raw, int maxWidth) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		if (this.textRenderer.getWidth(raw) <= maxWidth) {
			return raw;
		}
		String ellipsis = "...";
		int end = raw.length();
		while (end > 1 && this.textRenderer.getWidth(raw.substring(0, end) + ellipsis) > maxWidth) {
			end--;
		}
		return end <= 1 ? ellipsis : raw.substring(0, end) + ellipsis;
	}

	private int backgroundColorForSlot(int slotId, ItemStack stack) {
		if (stack.isOf(Items.GREEN_STAINED_GLASS_PANE)) {
			return 0xCC1F4F2A;
		}
		if (stack.isOf(Items.YELLOW_STAINED_GLASS_PANE)) {
			return 0xCC5A4A15;
		}
		if (stack.isOf(Items.RED_STAINED_GLASS_PANE)) {
			return 0xCC5D1D20;
		}
		if (stack.isOf(Items.BLUE_STAINED_GLASS_PANE) || stack.isOf(Items.LIGHT_BLUE_STAINED_GLASS_PANE)) {
			return 0xCC1C3F5B;
		}
		if (stack.isOf(Items.PLAYER_HEAD)) {
			return 0xCC4B3A6C;
		}
		if (stack.isOf(Items.BOOK) || stack.isOf(Items.WRITABLE_BOOK) || stack.isOf(Items.NAME_TAG)) {
			return 0xCC4A4331;
		}
		if (stack.isOf(Items.COMPASS) || stack.isOf(Items.RECOVERY_COMPASS)) {
			return 0xCC214953;
		}
		if (slotId >= 18 && slotId <= 44) {
			return 0xCC1F2D3C;
		}
		return 0xCC263342;
	}

	private static int brighten(int argb, int amount) {
		int alpha = (argb >> 24) & 0xFF;
		int red = Math.min(255, ((argb >> 16) & 0xFF) + amount);
		int green = Math.min(255, ((argb >> 8) & 0xFF) + amount);
		int blue = Math.min(255, (argb & 0xFF) + amount);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
		context.fill(x, y, x + width, y + 1, color);
		context.fill(x, y + height - 1, x + width, y + height, color);
		context.fill(x, y, x + 1, y + height, color);
		context.fill(x + width - 1, y, x + width, y + height, color);
	}

	private static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}
}
