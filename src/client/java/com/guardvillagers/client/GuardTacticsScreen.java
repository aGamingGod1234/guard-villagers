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
	private static final int SLOT_TITLE = 4;
	private static final int SLOT_MODE = 50;
	private static final int SLOT_INFO = 52;
	private static final int[] COLOR_SLOTS = {5, 6, 7, 8};
	private static final int[] RAIL_SLOTS = {45, 53, 47, 46, 49, 48, 50, 51, 52};

	private static final int BG_DARK = 0xF10B111A;
	private static final int MAP_TINT = 0xCC1A2B3C;
	private static final int MAP_BORDER = 0xFF2F4F67;
	private static final int HUD_BG = 0xC61A2A3D;
	private static final int HUD_BORDER = 0xFF4C6B83;
	private static final int TEXT_MAIN = 0xFFEAF4FF;
	private static final int TEXT_SUB = 0xFFC0D3E6;

	private final Map<Integer, HitArea> slotHitAreas = new HashMap<>();

	private int mapX;
	private int mapY;
	private int mapW;
	private int mapH;
	private int railX;
	private int railY;
	private int railSlotSize;
	private int railGap;
	private int paletteX;
	private int paletteY;
	private int paletteSize;
	private int paletteGap;

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
		this.computeLayout();
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
		this.computeLayout();
		this.slotHitAreas.clear();
		this.drawMapBackdrop(context);
		this.drawTopHud(context, mouseX, mouseY);
		this.drawPalette(context, mouseX, mouseY);
		this.drawRightRail(context, mouseX, mouseY);
		if (this.isHierarchyMode()) {
			this.drawHierarchyLayer(context, mouseX, mouseY);
		} else {
			this.drawZonesLayer(context, mouseX, mouseY);
		}
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		boolean hierarchy = this.isHierarchyMode();
		context.drawText(this.textRenderer, this.title.getString(), this.mapX + 12, this.mapY + 9, TEXT_MAIN, false);
		context.drawText(
			this.textRenderer,
			hierarchy ? "Hierarchy Tactical View" : "Zone Tactical View",
			this.mapX + 12,
			this.mapY + 22,
			hierarchy ? 0xFFFFC085 : 0xFF8FDFFF,
			false
		);
		context.drawText(
			this.textRenderer,
			hierarchy
				? "Move guards between lanes and role slots. Use side controls for quick actions."
				: "Paint and clear zone tiles with color chips and side controls.",
			this.mapX + 12,
			this.mapY + this.mapH - 14,
			TEXT_SUB,
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
		if (this.contains(this.mapX, this.mapY, this.mapW, this.mapH, click.x(), click.y())) {
			return true;
		}
		return super.mouseClicked(click, doubleClick);
	}

	@Override
	protected boolean isPointWithinBounds(int x, int y, int width, int height, double pointX, double pointY) {
		return false;
	}

	private void drawMapBackdrop(DrawContext context) {
		context.fill(0, 0, this.width, this.height, BG_DARK);
		context.fill(this.mapX - 1, this.mapY - 1, this.mapX + this.mapW + 1, this.mapY + this.mapH + 1, 0xF2050A12);
		context.fill(this.mapX, this.mapY, this.mapX + this.mapW, this.mapY + this.mapH, MAP_TINT);
		this.drawOutline(context, this.mapX, this.mapY, this.mapW, this.mapH, MAP_BORDER);

		int bands = 16;
		for (int i = 0; i < bands; i++) {
			int y0 = this.mapY + (i * this.mapH) / bands;
			int y1 = this.mapY + ((i + 1) * this.mapH) / bands;
			int tint = (i % 2 == 0) ? 0x122E4A3B : 0x122A3D4F;
			context.fill(this.mapX + 1, y0, this.mapX + this.mapW - 1, y1, tint);
		}

		for (int i = 0; i < 9; i++) {
			int ridgeY = this.mapY + 30 + i * Math.max(18, (this.mapH - 80) / 8);
			int inset = (i % 2 == 0) ? 22 : 34;
			context.fill(this.mapX + inset, ridgeY, this.mapX + this.mapW - inset, ridgeY + 1, 0x2A5E89A8);
		}
	}

	private void drawTopHud(DrawContext context, int mouseX, int mouseY) {
		int cardW = Math.min(250, this.mapW / 3);
		int cardH = 28;
		int cardX = this.mapX + 8;
		int cardY = this.mapY + 8;
		this.drawSlotCard(context, SLOT_TITLE, cardX, cardY, cardW, cardH, mouseX, mouseY, false, true);
	}

	private void drawPalette(DrawContext context, int mouseX, int mouseY) {
		for (int i = 0; i < COLOR_SLOTS.length; i++) {
			int slotId = COLOR_SLOTS[i];
			int x = this.paletteX - i * (this.paletteSize + this.paletteGap);
			int y = this.paletteY;
			this.drawSlotCard(context, slotId, x, y, this.paletteSize, this.paletteSize, mouseX, mouseY, true, false);
		}
	}

	private void drawRightRail(DrawContext context, int mouseX, int mouseY) {
		int railW = this.railSlotSize + 10;
		int railH = this.railSlotSize * RAIL_SLOTS.length + this.railGap * (RAIL_SLOTS.length - 1) + 10;
		context.fill(this.railX - 5, this.railY - 5, this.railX - 5 + railW, this.railY - 5 + railH, HUD_BG);
		this.drawOutline(context, this.railX - 5, this.railY - 5, railW, railH, HUD_BORDER);

		for (int i = 0; i < RAIL_SLOTS.length; i++) {
			int slotId = RAIL_SLOTS[i];
			int x = this.railX;
			int y = this.railY + i * (this.railSlotSize + this.railGap);
			this.drawSlotCard(context, slotId, x, y, this.railSlotSize, this.railSlotSize, mouseX, mouseY, true, false);
		}
	}

	private void drawZonesLayer(DrawContext context, int mouseX, int mouseY) {
		double baseX = this.mapX + 26;
		double baseY = this.mapY + 52;
		double stepX = (this.mapW - 110.0D) / 8.0D;
		double stepY = (this.mapH - 130.0D) / 2.0D;
		int tileW = Math.max(44, Math.min(64, (int) Math.floor(stepX - 7.0D)));
		int tileH = Math.max(24, Math.min(34, (int) Math.floor(stepY - 12.0D)));

		for (int slot = 18; slot <= 44; slot++) {
			int idx = slot - 18;
			int row = idx / 9;
			int col = idx % 9;
			double jitterX = Math.sin((col * 0.9D) + (row * 0.6D)) * 5.5D;
			double jitterY = Math.cos((col * 0.8D) + (row * 1.1D)) * 4.0D;
			double stagger = (row == 1) ? stepX * 0.34D : 0.0D;
			int x = (int) Math.round(baseX + col * stepX + stagger + jitterX);
			int y = (int) Math.round(baseY + row * stepY + jitterY);
			this.drawSlotCard(context, slot, x, y, tileW, tileH, mouseX, mouseY, true, true);
		}
	}

	private void drawHierarchyLayer(DrawContext context, int mouseX, int mouseY) {
		int laneGap = (this.mapH - 140) / 3;
		int laneStartY = this.mapY + 56;
		int laneHeight = Math.max(38, laneGap);

		for (int lane = 0; lane < 3; lane++) {
			int y = laneStartY + lane * (laneHeight + 8);
			int bandY = y + 8;
			int bandH = Math.max(28, laneHeight - 10);
			int bandX = this.mapX + 18;
			int bandW = this.mapW - 88;
			int bandColor = lane % 2 == 0 ? 0x3D4D3B66 : 0x3D52386C;
			context.fill(bandX, bandY, bandX + bandW, bandY + bandH, bandColor);

			int baseSlot = 18 + lane * 9;
			int headerW = Math.min(230, this.mapW / 4);
			this.drawSlotCard(context, baseSlot, bandX + 8, y, headerW, 24, mouseX, mouseY, true, true);

			int zoneY = y + 27;
			int zoneSize = 20;
			this.drawSlotCard(context, baseSlot + 1, bandX + 8, zoneY, zoneSize, zoneSize, mouseX, mouseY, true, false);
			this.drawSlotCard(context, baseSlot + 2, bandX + 8 + zoneSize + 6, zoneY, zoneSize, zoneSize, mouseX, mouseY, true, false);
			this.drawSlotCard(context, baseSlot + 3, bandX + 8 + (zoneSize + 6) * 2, zoneY, zoneSize, zoneSize, mouseX, mouseY, true, false);

			int guardStartX = bandX + headerW + 44;
			int guardGap = Math.max(6, (bandW - headerW - 80 - (5 * 70)) / 4);
			int guardW = 70;
			int guardH = 32;
			for (int i = 0; i < 5; i++) {
				int gx = guardStartX + i * (guardW + guardGap);
				int gy = y + ((i % 2 == 0) ? 2 : 8);
				this.drawSlotCard(context, baseSlot + 4 + i, gx, gy, guardW, guardH, mouseX, mouseY, true, true);
			}
		}
	}

	private void drawSlotCard(
		DrawContext context,
		int slotId,
		int x,
		int y,
		int w,
		int h,
		int mouseX,
		int mouseY,
		boolean clickable,
		boolean showLabel
	) {
		ItemStack stack = this.handler.getSlot(slotId).getStack();
		boolean hovered = this.contains(x, y, w, h, mouseX, mouseY);
		int fill = this.slotColor(slotId, stack);
		if (hovered && clickable) {
			fill = this.brighten(fill, 22);
		}
		context.fill(x, y, x + w, y + h, fill);
		this.drawOutline(context, x, y, w, h, hovered ? 0xFFCBE8FB : 0xFF395A74);

		if (!stack.isEmpty()) {
			int itemX = x + 4;
			int itemY = y + Math.max(2, (h - 16) / 2);
			context.drawItem(stack, itemX, itemY);
			if (showLabel && w >= 52) {
				String label = this.fitText(stack.getName().getString(), w - 24);
				context.drawText(this.textRenderer, label, x + 22, y + Math.max(3, (h - 8) / 2), TEXT_MAIN, false);
			}
		}

		if (clickable) {
			this.slotHitAreas.put(slotId, new HitArea(x, y, w, h));
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

	private void computeLayout() {
		int outerPad = 8;
		int railPad = 8;
		this.railGap = 6;
		this.railSlotSize = Math.max(18, Math.min(24, (this.height - 80 - this.railGap * (RAIL_SLOTS.length - 1)) / RAIL_SLOTS.length));
		this.mapX = outerPad;
		this.mapY = outerPad;
		this.mapW = this.width - (outerPad * 2) - (this.railSlotSize + railPad + 10);
		this.mapH = this.height - (outerPad * 2);
		this.railX = this.mapX + this.mapW + railPad;
		int railStackHeight = this.railSlotSize * RAIL_SLOTS.length + this.railGap * (RAIL_SLOTS.length - 1);
		this.railY = Math.max(outerPad + 26, (this.height - railStackHeight) / 2);

		this.paletteSize = Math.max(18, this.railSlotSize - 2);
		this.paletteGap = 6;
		this.paletteX = this.mapX + this.mapW - this.paletteSize - 10;
		this.paletteY = this.mapY + 10;
	}

	private int slotColor(int slotId, ItemStack stack) {
		if (stack.isOf(Items.GREEN_STAINED_GLASS_PANE)) {
			return 0xC228663A;
		}
		if (stack.isOf(Items.YELLOW_STAINED_GLASS_PANE)) {
			return 0xC37A6726;
		}
		if (stack.isOf(Items.RED_STAINED_GLASS_PANE)) {
			return 0xC5762E37;
		}
		if (stack.isOf(Items.BLUE_STAINED_GLASS_PANE) || stack.isOf(Items.LIGHT_BLUE_STAINED_GLASS_PANE)) {
			return 0xC230567F;
		}
		if (stack.isOf(Items.PLAYER_HEAD)) {
			return 0xC85E4C79;
		}
		if (stack.isOf(Items.BOOK) || stack.isOf(Items.WRITABLE_BOOK) || stack.isOf(Items.NAME_TAG)) {
			return 0xC85B5138;
		}
		if (stack.isOf(Items.COMPASS) || stack.isOf(Items.RECOVERY_COMPASS)) {
			return 0xC0336477;
		}
		if (slotId >= 18 && slotId <= 44) {
			return 0xBB274159;
		}
		return 0xC2364A5C;
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
