package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.tactics.GuardTacticsScreenHandler;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class GuardTacticsScreen extends HandledScreen<GuardTacticsScreenHandler> {
	private static final int PANEL_BACKGROUND = 0xF1161C25;
	private static final int PANEL_BORDER = 0xFF3B4A5E;
	private static final int TEXT_PRIMARY = 0xFFEAF1FA;
	private static final int TEXT_SECONDARY = 0xFFB6C6D6;
	private static final int SUBTITLE_TACTICS = 0xFF8FD2FF;
	private static final int SUBTITLE_GROUPS = 0xFFFFCB90;
	private static final int ROW_BACKGROUND = 0xCC1B2531;
	private static final int ROW_BORDER = 0xFF334154;
	private static final int CARD_BACKGROUND = 0xEE17212D;
	private static final int CARD_BORDER = 0xFF425067;
	private static final int CONNECTOR_COLOR = 0xFF4F6176;

	private static final int SWATCH_SIZE = 14;
	private static final int SWATCH_GAP = 4;
	private static final int HEADER_HEIGHT = 26;
	private static final int BOTTOM_BAR_HEIGHT = 18;
	private static final int PLAYER_PANEL_WIDTH = 180;
	private static final int PLAYER_PANEL_HEIGHT = 28;
	private static final int ROW_HEIGHT = 58;
	private static final int GROUP_HEADER_WIDTH = 190;
	private static final int GROUP_HEADER_HEIGHT = 24;
	private static final int GUARD_CARD_WIDTH = 78;
	private static final int GUARD_CARD_HEIGHT = 46;
	private static final int GUARD_CARD_GAP = 6;

	private static final ItemStack GUARD_HEAD_ICON = new ItemStack(Items.PLAYER_HEAD);

	private final ClientTacticsDataStore dataStore = ClientTacticsDataStore.getInstance();
	private final ChunkMapWidget chunkMapWidget = new ChunkMapWidget(this.dataStore, GuardVillagersClient.terrainCache());
	private ViewMode mode;
	private final List<PaletteSwatch> paletteSwatches = new ArrayList<>();
	private final List<GroupRowHitbox> groupRows = new ArrayList<>();
	private final List<GuardCardHitbox> guardCards = new ArrayList<>();
	private PaletteSwatch groupsToggleSwatch;

	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;
	private int contentX;
	private int contentY;
	private int contentWidth;
	private int contentHeight;
	private int groupRowsStartY;
	private int groupRowsHeight;
	private int groupMaxScroll;
	private int groupScrollRows;
	private TextFieldWidget groupRenameField;
	private int editingRow = -1;
	private int paletteScrollOffset = 0;
	private static final int VISIBLE_SWATCHES = 4;

	public GuardTacticsScreen(GuardTacticsScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		String normalized = title.getString().toLowerCase(Locale.ROOT);
		this.mode = normalized.contains("group") || normalized.contains("hierarchy") ? ViewMode.GROUPS : ViewMode.TACTICS;
	}

	@Override
	protected void init() {
		super.init();
		this.backgroundWidth = this.width;
		this.backgroundHeight = this.height;
		this.x = 0;
		this.y = 0;
		this.computeLayout();

		this.groupRenameField = new TextFieldWidget(this.textRenderer, 0, 0, GROUP_HEADER_WIDTH - 8, 18, Text.literal("Group Name"));
		this.groupRenameField.setVisible(false);
		this.groupRenameField.setMaxLength(24);
		this.addDrawableChild(this.groupRenameField);

		if (this.client != null && this.client.player != null) {
			this.chunkMapWidget.ensureCameraCentered(this.client.player.getChunkPos());
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		this.computeLayout();
		this.renderBackground(context, mouseX, mouseY, delta);
		context.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, PANEL_BACKGROUND);
		this.drawBorder(context, this.panelX, this.panelY, this.panelWidth, this.panelHeight, PANEL_BORDER);

		if (this.mode == ViewMode.TACTICS) {
			this.renderTactics(context, mouseX, mouseY);
		} else {
			this.renderGroups(context, mouseX, mouseY);
		}
		this.renderCommonHeader(context);
		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			this.groupRenameField.render(context, mouseX, mouseY, delta);
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubleClick) {
		double mouseX = click.x();
		double mouseY = click.y();
		int button = click.button();

		if (this.groupRenameField != null && this.groupRenameField.isVisible() && this.groupRenameField.mouseClicked(click, doubleClick)) {
			return true;
		}

		if (this.mode == ViewMode.TACTICS) {
			if (this.handlePaletteClick(mouseX, mouseY)) {
				return true;
			}
			if (this.chunkMapWidget.mouseClicked(
				mouseX,
				mouseY,
				button,
				click.buttonInfo().hasShift(),
				this.client == null ? null : this.client.world,
				this.resolveWorldContext()
			)) {
				return true;
			}
			if (this.contains(this.contentX, this.contentY, this.contentWidth, this.contentHeight, mouseX, mouseY)) {
				return true;
			}
			return super.mouseClicked(click, doubleClick);
		}

		if (this.handleGroupsClick(mouseX, mouseY, button, click.buttonInfo().hasShift())) {
			return true;
		}
		if (this.contains(this.panelX, this.panelY, this.panelWidth, this.panelHeight, mouseX, mouseY)) {
			return true;
		}
		return super.mouseClicked(click, doubleClick);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (this.mode == ViewMode.TACTICS) {
			if (this.chunkMapWidget.mouseDragged(
				click.x(),
				click.y(),
				click.button(),
				deltaX,
				deltaY,
				this.client == null ? null : this.client.world,
				this.resolveWorldContext()
			)) {
				return true;
			}
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (this.mode == ViewMode.TACTICS && this.chunkMapWidget.mouseReleased(click.x(), click.y(), click.button())) {
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (this.mode == ViewMode.TACTICS) {
			// Check if scrolling over palette area
			if (this.isPaletteHovered(mouseX, mouseY)) {
				int direction = verticalAmount < 0 ? 1 : -1;
				int maxOffset = Math.max(0, RegionColor.paletteCount() - VISIBLE_SWATCHES);
				this.paletteScrollOffset = MathHelper.clamp(this.paletteScrollOffset + direction, 0, maxOffset);
				return true;
			}
			if (this.chunkMapWidget.mouseScrolled(mouseX, mouseY, verticalAmount)) {
				return true;
			}
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}

		if (this.contains(this.contentX, this.groupRowsStartY, this.contentWidth, this.groupRowsHeight, mouseX, mouseY)) {
			int direction = verticalAmount < 0 ? 1 : -1;
			this.groupScrollRows = MathHelper.clamp(this.groupScrollRows + direction, 0, this.groupMaxScroll);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyInput keyInput) {
		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			if (keyInput.isEnter()) {
				this.commitGroupRename();
				return true;
			}
			if (keyInput.isEscape()) {
				this.cancelGroupRename();
				return true;
			}
			if (this.groupRenameField.keyPressed(keyInput)) {
				return true;
			}
		}

		if (this.mode == ViewMode.TACTICS && keyInput.getKeycode() == GLFW.GLFW_KEY_C) {
			this.chunkMapWidget.clearSelection();
			return true;
		}
		return super.keyPressed(keyInput);
	}

	@Override
	public boolean charTyped(CharInput charInput) {
		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			return this.groupRenameField.charTyped(charInput);
		}
		return super.charTyped(charInput);
	}

	private void renderCommonHeader(DrawContext context) {
		context.drawText(this.textRenderer, this.title, this.panelX + 10, this.panelY + 8, TEXT_PRIMARY, false);
		context.drawText(
			this.textRenderer,
			this.mode == ViewMode.GROUPS ? Text.literal("Group Configuration") : Text.literal("Zone Tactical View"),
			this.panelX + 10,
			this.panelY + 20,
			this.mode == ViewMode.GROUPS ? SUBTITLE_GROUPS : SUBTITLE_TACTICS,
			false
		);
	}

	private void renderTactics(DrawContext context, int mouseX, int mouseY) {
		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			this.groupRenameField.setVisible(false);
			this.editingRow = -1;
		}

		ClientTacticsDataStore.WorldContext worldContext = this.resolveWorldContext();
		if (this.client != null && this.client.player != null) {
			this.chunkMapWidget.ensureCameraCentered(this.client.player.getChunkPos());
		}

		int mapX = this.contentX;
		int mapY = this.contentY;
		int mapW = this.contentWidth;
		int mapH = this.contentHeight - BOTTOM_BAR_HEIGHT;
		this.chunkMapWidget.setBounds(mapX, mapY, mapW, mapH);
		this.renderPalette(context);
		this.chunkMapWidget.render(context, this.client == null ? null : this.client.world, worldContext, mouseX, mouseY);

		context.fill(this.contentX, this.contentY + this.contentHeight - BOTTOM_BAR_HEIGHT, this.contentX + this.contentWidth, this.contentY + this.contentHeight, 0xAA131B25);
		context.drawText(
			this.textRenderer,
			Text.literal("LMB drag: select chunks | RMB: paint | Shift+RMB: clear | Wheel: zoom | Middle drag: pan | C: clear selection"),
			this.contentX + 6,
			this.contentY + this.contentHeight - 13,
			TEXT_SECONDARY,
			false
		);
		context.drawText(
			this.textRenderer,
			Text.literal(String.format(Locale.ROOT, "Zoom %.2fx", this.chunkMapWidget.zoom())),
			this.contentX + this.contentWidth - 74,
			this.contentY + this.contentHeight - 13,
			SUBTITLE_TACTICS,
			false
		);

		ChunkPos hovered = this.chunkMapWidget.hoveredChunk();
		if (hovered != null && worldContext != null) {
			RegionColor zoneColor = this.dataStore.getRegionColor(worldContext, hovered.x, hovered.z);
			String groupLabel = "None";
			if (zoneColor != RegionColor.NONE) {
				groupLabel = this.dataStore.groupBindingForColor(worldContext, zoneColor)
					.map(binding -> binding.groupName() + " (R" + (binding.row() + 1) + ")")
					.orElse("None");
			}
			List<Text> tooltip = new ArrayList<>();
			tooltip.add(Text.literal("Chunk: " + hovered.x + ", " + hovered.z));
			tooltip.add(Text.literal("Zone: " + zoneColor.label()));
			tooltip.add(Text.literal("Group: " + groupLabel));
			tooltip.add(Text.literal("LMB drag select | RMB paint"));
			context.drawTooltip(this.textRenderer, tooltip, mouseX, mouseY);
		}
	}

	private void renderPalette(DrawContext context) {
		this.paletteSwatches.clear();
		RegionColor[] allColors = RegionColor.paletteColors();
		int maxOffset = Math.max(0, allColors.length - VISIBLE_SWATCHES);
		this.paletteScrollOffset = Math.min(this.paletteScrollOffset, maxOffset);

		// Toggle button (rightmost position)
		int toggleSize = SWATCH_SIZE;
		int toggleX = this.panelX + this.panelWidth - 10 - toggleSize;
		int toggleY = this.panelY + 9;
		context.fill(toggleX, toggleY, toggleX + toggleSize, toggleY + toggleSize, 0xFF334154);
		this.drawBorder(context, toggleX, toggleY, toggleSize, toggleSize, 0xFF5A6A7E);
		// Draw "G" for groups toggle
		context.drawText(this.textRenderer, Text.literal("G"), toggleX + 3, toggleY + 3, 0xFFEAF1FA, false);
		this.paletteSwatches.add(new PaletteSwatch(null, toggleX, toggleY, toggleSize, toggleSize));

		// Separator line
		int sepX = toggleX - 5;
		context.fill(sepX, toggleY, sepX + 1, toggleY + toggleSize, PANEL_BORDER);

		// Color swatches with scroll window
		int swatchAreaWidth = VISIBLE_SWATCHES * SWATCH_SIZE + (VISIBLE_SWATCHES - 1) * SWATCH_GAP;
		int startX = sepX - 5 - swatchAreaWidth;
		int y = toggleY;

		for (int i = 0; i < VISIBLE_SWATCHES && (i + this.paletteScrollOffset) < allColors.length; i++) {
			int colorIndex = i + this.paletteScrollOffset;
			RegionColor color = allColors[colorIndex];
			int x = startX + i * (SWATCH_SIZE + SWATCH_GAP);

			// Fade last swatch if more colors exist beyond view
			boolean isLast = (i == VISIBLE_SWATCHES - 1) && (colorIndex < allColors.length - 1);
			int fillColor = color.swatchArgb();
			if (isLast) {
				// Reduce alpha for fade effect
				fillColor = (fillColor & 0x00FFFFFF) | 0xAA000000;
			}
			context.fill(x, y, x + SWATCH_SIZE, y + SWATCH_SIZE, fillColor);
			int border = this.chunkMapWidget.activeColor() == color ? 0xFFDCEBFF : 0xFF3B4A5E;
			this.drawBorder(context, x, y, SWATCH_SIZE, SWATCH_SIZE, border);
			this.paletteSwatches.add(new PaletteSwatch(color, x, y, SWATCH_SIZE, SWATCH_SIZE));
		}

		// Separator before palette area
		int sepX2 = startX - 5;
		context.fill(sepX2, y, sepX2 + 1, y + toggleSize, PANEL_BORDER);
	}

	private boolean handlePaletteClick(double mouseX, double mouseY) {
		for (PaletteSwatch swatch : this.paletteSwatches) {
			if (swatch.contains(mouseX, mouseY)) {
				if (swatch.color() == null) {
					// Toggle button — switch to Groups mode
					this.mode = ViewMode.GROUPS;
					return true;
				}
				this.chunkMapWidget.setActiveColor(swatch.color());
				return true;
			}
		}
		return false;
	}

	private void renderGroups(DrawContext context, int mouseX, int mouseY) {
		if (this.groupRenameField != null && this.editingRow >= 0) {
			this.groupRenameField.setVisible(false);
		}

		// Toggle button to switch back to Zones view
		int toggleSize = SWATCH_SIZE;
		int toggleX = this.panelX + this.panelWidth - 10 - toggleSize;
		int toggleY = this.panelY + 9;
		context.fill(toggleX, toggleY, toggleX + toggleSize, toggleY + toggleSize, 0xFF334154);
		this.drawBorder(context, toggleX, toggleY, toggleSize, toggleSize, 0xFF5A6A7E);
		context.drawText(this.textRenderer, Text.literal("Z"), toggleX + 3, toggleY + 3, 0xFFEAF1FA, false);
		this.groupsToggleSwatch = new PaletteSwatch(null, toggleX, toggleY, toggleSize, toggleSize);

		ClientTacticsDataStore.WorldContext worldContext = this.resolveWorldContext();
		if (worldContext == null || this.client == null || this.client.player == null || this.client.world == null) {
			context.drawText(this.textRenderer, Text.literal("Group data unavailable."), this.contentX, this.contentY, TEXT_SECONDARY, false);
			return;
		}

		List<GuardEntity> guards = this.collectOwnedGuards(this.client.player.getUuid());
		Map<Integer, List<GuardEntity>> guardsByRow = new HashMap<>();
		int maxGuardRow = 0;
		for (GuardEntity guard : guards) {
			int row = Math.max(0, guard.getGroupIndex());
			maxGuardRow = Math.max(maxGuardRow, row);
			guardsByRow.computeIfAbsent(row, ignored -> new ArrayList<>()).add(guard);
		}

		for (List<GuardEntity> rowGuards : guardsByRow.values()) {
			rowGuards.sort(Comparator
				.comparingInt(this::armorGearScore).reversed()
				.thenComparing(Comparator.comparingInt(GuardEntity::getLevel).reversed())
				.thenComparing(guard -> guard.getName().getString(), String.CASE_INSENSITIVE_ORDER));
		}

		int groupCount = Math.max(3, Math.max(maxGuardRow + 1, this.dataStore.groupCount(worldContext)));
		this.dataStore.ensureGroupCount(worldContext, groupCount);

		int playerPanelX = this.contentX + (this.contentWidth - PLAYER_PANEL_WIDTH) / 2;
		int playerPanelY = this.contentY;
		int playerCenterX = playerPanelX + PLAYER_PANEL_WIDTH / 2;
		int playerBottomY = playerPanelY + PLAYER_PANEL_HEIGHT;
		context.fill(playerPanelX, playerPanelY, playerPanelX + PLAYER_PANEL_WIDTH, playerPanelY + PLAYER_PANEL_HEIGHT, ROW_BACKGROUND);
		this.drawBorder(context, playerPanelX, playerPanelY, PLAYER_PANEL_WIDTH, PLAYER_PANEL_HEIGHT, ROW_BORDER);
		context.drawItem(GUARD_HEAD_ICON, playerPanelX + 6, playerPanelY + 6);
		context.drawText(this.textRenderer, this.client.player.getName(), playerPanelX + 28, playerPanelY + 10, TEXT_PRIMARY, false);

		this.groupRows.clear();
		this.guardCards.clear();
		this.groupRowsStartY = playerPanelY + PLAYER_PANEL_HEIGHT + 10;
		this.groupRowsHeight = Math.max(0, this.contentHeight - PLAYER_PANEL_HEIGHT - 16);
		int visibleRows = Math.max(1, this.groupRowsHeight / ROW_HEIGHT);
		this.groupMaxScroll = Math.max(0, groupCount - visibleRows);
		this.groupScrollRows = MathHelper.clamp(this.groupScrollRows, 0, this.groupMaxScroll);

		context.enableScissor(this.contentX, this.groupRowsStartY, this.contentX + this.contentWidth, this.groupRowsStartY + this.groupRowsHeight);
		for (int visibleIndex = 0; visibleIndex < visibleRows; visibleIndex++) {
			int row = visibleIndex + this.groupScrollRows;
			if (row >= groupCount) {
				break;
			}

			int rowY = this.groupRowsStartY + visibleIndex * ROW_HEIGHT;
			int rowX = this.contentX + 4;
			int rowW = this.contentWidth - 8;
			context.fill(rowX, rowY, rowX + rowW, rowY + ROW_HEIGHT - 4, ROW_BACKGROUND);
			this.drawBorder(context, rowX, rowY, rowW, ROW_HEIGHT - 4, ROW_BORDER);

			int swatchX = rowX + 6;
			int swatchY = rowY + 20;
			RegionColor groupColor = this.dataStore.getGroupColor(worldContext, row);
			int swatchFill = groupColor == RegionColor.NONE ? 0xFF2C323D : groupColor.swatchArgb();
			context.fill(swatchX, swatchY, swatchX + 14, swatchY + 14, swatchFill);
			this.drawBorder(context, swatchX, swatchY, 14, 14, 0xFF6A7A8D);

			int headerX = rowX + 28;
			int headerY = rowY + 15;
			context.fill(headerX, headerY, headerX + GROUP_HEADER_WIDTH, headerY + GROUP_HEADER_HEIGHT, 0xCC121A24);
			this.drawBorder(context, headerX, headerY, GROUP_HEADER_WIDTH, GROUP_HEADER_HEIGHT, 0xFF4D5E73);

			List<GuardEntity> rowGuards = guardsByRow.getOrDefault(row, List.of());
			String groupName = this.resolveGroupName(worldContext, row, rowGuards);
			context.drawText(this.textRenderer, Text.literal(groupName), headerX + 5, headerY + 4, TEXT_PRIMARY, false);
			context.drawText(this.textRenderer, Text.literal(rowGuards.size() + " guards"), headerX + 5, headerY + 14, TEXT_SECONDARY, false);
			this.groupRows.add(new GroupRowHitbox(row, swatchX, swatchY, 14, 14, headerX, headerY, GROUP_HEADER_WIDTH, GROUP_HEADER_HEIGHT, groupName));

			if (this.editingRow == row && this.groupRenameField != null) {
				this.groupRenameField.setVisible(true);
				this.groupRenameField.setX(headerX + 4);
				this.groupRenameField.setY(headerY + 3);
				this.groupRenameField.setWidth(GROUP_HEADER_WIDTH - 8);
			}

			int headerCenterY = headerY + GROUP_HEADER_HEIGHT / 2;
			this.drawConnector(context, playerCenterX, playerBottomY, headerX + GROUP_HEADER_WIDTH / 2, headerCenterY, CONNECTOR_COLOR);

			int cardsStartX = headerX + GROUP_HEADER_WIDTH + 14;
			int cardY = rowY + 6;
			int availableWidth = rowX + rowW - cardsStartX - 4;
			int cardsFit = Math.max(0, (availableWidth + GUARD_CARD_GAP) / (GUARD_CARD_WIDTH + GUARD_CARD_GAP));
			int cardsToDraw = Math.min(cardsFit, rowGuards.size());
			for (int i = 0; i < cardsToDraw; i++) {
				GuardEntity guard = rowGuards.get(i);
				int cardX = cardsStartX + i * (GUARD_CARD_WIDTH + GUARD_CARD_GAP);
				this.renderGuardCard(context, guard, cardX, cardY);
				this.guardCards.add(new GuardCardHitbox(guard, cardX, cardY, GUARD_CARD_WIDTH, GUARD_CARD_HEIGHT));
				this.drawConnector(context, headerX + GROUP_HEADER_WIDTH, headerCenterY, cardX, cardY + GUARD_CARD_HEIGHT / 2, CONNECTOR_COLOR);
			}
		}
		context.disableScissor();

		if (this.editingRow >= 0 && this.groupRenameField != null && !this.groupRenameField.isVisible()) {
			this.cancelGroupRename();
		}

		context.fill(this.contentX, this.contentY + this.contentHeight - BOTTOM_BAR_HEIGHT, this.contentX + this.contentWidth, this.contentY + this.contentHeight, 0xAA131B25);
		context.drawText(this.textRenderer, Text.literal("Shift+RMB role header: rename | Click row swatch: cycle region color"), this.contentX + 6, this.contentY + this.contentHeight - 13, TEXT_SECONDARY, false);

		GuardCardHitbox hoveredGuard = this.findHoveredGuard(mouseX, mouseY);
		if (hoveredGuard != null) {
			GuardEntity guard = hoveredGuard.guard();
			List<Text> tooltip = new ArrayList<>();
			tooltip.add(guard.getName());
			ItemStack weapon = guard.getMainHandStack();
			ItemStack helmet = guard.getEquippedStack(EquipmentSlot.HEAD);
			ItemStack chest = guard.getEquippedStack(EquipmentSlot.CHEST);
			ItemStack legs = guard.getEquippedStack(EquipmentSlot.LEGS);
			ItemStack boots = guard.getEquippedStack(EquipmentSlot.FEET);
			int headArmor = this.armorPoints(helmet);
			int chestArmor = this.armorPoints(chest);
			int legsArmor = this.armorPoints(legs);
			int bootsArmor = this.armorPoints(boots);
			int totalArmor = headArmor + chestArmor + legsArmor + bootsArmor;
			double attackSpeed = 1.6D;
			int cooldownTicks = Math.max(1, (int) Math.round(20.0D / attackSpeed));
			tooltip.add(Text.literal("\u00A77Weapon: \u00A7f" + this.itemName(weapon) + " (" + this.weaponDamage(weapon) + " dmg)"));
			tooltip.add(Text.literal("\u00A77Helmet: \u00A7f" + this.itemName(helmet) + " (" + headArmor + ")"));
			tooltip.add(Text.literal("\u00A77Chestplate: \u00A7f" + this.itemName(chest) + " (" + chestArmor + ")"));
			tooltip.add(Text.literal("\u00A77Leggings: \u00A7f" + this.itemName(legs) + " (" + legsArmor + ")"));
			tooltip.add(Text.literal("\u00A77Boots: \u00A7f" + this.itemName(boots) + " (" + bootsArmor + ")"));
			tooltip.add(Text.literal("\u00A77Total armour: \u00A7f" + totalArmor));
			tooltip.add(Text.literal("\u00A77Health: \u00A7f" + (int) guard.getMaxHealth() + " HP"));
			tooltip.add(Text.literal("\u00A77Attack speed: \u00A7f" + String.format(Locale.ROOT, "%.2f", attackSpeed) + " (" + cooldownTicks + "t cd)"));
			tooltip.add(Text.literal("\u00A77Hire price: \u00A7f" + com.guardvillagers.GuardHirePricing.getHirePrice(guard.getLevel()) + " emerald(s)"));
			context.drawTooltip(this.textRenderer, tooltip, mouseX, mouseY);
		}
	}

	private boolean handleGroupsClick(double mouseX, double mouseY, int button, boolean shiftDown) {
		// Check toggle button first
		if (this.groupsToggleSwatch != null && this.groupsToggleSwatch.contains(mouseX, mouseY)) {
			this.mode = ViewMode.TACTICS;
			return true;
		}

		ClientTacticsDataStore.WorldContext worldContext = this.resolveWorldContext();
		if (worldContext == null) {
			return false;
		}

		for (GroupRowHitbox row : this.groupRows) {
			if (row.containsSwatch(mouseX, mouseY)) {
				RegionColor current = this.dataStore.getGroupColor(worldContext, row.row());
				if (button == 1) {
					this.dataStore.setGroupColor(worldContext, row.row(), RegionColor.NONE);
				} else {
					RegionColor next = (current == RegionColor.NONE) ? RegionColor.BLUE : current.nextPaletteColor();
					this.dataStore.setGroupColor(worldContext, row.row(), next);
				}
				return true;
			}
			if (row.containsHeader(mouseX, mouseY) && button == 1 && shiftDown) {
				this.startGroupRename(row.row(), row.groupName());
				return true;
			}
		}
		return false;
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
	}

	private void startGroupRename(int row, String currentName) {
		if (this.groupRenameField == null) {
			return;
		}
		this.editingRow = row;
		this.groupRenameField.setVisible(true);
		this.groupRenameField.setText(currentName);
		this.groupRenameField.setCursorToEnd(false);
		this.setFocused(this.groupRenameField);
	}

	private void commitGroupRename() {
		if (this.groupRenameField == null || this.editingRow < 0) {
			return;
		}
		ClientTacticsDataStore.WorldContext worldContext = this.resolveWorldContext();
		if (worldContext == null) {
			this.cancelGroupRename();
			return;
		}
		String requested = this.groupRenameField.getText();
		if (requested == null || requested.isBlank()) {
			requested = "Role";
		}
		this.dataStore.setGroupName(worldContext, this.editingRow, requested);

		if (this.client != null && this.client.getNetworkHandler() != null) {
			String command = "guards groups rename " + (this.editingRow + 1) + " " + requested.trim();
			this.client.getNetworkHandler().sendChatCommand(command);
		}

		this.groupRenameField.setVisible(false);
		this.editingRow = -1;
		this.setFocused(null);
	}

	private void cancelGroupRename() {
		if (this.groupRenameField != null) {
			this.groupRenameField.setVisible(false);
		}
		this.editingRow = -1;
		this.setFocused(null);
	}

	private List<GuardEntity> collectOwnedGuards(UUID owner) {
		if (this.client == null || this.client.world == null) {
			return List.of();
		}
		List<GuardEntity> allGuards = this.client.world.getEntitiesByClass(GuardEntity.class, new Box(-30_000_000, this.client.world.getBottomY(), -30_000_000, 30_000_000, this.client.world.getTopYInclusive(), 30_000_000), guard -> guard.isOwnedBy(owner));
		return allGuards;
	}

	private void renderGuardCard(DrawContext context, GuardEntity guard, int x, int y) {
		context.fill(x, y, x + GUARD_CARD_WIDTH, y + GUARD_CARD_HEIGHT, CARD_BACKGROUND);
		this.drawBorder(context, x, y, GUARD_CARD_WIDTH, GUARD_CARD_HEIGHT, CARD_BORDER);
		context.drawItem(GUARD_HEAD_ICON, x + 4, y + 4);
		context.drawText(this.textRenderer, Text.literal("Lv " + guard.getLevel()), x + 24, y + 8, TEXT_PRIMARY, false);

		this.drawArmorIcon(context, guard.getEquippedStack(EquipmentSlot.HEAD), x + 4, y + 24);
		this.drawArmorIcon(context, guard.getEquippedStack(EquipmentSlot.CHEST), x + 20, y + 24);
		this.drawArmorIcon(context, guard.getEquippedStack(EquipmentSlot.LEGS), x + 36, y + 24);
		this.drawArmorIcon(context, guard.getEquippedStack(EquipmentSlot.FEET), x + 52, y + 24);
	}

	private void drawArmorIcon(DrawContext context, ItemStack stack, int x, int y) {
		if (stack == null || stack.isEmpty()) {
			this.drawBorder(context, x, y, 16, 16, 0x884D5F72);
			return;
		}
		context.drawItem(stack, x, y);
	}

	private int armorGearScore(GuardEntity guard) {
		return this.pieceScore(guard.getEquippedStack(EquipmentSlot.HEAD))
			+ this.pieceScore(guard.getEquippedStack(EquipmentSlot.CHEST))
			+ this.pieceScore(guard.getEquippedStack(EquipmentSlot.LEGS))
			+ this.pieceScore(guard.getEquippedStack(EquipmentSlot.FEET));
	}

	private int pieceScore(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		String path = Registries.ITEM.getId(stack.getItem()).getPath();
		if (path.contains("netherite")) {
			return 5;
		}
		if (path.contains("diamond")) {
			return 4;
		}
		if (path.contains("iron")) {
			return 3;
		}
		if (path.contains("chainmail") || path.contains("golden")) {
			return 2;
		}
		if (path.contains("leather")) {
			return 1;
		}
		return 0;
	}

	private String resolveGroupName(ClientTacticsDataStore.WorldContext worldContext, int row, List<GuardEntity> rowGuards) {
		String stored = this.dataStore.getGroupName(worldContext, row);
		if (!rowGuards.isEmpty() && this.isGenericGroupName(stored)) {
			String fromGuard = rowGuards.getFirst().getGroupName();
			if (fromGuard != null && !fromGuard.isBlank()) {
				return fromGuard;
			}
		}
		return stored;
	}

	private boolean isGenericGroupName(String groupName) {
		if (groupName == null) {
			return true;
		}
		String trimmed = groupName.trim();
		return trimmed.equals("Role") || trimmed.matches("Role\\s+\\d+");
	}

	private String itemName(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "None";
		}
		return stack.getName().getString();
	}

	private int armorPoints(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		String path = Registries.ITEM.getId(stack.getItem()).getPath();
		if (path.contains("helmet")) {
			if (path.contains("netherite") || path.contains("diamond")) return 3;
			if (path.contains("iron")) return 2;
			if (path.contains("chainmail") || path.contains("golden")) return 2;
			if (path.contains("leather")) return 1;
		}
		if (path.contains("chestplate")) {
			if (path.contains("netherite") || path.contains("diamond")) return 8;
			if (path.contains("iron")) return 6;
			if (path.contains("chainmail") || path.contains("golden")) return 5;
			if (path.contains("leather")) return 3;
		}
		if (path.contains("leggings")) {
			if (path.contains("netherite") || path.contains("diamond")) return 6;
			if (path.contains("iron")) return 5;
			if (path.contains("chainmail") || path.contains("golden")) return 3;
			if (path.contains("leather")) return 2;
		}
		if (path.contains("boots")) {
			if (path.contains("netherite") || path.contains("diamond")) return 3;
			if (path.contains("iron")) return 2;
			if (path.contains("chainmail") || path.contains("golden")) return 1;
			if (path.contains("leather")) return 1;
		}
		return 0;
	}

	private int weaponDamage(ItemStack stack) {
		if (stack.isOf(Items.STONE_SWORD)) {
			return 5;
		}
		if (stack.isOf(Items.IRON_SWORD)) {
			return 6;
		}
		if (stack.isOf(Items.DIAMOND_SWORD)) {
			return 7;
		}
		return 1;
	}

	private GuardCardHitbox findHoveredGuard(int mouseX, int mouseY) {
		for (GuardCardHitbox card : this.guardCards) {
			if (card.contains(mouseX, mouseY)) {
				return card;
			}
		}
		return null;
	}

	private ClientTacticsDataStore.WorldContext resolveWorldContext() {
		if (this.client == null || this.client.world == null) {
			return null;
		}
		return ClientTacticsDataStore.resolveContext(this.client, this.client.world);
	}

	private boolean isPaletteHovered(double mouseX, double mouseY) {
		for (PaletteSwatch swatch : this.paletteSwatches) {
			if (swatch.color() != null && swatch.contains(mouseX, mouseY)) {
				return true;
			}
		}
		// Also check the area between swatches (the full palette row)
		if (!this.paletteSwatches.isEmpty()) {
			int minX = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int minY = Integer.MAX_VALUE;
			int maxY = Integer.MIN_VALUE;
			for (PaletteSwatch swatch : this.paletteSwatches) {
				if (swatch.color() != null) {
					minX = Math.min(minX, swatch.x());
					maxX = Math.max(maxX, swatch.x() + swatch.width());
					minY = Math.min(minY, swatch.y());
					maxY = Math.max(maxY, swatch.y() + swatch.height());
				}
			}
			if (minX != Integer.MAX_VALUE && mouseX >= minX && mouseX < maxX && mouseY >= minY && mouseY < maxY) {
				return true;
			}
		}
		return false;
	}

	private void drawConnector(DrawContext context, int fromX, int fromY, int toX, int toY, int color) {
		int verticalTop = Math.min(fromY, toY);
		int verticalBottom = Math.max(fromY, toY);
		context.fill(fromX, verticalTop, fromX + 1, verticalBottom + 1, color);
		int horizontalLeft = Math.min(fromX, toX);
		int horizontalRight = Math.max(fromX, toX);
		context.fill(horizontalLeft, toY, horizontalRight + 1, toY + 1, color);
	}

	private void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
		context.fill(x, y, x + width, y + 1, color);
		context.fill(x, y + height - 1, x + width, y + height, color);
		context.fill(x, y, x + 1, y + height, color);
		context.fill(x + width - 1, y, x + width, y + height, color);
	}

	private boolean contains(int x, int y, int width, int height, double mouseX, double mouseY) {
		return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
	}

	private void computeLayout() {
		this.panelX = 10;
		this.panelY = 10;
		this.panelWidth = this.width - 20;
		this.panelHeight = this.height - 20;
		this.contentX = this.panelX + 8;
		this.contentY = this.panelY + HEADER_HEIGHT + 4;
		this.contentWidth = this.panelWidth - 16;
		this.contentHeight = this.panelHeight - HEADER_HEIGHT - 8;
	}

	private enum ViewMode {
		TACTICS,
		GROUPS
	}

	private record PaletteSwatch(RegionColor color, int x, int y, int width, int height) {
		private boolean contains(double mouseX, double mouseY) {
			return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
		}
	}

	private record GroupRowHitbox(
		int row,
		int swatchX,
		int swatchY,
		int swatchW,
		int swatchH,
		int headerX,
		int headerY,
		int headerW,
		int headerH,
		String groupName
	) {
		private boolean containsSwatch(double mouseX, double mouseY) {
			return mouseX >= this.swatchX && mouseY >= this.swatchY && mouseX < this.swatchX + this.swatchW && mouseY < this.swatchY + this.swatchH;
		}

		private boolean containsHeader(double mouseX, double mouseY) {
			return mouseX >= this.headerX && mouseY >= this.headerY && mouseX < this.headerX + this.headerW && mouseY < this.headerY + this.headerH;
		}
	}

	private record GuardCardHitbox(GuardEntity guard, int x, int y, int width, int height) {
		private boolean contains(double mouseX, double mouseY) {
			return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
		}
	}
}
