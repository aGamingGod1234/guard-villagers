package com.guardvillagers.client;

import com.guardvillagers.tactics.GuardTacticsScreenHandler;
<<<<<<< HEAD
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
=======
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
>>>>>>> origin/main
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
<<<<<<< HEAD
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GuardTacticsScreen extends HandledScreen<GuardTacticsScreenHandler> {
	private static final Logger LOGGER = LoggerFactory.getLogger(GuardTacticsScreen.class);
	private static final Pattern CHUNK_TITLE = Pattern.compile("Chunk\\s+(-?\\d+)\\s*,\\s*(-?\\d+)");
	private static final int MAP_SLOT_START = 18;
	private static final int MAP_SLOT_END = 44;
	private static final int SLOT_TITLE = 4;
	private static final int SLOT_MODE = 50;
=======

import java.util.HashMap;
import java.util.Map;

public final class GuardTacticsScreen extends HandledScreen<GuardTacticsScreenHandler> {
	private static final int SLOT_TITLE = 4;
	private static final int SLOT_MODE = 50;
	private static final int SLOT_INFO = 52;
>>>>>>> origin/main
	private static final int[] COLOR_SLOTS = {5, 6, 7, 8};
	private static final int[] RAIL_SLOTS = {45, 53, 47, 46, 49, 48, 50, 51, 52};

	private static final int BG_DARK = 0xF10B111A;
<<<<<<< HEAD
	private static final int MAP_TINT = 0xFF040A11;
=======
	private static final int MAP_TINT = 0xCC1A2B3C;
>>>>>>> origin/main
	private static final int MAP_BORDER = 0xFF2F4F67;
	private static final int HUD_BG = 0xC61A2A3D;
	private static final int HUD_BORDER = 0xFF4C6B83;
	private static final int TEXT_MAIN = 0xFFEAF4FF;
	private static final int TEXT_SUB = 0xFFC0D3E6;
<<<<<<< HEAD
	private static final long SLOW_ZONE_RENDER_NANOS = 20_000_000L;
	private static final long SLOW_ZONE_RENDER_LOG_COOLDOWN_NANOS = 2_000_000_000L;
	private static final int MAX_TERRAIN_COLOR_CACHE_SIZE = 1024;

	private final Map<Integer, HitArea> slotHitAreas = new HashMap<>();
	private final Map<Integer, ZoneSlotView> zoneSlots = new HashMap<>();
	private final Map<Long, Integer> terrainColorCache = new HashMap<>();
	private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

	private ChunkPoint selectionStart;
	private ChunkPoint selectionEnd;
	private boolean draggingSelection;
	private double zoneZoom = 1.0D;
=======

	private final Map<Integer, HitArea> slotHitAreas = new HashMap<>();
>>>>>>> origin/main

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
<<<<<<< HEAD
	private long lastSlowZoneRenderLogAt;
=======
>>>>>>> origin/main

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
<<<<<<< HEAD
		this.terrainColorCache.clear();
		this.lastSlowZoneRenderLogAt = 0L;
=======
>>>>>>> origin/main
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
<<<<<<< HEAD
		this.zoneSlots.clear();
=======
>>>>>>> origin/main
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
<<<<<<< HEAD
				: "Left drag: select rendered chunks | Right click: paint | Scroll: smooth zoom",
=======
				: "Paint and clear zone tiles with color chips and side controls.",
>>>>>>> origin/main
			this.mapX + 12,
			this.mapY + this.mapH - 14,
			TEXT_SUB,
			false
		);
<<<<<<< HEAD
		if (!hierarchy) {
			context.drawText(
				this.textRenderer,
				"Zoom " + String.format("%.2f", this.zoneZoom) + "x",
				this.mapX + this.mapW - 82,
				this.mapY + this.mapH - 14,
				0xFF8FDFFF,
				false
			);
		}
=======
>>>>>>> origin/main
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubleClick) {
		int button = click.button();
		if (button != 0 && button != 1) {
			return super.mouseClicked(click, doubleClick);
		}
<<<<<<< HEAD

		if (!this.isHierarchyMode() && this.contains(this.mapX, this.mapY, this.mapW, this.mapH, click.x(), click.y())) {
			int zoneSlot = this.findZoneSlotAt(click.x(), click.y());
			if (button == 0) {
				if (zoneSlot >= 0) {
					ZoneSlotView zone = this.zoneSlots.get(zoneSlot);
					if (zone != null) {
						this.selectionStart = new ChunkPoint(zone.chunkX(), zone.chunkZ());
						this.selectionEnd = this.selectionStart;
						this.draggingSelection = true;
					}
				} else {
					this.clearSelection();
				}
				return true;
			}

			if (zoneSlot >= 0 && (this.selectionStart == null || this.selectionEnd == null)) {
				ZoneSlotView zone = this.zoneSlots.get(zoneSlot);
				if (zone != null) {
					this.selectionStart = new ChunkPoint(zone.chunkX(), zone.chunkZ());
					this.selectionEnd = this.selectionStart;
				}
			}
			this.draggingSelection = false;
			this.paintSelection();
			return true;
		}

=======
>>>>>>> origin/main
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
<<<<<<< HEAD
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (!this.isHierarchyMode() && this.draggingSelection && click.button() == 0) {
			this.extendSelectionTo(click.x(), click.y());
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (!this.isHierarchyMode() && click.button() == 0 && this.draggingSelection) {
			this.extendSelectionTo(click.x(), click.y());
			this.draggingSelection = false;
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (!this.isHierarchyMode() && this.contains(this.mapX, this.mapY, this.mapW, this.mapH, mouseX, mouseY)) {
			if (verticalAmount == 0.0D) {
				return true;
			}
			double factor = Math.pow(1.12D, verticalAmount);
			this.zoneZoom = Math.max(0.35D, Math.min(5.50D, this.zoneZoom * factor));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
=======
>>>>>>> origin/main
	protected boolean isPointWithinBounds(int x, int y, int width, int height, double pointX, double pointY) {
		return false;
	}

	private void drawMapBackdrop(DrawContext context) {
		context.fill(0, 0, this.width, this.height, BG_DARK);
<<<<<<< HEAD
		context.fill(this.mapX, this.mapY, this.mapX + this.mapW, this.mapY + this.mapH, MAP_TINT);

		if (this.isHierarchyMode()) {
			this.drawOutline(context, this.mapX, this.mapY, this.mapW, this.mapH, MAP_BORDER);
			int bands = 16;
			for (int i = 0; i < bands; i++) {
				int y0 = this.mapY + (i * this.mapH) / bands;
				int y1 = this.mapY + ((i + 1) * this.mapH) / bands;
				int tint = (i % 2 == 0) ? 0x122E4A3B : 0x122A3D4F;
				context.fill(this.mapX + 1, y0, this.mapX + this.mapW - 1, y1, tint);
			}
=======
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
>>>>>>> origin/main
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
<<<<<<< HEAD
		ClientWorld world = this.client == null ? null : this.client.world;
		if (world == null) {
			return;
		}

		long renderStart = System.nanoTime();
		int renderedChunkCount = 0;
		double baseTile = Math.min(this.mapW / 9.0D, this.mapH / 3.0D);
		double tileSize = Math.max(8.0D, Math.min(180.0D, baseTile * this.zoneZoom));
		double gridW = tileSize * 9.0D;
		double gridH = tileSize * 3.0D;
		double originX = this.mapX + (this.mapW - gridW) * 0.5D;
		double originY = this.mapY + (this.mapH - gridH) * 0.5D;
		context.enableScissor(this.mapX, this.mapY, this.mapX + this.mapW, this.mapY + this.mapH);

		for (int slot = MAP_SLOT_START; slot <= MAP_SLOT_END; slot++) {
			ChunkPoint point = this.parseChunkPoint(slot);
			if (point == null || !this.isChunkRendered(world, point.x(), point.z())) {
				continue;
			}
			int idx = slot - MAP_SLOT_START;
			int row = idx / 9;
			int col = idx % 9;
			int x0 = (int) Math.floor(originX + col * tileSize);
			int y0 = (int) Math.floor(originY + row * tileSize);
			int x1 = (int) Math.ceil(originX + (col + 1) * tileSize);
			int y1 = (int) Math.ceil(originY + (row + 1) * tileSize);
			if (x1 <= this.mapX || y1 <= this.mapY || x0 >= this.mapX + this.mapW || y0 >= this.mapY + this.mapH) {
				continue;
			}

			int terrainColor = this.sampleTopColorCached(world, point.x(), point.z());
			context.fill(x0, y0, x1, y1, terrainColor);
			renderedChunkCount++;

			int overlay = this.zoneOverlayColor(this.zoneColorId(this.handler.getSlot(slot).getStack()));
			if (overlay != 0) {
				context.fill(x0, y0, x1, y1, overlay);
			}

			if (this.isInSelection(point.x(), point.z())) {
				context.fill(x0, y0, x1, y1, 0x45FFFFFF);
			}
			if (this.contains(x0, y0, x1 - x0, y1 - y0, mouseX, mouseY)) {
				context.fill(x0, y0, x1, y1, 0x24FFFFFF);
			}

			int hitX0 = Math.max(this.mapX, x0);
			int hitY0 = Math.max(this.mapY, y0);
			int hitX1 = Math.min(this.mapX + this.mapW, x1);
			int hitY1 = Math.min(this.mapY + this.mapH, y1);
			if (hitX1 <= hitX0 || hitY1 <= hitY0) {
				continue;
			}

			this.slotHitAreas.put(slot, new HitArea(hitX0, hitY0, hitX1 - hitX0, hitY1 - hitY0));
			this.zoneSlots.put(slot, new ZoneSlotView(point.x(), point.z()));
		}
		context.disableScissor();
		this.maybeLogSlowZoneRender(renderStart, renderedChunkCount);

		if (this.zoneSlots.isEmpty()) {
			context.drawCenteredTextWithShadow(
				this.textRenderer,
				Text.literal("No rendered chunks in tactical window"),
				this.mapX + this.mapW / 2,
				this.mapY + this.mapH / 2 - 4,
				0xFF9CB2C7
			);
=======
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
>>>>>>> origin/main
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

<<<<<<< HEAD
	private int findZoneSlotAt(double mouseX, double mouseY) {
		for (Map.Entry<Integer, ZoneSlotView> entry : this.zoneSlots.entrySet()) {
			HitArea area = this.slotHitAreas.get(entry.getKey());
			if (area == null) {
				continue;
			}
			if (this.contains(area.x, area.y, area.w, area.h, mouseX, mouseY)) {
				return entry.getKey();
			}
		}
		return -1;
	}

=======
>>>>>>> origin/main
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

<<<<<<< HEAD
	private ChunkPoint parseChunkPoint(int slotId) {
		ItemStack stack = this.handler.getSlot(slotId).getStack();
		if (stack.isEmpty()) {
			return null;
		}
		Matcher matcher = CHUNK_TITLE.matcher(stack.getName().getString());
		if (!matcher.find()) {
			return null;
		}
		try {
			int chunkX = Integer.parseInt(matcher.group(1));
			int chunkZ = Integer.parseInt(matcher.group(2));
			return new ChunkPoint(chunkX, chunkZ);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private boolean isChunkRendered(ClientWorld world, int chunkX, int chunkZ) {
		if (!world.isChunkLoaded(chunkX, chunkZ)) {
			return false;
		}
		Chunk chunk = world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		return chunk instanceof WorldChunk worldChunk && !worldChunk.isEmpty();
	}

	private int sampleTopColor(ClientWorld world, int chunkX, int chunkZ) {
		Chunk chunk = world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (!(chunk instanceof WorldChunk worldChunk) || worldChunk.isEmpty()) {
			return 0xFF0F1720;
		}

		long red = 0L;
		long green = 0L;
		long blue = 0L;
		int samples = 0;
		int seaLevel = world.getSeaLevel();
		for (int localX = 1; localX < 16; localX += 5) {
			for (int localZ = 1; localZ < 16; localZ += 5) {
				int topY = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, localX, localZ);
				int worldX = (chunkX << 4) + localX;
				int worldZ = (chunkZ << 4) + localZ;
				int sampleY = Math.max(world.getBottomY(), topY - 1);
				this.samplePos.set(worldX, sampleY, worldZ);
				BlockState blockState = world.getBlockState(this.samplePos);
				MapColor mapColor = blockState.getBlock().getDefaultMapColor();
				int rgb = mapColor == null ? 0x41505A : mapColor.getRenderColor(MapColor.Brightness.NORMAL);
				int shade = Math.max(-20, Math.min(20, (sampleY - seaLevel) * 2));
				rgb = this.shiftRgb(rgb, shade);
				red += (rgb >> 16) & 0xFF;
				green += (rgb >> 8) & 0xFF;
				blue += rgb & 0xFF;
				samples++;
			}
		}
		if (samples == 0) {
			return 0xFF0F1720;
		}
		int r = (int) (red / samples);
		int g = (int) (green / samples);
		int b = (int) (blue / samples);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private int sampleTopColorCached(ClientWorld world, int chunkX, int chunkZ) {
		long key = ChunkPos.toLong(chunkX, chunkZ);
		Integer cached = this.terrainColorCache.get(key);
		if (cached != null) {
			return cached;
		}

		int sampled = this.sampleTopColor(world, chunkX, chunkZ);
		if (this.terrainColorCache.size() >= MAX_TERRAIN_COLOR_CACHE_SIZE) {
			this.terrainColorCache.clear();
		}
		this.terrainColorCache.put(key, sampled);
		return sampled;
	}

	private int zoneColorId(ItemStack stack) {
		if (stack.isOf(Items.GREEN_STAINED_GLASS_PANE)) {
			return 1;
		}
		if (stack.isOf(Items.YELLOW_STAINED_GLASS_PANE)) {
			return 2;
		}
		if (stack.isOf(Items.RED_STAINED_GLASS_PANE)) {
			return 3;
		}
		if (stack.isOf(Items.BLUE_STAINED_GLASS_PANE)) {
			return 4;
		}
		return 0;
	}

	private int zoneOverlayColor(int colorId) {
		return switch (colorId) {
			case 1 -> 0x3A2FA24F;
			case 2 -> 0x3ACCAC3A;
			case 3 -> 0x3AC94B4B;
			case 4 -> 0x3A4D89D9;
			default -> 0;
		};
	}

	private void extendSelectionTo(double mouseX, double mouseY) {
		int slotId = this.findZoneSlotAt(mouseX, mouseY);
		if (slotId < 0) {
			return;
		}
		ZoneSlotView zone = this.zoneSlots.get(slotId);
		if (zone != null) {
			this.selectionEnd = new ChunkPoint(zone.chunkX(), zone.chunkZ());
		}
	}

	private void paintSelection() {
		if (this.selectionStart == null || this.selectionEnd == null) {
			return;
		}
		int minX = Math.min(this.selectionStart.x(), this.selectionEnd.x());
		int maxX = Math.max(this.selectionStart.x(), this.selectionEnd.x());
		int minZ = Math.min(this.selectionStart.z(), this.selectionEnd.z());
		int maxZ = Math.max(this.selectionStart.z(), this.selectionEnd.z());
		for (Map.Entry<Integer, ZoneSlotView> entry : this.zoneSlots.entrySet()) {
			ZoneSlotView zone = entry.getValue();
			if (zone.chunkX() < minX || zone.chunkX() > maxX || zone.chunkZ() < minZ || zone.chunkZ() > maxZ) {
				continue;
			}
			int slotId = entry.getKey();
			this.onMouseClick(this.handler.getSlot(slotId), slotId, 1, SlotActionType.PICKUP);
		}
	}

	private boolean isInSelection(int chunkX, int chunkZ) {
		if (this.selectionStart == null || this.selectionEnd == null) {
			return false;
		}
		int minX = Math.min(this.selectionStart.x(), this.selectionEnd.x());
		int maxX = Math.max(this.selectionStart.x(), this.selectionEnd.x());
		int minZ = Math.min(this.selectionStart.z(), this.selectionEnd.z());
		int maxZ = Math.max(this.selectionStart.z(), this.selectionEnd.z());
		return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
	}

	private void clearSelection() {
		this.selectionStart = null;
		this.selectionEnd = null;
		this.draggingSelection = false;
	}

	private int shiftRgb(int rgb, int amount) {
		int r = Math.max(0, Math.min(255, ((rgb >> 16) & 0xFF) + amount));
		int g = Math.max(0, Math.min(255, ((rgb >> 8) & 0xFF) + amount));
		int b = Math.max(0, Math.min(255, (rgb & 0xFF) + amount));
		return (r << 16) | (g << 8) | b;
	}

=======
>>>>>>> origin/main
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

<<<<<<< HEAD
	private void maybeLogSlowZoneRender(long renderStartNanos, int renderedChunkCount) {
		long elapsed = System.nanoTime() - renderStartNanos;
		if (elapsed < SLOW_ZONE_RENDER_NANOS) {
			return;
		}
		long now = System.nanoTime();
		if (now - this.lastSlowZoneRenderLogAt < SLOW_ZONE_RENDER_LOG_COOLDOWN_NANOS) {
			return;
		}
		this.lastSlowZoneRenderLogAt = now;
		LOGGER.info(
			"Slow tactics zone render: {} ms, rendered chunks: {}, zoom: {}",
			elapsed / 1_000_000.0D,
			renderedChunkCount,
			String.format("%.2f", this.zoneZoom)
		);
	}

	private record HitArea(int x, int y, int w, int h) {
	}

	private record ZoneSlotView(int chunkX, int chunkZ) {
	}

	private record ChunkPoint(int x, int z) {
	}
=======
	private record HitArea(int x, int y, int w, int h) {
	}
>>>>>>> origin/main
}
