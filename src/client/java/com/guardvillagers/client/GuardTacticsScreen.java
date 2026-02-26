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

import java.util.HashMap;
import java.util.Map;

public final class GuardTacticsScreen extends HandledScreen<GuardTacticsScreenHandler> {
	private static final int SLOT_MODE = 50;
	private static final int SLOT_INFO = 52;
	private static final int SLOT_TITLE = 4;

	private static final int TOP_INSET = 16;
	private static final int BOTTOM_INSET = 14;
	private static final int LEFT_INSET = 14;
	private static final int RIGHT_INSET = 14;
	private static final int HEADER_HEIGHT = 56;
	private static final int FOOTER_HEIGHT = 34;
	private static final int SIDEBAR_WIDTH = 172;

	private static final int PANEL_BG = 0xEC101B2A;
	private static final int PANEL_ELEVATION = 0xEE1A2A3E;
	private static final int PANEL_ACCENT = 0xFF355C7A;
	private static final int PANEL_ACCENT_2 = 0xFF2A4258;
	private static final int PANEL_TEXT = 0xFFE9F2FF;
	private static final int PANEL_SUBTEXT = 0xFFB9CCE0;

	private final Map<Integer, HitArea> slotHitAreas = new HashMap<>();

	private int rootX;
	private int rootY;
	private int rootW;
	private int rootH;

	private int headerX;
	private int headerY;
	private int headerW;

	private int sidebarX;
	private int sidebarY;
	private int sidebarW;
	private int sidebarH;

	private int canvasX;
	private int canvasY;
	private int canvasW;
	private int canvasH;

	public GuardTacticsScreen(GuardTacticsScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
	}

	@Override
	protected void init() {
		super.init();
		this.backgroundWidth = this.width;
		this.backgroundHeight = this.height;
		this.x = 0;
		this.y = 0;
		this.recomputeLayout();
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
		this.recomputeLayout();
		this.slotHitAreas.clear();

		this.drawBackdrop(context);
		this.drawHeader(context);
		this.drawSidebar(context, mouseX, mouseY);
		this.drawFooter(context, mouseX, mouseY);

		if (this.isHierarchyMode()) {
			this.drawHierarchyCanvas(context, mouseX, mouseY);
		} else {
			this.drawZonesCanvas(context, mouseX, mouseY);
		}
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		boolean hierarchyMode = this.isHierarchyMode();
		context.drawText(this.textRenderer, this.title.getString(), this.headerX + 12, this.headerY + 10, PANEL_TEXT, false);
		context.drawText(
			this.textRenderer,
			hierarchyMode ? "Hierarchy Command Surface" : "Tactical Zone Map",
			this.headerX + 12,
			this.headerY + 27,
			hierarchyMode ? 0xFFFFB57D : 0xFF8EDAF8,
			false
		);
		context.drawText(
			this.textRenderer,
			hierarchyMode
				? "Move guards through role lanes and zone assignments."
				: "Paint operational zones with custom color controls.",
			this.headerX + 12,
			this.headerY + 42,
			PANEL_SUBTEXT,
			false
		);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubleClick) {
		int button = click.button();
		if (button != 0 && button != 1) {
			return super.mouseClicked(click, doubleClick);
		}

		int slotId = this.findSlotAt(click.x(), click.y());
		if (slotId >= 0) {
			this.onMouseClick(this.handler.getSlot(slotId), slotId, button, SlotActionType.PICKUP);
			return true;
		}

		if (this.contains(this.rootX, this.rootY, this.rootW, this.rootH, click.x(), click.y())) {
			return true;
		}
		return super.mouseClicked(click, doubleClick);
	}

	@Override
	protected boolean isPointWithinBounds(int x, int y, int width, int height, double pointX, double pointY) {
		return false;
	}

	private void drawBackdrop(DrawContext context) {
		context.fill(0, 0, this.width, this.height, 0xF107111A);
		context.fill(this.rootX - 2, this.rootY - 2, this.rootX + this.rootW + 2, this.rootY + this.rootH + 2, 0xEE050B12);
		context.fill(this.rootX, this.rootY, this.rootX + this.rootW, this.rootY + this.rootH, PANEL_BG);
	}

	private void drawHeader(DrawContext context) {
		context.fill(this.headerX, this.headerY, this.headerX + this.headerW, this.headerY + HEADER_HEIGHT, PANEL_ELEVATION);
		context.fill(this.headerX, this.headerY + HEADER_HEIGHT - 2, this.headerX + this.headerW, this.headerY + HEADER_HEIGHT, PANEL_ACCENT);
	}

	private void drawSidebar(DrawContext context, int mouseX, int mouseY) {
		context.fill(this.sidebarX, this.sidebarY, this.sidebarX + this.sidebarW, this.sidebarY + this.sidebarH, PANEL_ELEVATION);
		context.fill(this.sidebarX + this.sidebarW - 2, this.sidebarY, this.sidebarX + this.sidebarW, this.sidebarY + this.sidebarH, PANEL_ACCENT_2);

		int titleY = this.sidebarY + 12;
		context.drawText(this.textRenderer, "Controls", this.sidebarX + 10, titleY, PANEL_TEXT, false);

		int modeButtonY = titleY + 16;
		this.drawControlButton(context, SLOT_MODE, this.sidebarX + 10, modeButtonY, this.sidebarW - 20, 24, mouseX, mouseY, true);

		if (!this.isHierarchyMode()) {
			int swatchY = modeButtonY + 35;
			context.drawText(this.textRenderer, "Paint Colors", this.sidebarX + 10, swatchY, PANEL_SUBTEXT, false);
			int swatchX = this.sidebarX + 10;
			int swatchW = this.sidebarW - 20;
			int swatchH = 22;
			this.drawControlButton(context, 5, swatchX, swatchY + 12, swatchW, swatchH, mouseX, mouseY, true);
			this.drawControlButton(context, 6, swatchX, swatchY + 38, swatchW, swatchH, mouseX, mouseY, true);
			this.drawControlButton(context, 7, swatchX, swatchY + 64, swatchW, swatchH, mouseX, mouseY, true);
			this.drawControlButton(context, 8, swatchX, swatchY + 90, swatchW, swatchH, mouseX, mouseY, true);
			this.drawControlButton(context, SLOT_TITLE, swatchX, swatchY + 120, swatchW, 36, mouseX, mouseY, false);
		} else {
			int legendY = modeButtonY + 36;
			context.drawText(this.textRenderer, "Role Lanes", this.sidebarX + 10, legendY, PANEL_SUBTEXT, false);
			this.drawControlButton(context, SLOT_TITLE, this.sidebarX + 10, legendY + 12, this.sidebarW - 20, 36, mouseX, mouseY, false);
			this.drawControlButton(context, 5, this.sidebarX + 10, legendY + 54, this.sidebarW - 20, 22, mouseX, mouseY, false);
			this.drawControlButton(context, 6, this.sidebarX + 10, legendY + 80, this.sidebarW - 20, 22, mouseX, mouseY, false);
			this.drawControlButton(context, 7, this.sidebarX + 10, legendY + 106, this.sidebarW - 20, 22, mouseX, mouseY, false);
			this.drawControlButton(context, 8, this.sidebarX + 10, legendY + 132, this.sidebarW - 20, 22, mouseX, mouseY, false);
		}
	}

	private void drawFooter(DrawContext context, int mouseX, int mouseY) {
		int footerY = this.rootY + this.rootH - FOOTER_HEIGHT;
		context.fill(this.rootX, footerY, this.rootX + this.rootW, this.rootY + this.rootH, PANEL_ELEVATION);
		context.fill(this.rootX, footerY, this.rootX + this.rootW, footerY + 1, PANEL_ACCENT_2);

		int buttonY = footerY + 6;
		int buttonW = 86;
		int buttonH = 22;
		int gap = 8;
		int startX = this.canvasX + 6;

		this.drawControlButton(context, 45, startX, buttonY, buttonW, buttonH, mouseX, mouseY, true);
		this.drawControlButton(context, 53, startX + (buttonW + gap), buttonY, buttonW, buttonH, mouseX, mouseY, true);
		this.drawControlButton(context, 47, startX + (buttonW + gap) * 2, buttonY, buttonW, buttonH, mouseX, mouseY, true);
		this.drawControlButton(context, 46, startX + (buttonW + gap) * 3, buttonY, buttonW, buttonH, mouseX, mouseY, true);
		this.drawControlButton(context, 49, startX + (buttonW + gap) * 4, buttonY, buttonW, buttonH, mouseX, mouseY, true);
		this.drawControlButton(context, 48, startX + (buttonW + gap) * 5, buttonY, buttonW, buttonH, mouseX, mouseY, true);

		int rightX = this.rootX + this.rootW - (buttonW * 2 + gap + 12);
		this.drawControlButton(context, 51, rightX, buttonY, buttonW, buttonH, mouseX, mouseY, true);
		this.drawControlButton(context, SLOT_INFO, rightX + buttonW + gap, buttonY, buttonW, buttonH, mouseX, mouseY, true);
	}

	private void drawZonesCanvas(DrawContext context, int mouseX, int mouseY) {
		context.fill(this.canvasX, this.canvasY, this.canvasX + this.canvasW, this.canvasY + this.canvasH, 0xA51A2E44);
		context.fill(this.canvasX + 1, this.canvasY + 1, this.canvasX + this.canvasW - 1, this.canvasY + this.canvasH - 1, 0x8021364D);
		this.drawSoftMapRidges(context);

		int bubbleW = 86;
		int bubbleH = 44;
		for (int slot = 18; slot <= 44; slot++) {
			int idx = slot - 18;
			int row = idx / 9;
			int col = idx % 9;

			double nx = (col + 0.5D) / 9.0D;
			double ny = (row + 0.5D) / 3.0D;
			int offsetX = (int) Math.round(Math.sin((col * 0.85D) + (row * 0.45D)) * 12.0D);
			int offsetY = (int) Math.round(Math.cos((col * 0.67D) + (row * 0.78D)) * 9.0D);
			int x = this.canvasX + 18 + (int) Math.round((this.canvasW - bubbleW - 36) * nx) + offsetX;
			int y = this.canvasY + 18 + (int) Math.round((this.canvasH - bubbleH - 36) * ny) + offsetY;

			this.drawMapBubble(context, slot, x, y, bubbleW, bubbleH, mouseX, mouseY);
		}
	}

	private void drawHierarchyCanvas(DrawContext context, int mouseX, int mouseY) {
		context.fill(this.canvasX, this.canvasY, this.canvasX + this.canvasW, this.canvasY + this.canvasH, 0x9E241F35);
		context.fill(this.canvasX + 1, this.canvasY + 1, this.canvasX + this.canvasW - 1, this.canvasY + this.canvasH - 1, 0x7E2F2B45);

		int laneHeight = (this.canvasH - 46) / 3;
		for (int lane = 0; lane < 3; lane++) {
			int y = this.canvasY + 14 + lane * laneHeight;
			int laneColor = lane % 2 == 0 ? 0x663E3556 : 0x66493562;
			context.fill(this.canvasX + 10, y, this.canvasX + this.canvasW - 10, y + laneHeight - 8, laneColor);

			int baseSlot = 18 + lane * 9;
			this.drawHierarchyRow(context, baseSlot, y + 8, laneHeight - 24, mouseX, mouseY);
		}
	}

	private void drawHierarchyRow(DrawContext context, int baseSlot, int rowY, int rowHeight, int mouseX, int mouseY) {
		int headerX = this.canvasX + 18;
		int headerW = Math.min(300, this.canvasW / 3);
		int headerH = Math.min(34, rowHeight);
		this.drawControlButton(context, baseSlot, headerX, rowY, headerW, headerH, mouseX, mouseY, true);

		int zoneY = rowY + headerH + 6;
		int zoneW = 88;
		int zoneH = 24;
		this.drawControlButton(context, baseSlot + 1, headerX, zoneY, zoneW, zoneH, mouseX, mouseY, true);
		this.drawControlButton(context, baseSlot + 2, headerX + zoneW + 6, zoneY, zoneW, zoneH, mouseX, mouseY, true);
		this.drawControlButton(context, baseSlot + 3, headerX + (zoneW + 6) * 2, zoneY, zoneW, zoneH, mouseX, mouseY, true);

		int guardStartX = headerX + headerW + 18;
		int guardAreaW = this.canvasX + this.canvasW - 20 - guardStartX;
		int guardW = Math.max(88, Math.min(132, (guardAreaW - 24) / 5));
		int guardH = Math.min(44, rowHeight + 4);
		int spacing = Math.max(6, (guardAreaW - (guardW * 5)) / 4);
		for (int i = 0; i < 5; i++) {
			int x = guardStartX + i * (guardW + spacing);
			int y = rowY + ((i % 2 == 0) ? 0 : 6);
			this.drawControlButton(context, baseSlot + 4 + i, x, y, guardW, guardH, mouseX, mouseY, true);
		}
	}

	private void drawMapBubble(DrawContext context, int slotId, int x, int y, int w, int h, int mouseX, int mouseY) {
		ItemStack stack = this.handler.getSlot(slotId).getStack();
		boolean hovered = this.contains(x, y, w, h, mouseX, mouseY);
		int color = this.slotBaseColor(stack, slotId);
		int border = hovered ? 0xFFD8F0FF : 0xFF365573;

		context.fill(x, y, x + w, y + h, color);
		this.drawOutline(context, x, y, w, h, border);

		if (!stack.isEmpty()) {
			context.drawItem(stack, x + 5, y + 6);
			String label = this.fitText(stack.getName().getString(), w - 28);
			context.drawText(this.textRenderer, label, x + 24, y + 7, PANEL_TEXT, false);
		}

		this.slotHitAreas.put(slotId, new HitArea(x, y, w, h));
	}

	private void drawControlButton(
		DrawContext context,
		int slotId,
		int x,
		int y,
		int w,
		int h,
		int mouseX,
		int mouseY,
		boolean clickable
	) {
		ItemStack stack = this.handler.getSlot(slotId).getStack();
		boolean hovered = this.contains(x, y, w, h, mouseX, mouseY);
		int color = this.slotBaseColor(stack, slotId);
		if (hovered && clickable) {
			color = this.brighten(color, 22);
		}
		context.fill(x, y, x + w, y + h, color);
		this.drawOutline(context, x, y, w, h, hovered ? 0xFF7CCBEE : 0xFF31516A);

		if (!stack.isEmpty()) {
			context.drawItem(stack, x + 4, y + Math.max(2, (h - 16) / 2));
			String label = this.fitText(stack.getName().getString(), w - 24);
			context.drawText(this.textRenderer, label, x + 22, y + Math.max(3, (h - 8) / 2), PANEL_TEXT, false);
		}

		if (clickable) {
			this.slotHitAreas.put(slotId, new HitArea(x, y, w, h));
		}
	}

	private void drawSoftMapRidges(DrawContext context) {
		int ridgeCount = 8;
		for (int i = 0; i < ridgeCount; i++) {
			int y = this.canvasY + 18 + i * ((this.canvasH - 36) / ridgeCount);
			int wave = (i % 2 == 0) ? 14 : 22;
			context.fill(this.canvasX + 18 + wave, y, this.canvasX + this.canvasW - 18 - wave, y + 1, 0x335D85AA);
		}
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

	private int findSlotAt(double mouseX, double mouseY) {
		for (Map.Entry<Integer, HitArea> entry : this.slotHitAreas.entrySet()) {
			HitArea area = entry.getValue();
			if (this.contains(area.x, area.y, area.w, area.h, mouseX, mouseY)) {
				return entry.getKey();
			}
		}
		return -1;
	}

	private boolean isHierarchyMode() {
		ItemStack modeStack = this.handler.getSlot(SLOT_MODE).getStack();
		return !modeStack.isEmpty() && modeStack.isOf(Items.COMPASS);
	}

	private void recomputeLayout() {
		this.rootX = LEFT_INSET;
		this.rootY = TOP_INSET;
		this.rootW = Math.max(420, this.width - LEFT_INSET - RIGHT_INSET);
		this.rootH = Math.max(280, this.height - TOP_INSET - BOTTOM_INSET);

		this.headerX = this.rootX + 2;
		this.headerY = this.rootY + 2;
		this.headerW = this.rootW - 4;

		this.sidebarX = this.rootX + 8;
		this.sidebarY = this.headerY + HEADER_HEIGHT + 8;
		this.sidebarW = SIDEBAR_WIDTH;
		this.sidebarH = this.rootH - HEADER_HEIGHT - FOOTER_HEIGHT - 20;

		this.canvasX = this.sidebarX + this.sidebarW + 10;
		this.canvasY = this.sidebarY;
		this.canvasW = this.rootX + this.rootW - this.canvasX - 8;
		this.canvasH = this.sidebarH;
	}

	private int slotBaseColor(ItemStack stack, int slotId) {
		if (stack.isOf(Items.GREEN_STAINED_GLASS_PANE)) {
			return 0xCE215A34;
		}
		if (stack.isOf(Items.YELLOW_STAINED_GLASS_PANE)) {
			return 0xCE726022;
		}
		if (stack.isOf(Items.RED_STAINED_GLASS_PANE)) {
			return 0xCE742833;
		}
		if (stack.isOf(Items.BLUE_STAINED_GLASS_PANE) || stack.isOf(Items.LIGHT_BLUE_STAINED_GLASS_PANE)) {
			return 0xCE2D4D73;
		}
		if (stack.isOf(Items.PLAYER_HEAD)) {
			return 0xCE5A4474;
		}
		if (stack.isOf(Items.BOOK) || stack.isOf(Items.WRITABLE_BOOK) || stack.isOf(Items.NAME_TAG)) {
			return 0xCE564A32;
		}
		if (stack.isOf(Items.COMPASS) || stack.isOf(Items.RECOVERY_COMPASS)) {
			return 0xCE2B586E;
		}
		if (stack.isOf(Items.GRAY_STAINED_GLASS_PANE) || stack.isOf(Items.BLACK_STAINED_GLASS_PANE)) {
			return 0xB22A3340;
		}
		if (slotId >= 18 && slotId <= 44) {
			return 0xCA23354A;
		}
		return 0xC6324050;
	}

	private void drawOutline(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}

	private String fitText(String text, int maxWidth) {
		if (text == null || text.isBlank()) {
			return "";
		}
		if (this.textRenderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String suffix = "...";
		int end = text.length();
		while (end > 1 && this.textRenderer.getWidth(text.substring(0, end) + suffix) > maxWidth) {
			end--;
		}
		return end <= 1 ? suffix : text.substring(0, end) + suffix;
	}

	private boolean contains(int x, int y, int w, int h, double px, double py) {
		return px >= x && px < x + w && py >= y && py < y + h;
	}

	private int brighten(int argb, int amount) {
		int a = (argb >> 24) & 0xFF;
		int r = Math.min(255, ((argb >> 16) & 0xFF) + amount);
		int g = Math.min(255, ((argb >> 8) & 0xFF) + amount);
		int b = Math.min(255, (argb & 0xFF) + amount);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private record HitArea(int x, int y, int w, int h) {
	}
}
