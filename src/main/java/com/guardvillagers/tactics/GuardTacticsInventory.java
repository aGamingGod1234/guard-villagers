package com.guardvillagers.tactics;

import com.guardvillagers.GuardTacticsManager;
import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

public final class GuardTacticsInventory extends SimpleInventory {
	private static final int SIZE = 54;
	private static final int MAP_SLOT_START = 18;
	private static final int MAP_SLOT_END = 44;
	private static final int ZOOM_OUT_SLOT = 45;
	private static final int PAN_WEST_SLOT = 46;
	private static final int PAN_NORTH_SLOT = 47;
	private static final int PAN_SOUTH_SLOT = 48;
	private static final int PAN_EAST_SLOT = 49;
	private static final int MODE_SLOT = 50;
	private static final int ADD_ROLE_SLOT = 51;
	private static final int INFO_SLOT = 52;
	private static final int ZOOM_IN_SLOT = 53;
	private static final int SLOT_TITLE = 4;
	private static final int SLOT_GREEN = 5;
	private static final int SLOT_YELLOW = 6;
	private static final int SLOT_RED = 7;
	private static final int SLOT_BLUE = 8;

	private final ServerPlayerEntity owner;
	private final GuardTacticsState tacticsState;
	private final GuardTacticsState.PlayerTactics playerTactics;
	private final Map<Integer, Long> zoneSlotChunks = new HashMap<>();
	private final Map<Integer, HierarchySlotBinding> hierarchySlotBindings = new HashMap<>();
	private final Map<UUID, GuardEntity> ownedGuardCache = new HashMap<>();
	private ViewMode mode = ViewMode.ZONES;
	private int zoom = 1;
	private int mapCenterX;
	private int mapCenterZ;
	private int activeColorId = 1;
	private int hierarchyRowOffset = 0;
<<<<<<< HEAD
=======
	private Long selectionAnchor;
>>>>>>> origin/main
	private UUID selectedGuardId;

	public GuardTacticsInventory(ServerPlayerEntity owner) {
		super(SIZE);
		this.owner = Objects.requireNonNull(owner, "owner");
		MinecraftServer server = this.owner.getCommandSource().getServer();
		if (server == null) {
			throw new IllegalStateException("Cannot create GuardTacticsInventory without an active server");
		}
		this.tacticsState = GuardTacticsManager.getState(server);
		this.playerTactics = this.tacticsState.getOrCreate(this.owner.getUuid());
		this.mapCenterX = this.owner.getChunkPos().x;
		this.mapCenterZ = this.owner.getChunkPos().z;
		this.refresh();
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return this.owner == player;
	}

	@Override
	public boolean isValid(int slot, ItemStack stack) {
		return false;
	}

	public boolean handleClick(int slot, int button) {
		if (slot == MODE_SLOT) {
			this.mode = this.mode == ViewMode.ZONES ? ViewMode.HIERARCHY : ViewMode.ZONES;
			return true;
		}

		return this.mode == ViewMode.ZONES
			? this.handleZonesClick(slot, button)
			: this.handleHierarchyClick(slot, button);
	}

	public void refresh() {
		this.zoneSlotChunks.clear();
		this.hierarchySlotBindings.clear();
		this.ownedGuardCache.clear();
		for (int i = 0; i < this.size(); i++) {
			this.setStack(i, ItemStack.EMPTY);
		}
		for (int i = 0; i < this.size(); i++) {
			if (i >= MAP_SLOT_START && i <= MAP_SLOT_END) {
				continue;
			}
			if (i >= ZOOM_OUT_SLOT && i <= ZOOM_IN_SLOT) {
				continue;
			}
			if (i >= SLOT_TITLE && i <= SLOT_BLUE) {
				continue;
			}
			this.setStack(i, this.decorativePane(i));
		}

		List<GuardEntity> owned = this.collectOwnedGuards();
		for (GuardEntity guard : owned) {
			this.ownedGuardCache.put(guard.getUuid(), guard);
		}

		if (this.mode == ViewMode.ZONES) {
			this.populateZones();
		} else {
			this.populateHierarchy(owned);
		}
		this.populateCommonControls();
	}

	private boolean handleZonesClick(int slot, int button) {
		if (slot == ZOOM_OUT_SLOT) {
			this.zoom = Math.min(32, this.zoom + 1);
			return true;
		}
		if (slot == ZOOM_IN_SLOT) {
			this.zoom = Math.max(1, this.zoom - 1);
			return true;
		}
		if (slot == PAN_WEST_SLOT) {
			this.mapCenterX -= this.zoom;
			return true;
		}
		if (slot == PAN_NORTH_SLOT) {
			this.mapCenterZ -= this.zoom;
			return true;
		}
		if (slot == PAN_SOUTH_SLOT) {
			this.mapCenterZ += this.zoom;
			return true;
		}
		if (slot == PAN_EAST_SLOT) {
			this.mapCenterX += this.zoom;
			return true;
		}
		if (slot == SLOT_GREEN) {
			this.activeColorId = ZoneColor.GREEN.id;
			return true;
		}
		if (slot == SLOT_YELLOW) {
			this.activeColorId = ZoneColor.YELLOW.id;
			return true;
		}
		if (slot == SLOT_RED) {
			this.activeColorId = ZoneColor.RED.id;
			return true;
		}
		if (slot == SLOT_BLUE) {
			this.activeColorId = ZoneColor.BLUE.id;
			return true;
		}

		if (slot < MAP_SLOT_START || slot > MAP_SLOT_END) {
			return false;
		}

		Long chunkKey = this.zoneSlotChunks.get(slot);
		if (chunkKey == null) {
			return false;
		}
		int chunkX = ChunkPos.getPackedX(chunkKey);
		int chunkZ = ChunkPos.getPackedZ(chunkKey);
		if (button == 1) {
<<<<<<< HEAD
			this.playerTactics.setZoneColor(chunkX, chunkZ, this.activeColorId);
		} else {
			this.playerTactics.setZoneColor(chunkX, chunkZ, ZoneColor.NONE.id);
		}
=======
			this.playerTactics.setZoneColor(chunkX, chunkZ, ZoneColor.NONE.id);
			this.selectionAnchor = null;
			this.markTacticsDirty();
			this.applyZoneHomesForOwnedGuards();
			return true;
		}

		if (this.selectionAnchor == null) {
			this.selectionAnchor = chunkKey;
			return true;
		}

		int ax = ChunkPos.getPackedX(this.selectionAnchor);
		int az = ChunkPos.getPackedZ(this.selectionAnchor);
		int minX = Math.min(ax, chunkX);
		int maxX = Math.max(ax, chunkX);
		int minZ = Math.min(az, chunkZ);
		int maxZ = Math.max(az, chunkZ);
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				this.playerTactics.setZoneColor(x, z, this.activeColorId);
			}
		}
		this.selectionAnchor = null;
>>>>>>> origin/main
		this.markTacticsDirty();
		this.applyZoneHomesForOwnedGuards();
		return true;
	}

	private boolean handleHierarchyClick(int slot, int button) {
		if (slot == ZOOM_OUT_SLOT) {
			this.hierarchyRowOffset = Math.max(0, this.hierarchyRowOffset - 1);
			return true;
		}
		if (slot == ZOOM_IN_SLOT) {
			int maxOffset = Math.max(0, this.playerTactics.roleCount() - 3);
			this.hierarchyRowOffset = Math.min(maxOffset, this.hierarchyRowOffset + 1);
			return true;
		}
		if (slot == ADD_ROLE_SLOT) {
			int newRow = this.playerTactics.addRole();
			this.hierarchyRowOffset = Math.max(0, newRow - 2);
			this.markTacticsDirty();
			return true;
		}
		if (slot == PAN_WEST_SLOT && this.selectedGuardId != null) {
			this.assignGuardTo(this.selectedGuardId, Math.max(0, this.getGuardRow(this.selectedGuardId) - 1), this.getGuardColumn(this.selectedGuardId));
			this.selectedGuardId = null;
			return true;
		}
		if (slot == PAN_EAST_SLOT && this.selectedGuardId != null) {
			this.assignGuardTo(this.selectedGuardId, this.getGuardRow(this.selectedGuardId) + 1, this.getGuardColumn(this.selectedGuardId));
			this.selectedGuardId = null;
			return true;
		}
		if (slot == PAN_SOUTH_SLOT) {
			this.selectedGuardId = null;
			return true;
		}
		if (slot == SLOT_GREEN || slot == SLOT_YELLOW || slot == SLOT_RED || slot == SLOT_BLUE) {
			return false;
		}

		HierarchySlotBinding binding = this.hierarchySlotBindings.get(slot);
		if (binding == null) {
			return false;
		}
		return switch (binding.type) {
			case ROW_HEADER -> this.handleRowHeaderClick(binding, button);
			case COLUMN_ZONE -> this.handleColumnZoneClick(binding, button);
			case GUARD -> this.handleGuardClick(binding, button);
			case EMPTY_ROW_SLOT -> this.handleEmptyRowSlotClick(binding);
		};
	}

	private boolean handleRowHeaderClick(HierarchySlotBinding binding, int button) {
		this.playerTactics.ensureRoleCount(binding.row + 1);
		if (button == 1) {
			this.playerTactics.cycleRoleName(binding.row);
			this.syncRoleNameToRow(binding.row);
			this.markTacticsDirty();
			return true;
		}

		if (this.selectedGuardId != null) {
			this.assignGuardTo(this.selectedGuardId, binding.row, this.getGuardColumn(this.selectedGuardId));
			this.selectedGuardId = null;
		}
		return true;
	}

	private boolean handleColumnZoneClick(HierarchySlotBinding binding, int button) {
		int current = this.playerTactics.getRowColumnZone(binding.row, binding.column);
		ZoneColor color = ZoneColor.fromId(current);
		ZoneColor next = button == 1 ? color.previous() : color.next();
		this.playerTactics.setRowColumnZone(binding.row, binding.column, next.id);
		this.markTacticsDirty();
		this.applyZoneHomesForOwnedGuards();
		return true;
	}

	private boolean handleGuardClick(HierarchySlotBinding binding, int button) {
		UUID guardId = binding.guardId;
		if (guardId == null) {
			return false;
		}

		if (button == 1) {
			int nextColumn = (this.getGuardColumn(guardId) + 1) % 3;
			this.assignGuardTo(guardId, this.getGuardRow(guardId), nextColumn);
			return true;
		}

		if (this.selectedGuardId == null || this.selectedGuardId.equals(guardId)) {
			this.selectedGuardId = this.selectedGuardId == null ? guardId : null;
			return true;
		}

		int row = this.getGuardRow(guardId);
		int column = this.getGuardColumn(guardId);
		this.assignGuardTo(this.selectedGuardId, row, column);
		this.selectedGuardId = null;
		return true;
	}

	private boolean handleEmptyRowSlotClick(HierarchySlotBinding binding) {
		if (this.selectedGuardId == null) {
			return false;
		}
		this.assignGuardTo(this.selectedGuardId, binding.row, binding.column);
		this.selectedGuardId = null;
		return true;
	}

	private void populateZones() {
		ZoneColor activeColor = ZoneColor.fromId(this.activeColorId);
		ChunkPos center = new ChunkPos(this.mapCenterX, this.mapCenterZ);
		for (int slot = MAP_SLOT_START; slot <= MAP_SLOT_END; slot++) {
			int index = slot - MAP_SLOT_START;
			int row = index / 9;
			int col = index % 9;
			int chunkX = center.x + (col - 4) * this.zoom;
			int chunkZ = center.z + (row - 1) * this.zoom;
			long key = ChunkPos.toLong(chunkX, chunkZ);
			this.zoneSlotChunks.put(slot, key);

			ZoneColor color = ZoneColor.fromId(this.playerTactics.getZoneColor(chunkX, chunkZ));
<<<<<<< HEAD
			String title = "Chunk " + chunkX + ", " + chunkZ;
=======
			boolean isAnchor = this.selectionAnchor != null && this.selectionAnchor == key;
			String title = "Chunk " + chunkX + ", " + chunkZ + (isAnchor ? " [Anchor]" : "");
>>>>>>> origin/main
			this.setStack(slot, this.card(
				color.item,
				title,
				color.formatting,
				"Zone: " + color.label,
<<<<<<< HEAD
				"Hierarchy: " + this.roleSummaryForColor(color.id),
				"Left: Clear this chunk",
				"Right: Paint with " + activeColor.label
=======
				"Left: Set anchor / fill rectangle",
				"Right: Clear this chunk"
>>>>>>> origin/main
			));
		}

		this.setStack(SLOT_TITLE, this.card(
			Items.FILLED_MAP,
			"Zones",
			Formatting.AQUA,
<<<<<<< HEAD
			"Top-down rendered chunk map",
=======
			"Top-down chunk map",
>>>>>>> origin/main
			"Paint: " + activeColor.label,
			"Zoom: " + this.zoom + "x  Center: " + this.mapCenterX + ", " + this.mapCenterZ,
			"Assigned chunks: " + this.playerTactics.getColoredChunkCount()
		));
		this.setStack(SLOT_GREEN, this.paletteCard(ZoneColor.GREEN, activeColor.id == ZoneColor.GREEN.id));
		this.setStack(SLOT_YELLOW, this.paletteCard(ZoneColor.YELLOW, activeColor.id == ZoneColor.YELLOW.id));
		this.setStack(SLOT_RED, this.paletteCard(ZoneColor.RED, activeColor.id == ZoneColor.RED.id));
		this.setStack(SLOT_BLUE, this.paletteCard(ZoneColor.BLUE, activeColor.id == ZoneColor.BLUE.id));
	}

	private void populateHierarchy(List<GuardEntity> ownedGuards) {
		ownedGuards.sort(Comparator
			.comparingInt(GuardEntity::getHierarchyRow)
			.thenComparingInt(GuardEntity::getHierarchyColumn)
			.thenComparing(Comparator.comparingInt(GuardEntity::getLevel).reversed())
			.thenComparing(guard -> guard.getUuid().toString()));

		Map<Integer, List<GuardEntity>> byRow = new HashMap<>();
		for (GuardEntity guard : ownedGuards) {
			this.playerTactics.ensureRoleCount(guard.getHierarchyRow() + 1);
			byRow.computeIfAbsent(guard.getHierarchyRow(), ignored -> new ArrayList<>()).add(guard);
		}
		this.playerTactics.ensureRoleCount(this.hierarchyRowOffset + 3);
		int maxOffset = Math.max(0, this.playerTactics.roleCount() - 3);
		this.hierarchyRowOffset = Math.min(this.hierarchyRowOffset, maxOffset);

		for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
			int absoluteRow = this.hierarchyRowOffset + rowIndex;
			int baseSlot = MAP_SLOT_START + rowIndex * 9;
			String roleName = this.playerTactics.getRoleName(absoluteRow);
			int rowGuardCount = byRow.getOrDefault(absoluteRow, List.of()).size();
			this.setStack(baseSlot, this.card(
				Items.BOOK,
				"Row " + (absoluteRow + 1) + ": " + roleName,
				Formatting.LIGHT_PURPLE,
				"Guards: " + rowGuardCount,
				"Left: Place selected guard in this row",
				"Right: Cycle row role name",
				"Custom: /guards hierarchy rename " + (absoluteRow + 1) + " <name>"
			));
			this.hierarchySlotBindings.put(baseSlot, HierarchySlotBinding.rowHeader(absoluteRow));

			for (int column = 0; column < 3; column++) {
				int zoneSlot = baseSlot + 1 + column;
				ZoneColor zone = ZoneColor.fromId(this.playerTactics.getRowColumnZone(absoluteRow, column));
				this.setStack(zoneSlot, this.card(
					zone.item,
					"Col " + (column + 1) + " Zone",
					zone.formatting,
					"Zone: " + zone.label,
					"Left/Right: Cycle zone color"
				));
				this.hierarchySlotBindings.put(zoneSlot, HierarchySlotBinding.columnZone(absoluteRow, column));
			}

			List<GuardEntity> rowGuards = byRow.getOrDefault(absoluteRow, List.of());
			for (int i = 0; i < 5; i++) {
				int slot = baseSlot + 4 + i;
				int targetColumn = Math.min(2, (i * 3) / 5);
				if (i < rowGuards.size()) {
					GuardEntity guard = rowGuards.get(i);
					boolean selected = guard.getUuid().equals(this.selectedGuardId);
					this.setStack(slot, this.guardCard(guard, selected));
					this.hierarchySlotBindings.put(slot, HierarchySlotBinding.guard(absoluteRow, guard.getHierarchyColumn(), guard.getUuid()));
				} else {
					this.setStack(slot, this.card(
						Items.GRAY_STAINED_GLASS_PANE,
						"Empty Slot",
						Formatting.DARK_GRAY,
						this.selectedGuardId == null ? "Select a guard to drag here" : "Drop selected guard here"
					));
					this.hierarchySlotBindings.put(slot, HierarchySlotBinding.emptyRow(absoluteRow, targetColumn));
				}
			}
		}

		this.setStack(SLOT_TITLE, this.card(
			Items.PLAYER_HEAD,
			"Hierarchy",
			Formatting.GOLD,
			"Player: " + this.owner.getName().getString(),
			"Higher rows stay closer to you",
			this.selectedGuardId == null ? "Move: click guard, then click row/slot" : "Moving guard: click destination row/slot"
		));
		this.setStack(SLOT_GREEN, this.card(Items.GREEN_STAINED_GLASS_PANE, "Green", Formatting.GREEN, "Patrol-friendly zone"));
		this.setStack(SLOT_YELLOW, this.card(Items.YELLOW_STAINED_GLASS_PANE, "Yellow", Formatting.YELLOW, "Caution zone"));
		this.setStack(SLOT_RED, this.card(Items.RED_STAINED_GLASS_PANE, "Red", Formatting.RED, "High-alert zone"));
		this.setStack(SLOT_BLUE, this.card(Items.BLUE_STAINED_GLASS_PANE, "Blue", Formatting.AQUA, "Flexible support zone"));
	}

	private ItemStack guardCard(GuardEntity guard, boolean selected) {
		String armorHead = this.itemName(guard.getEquippedStack(EquipmentSlot.HEAD));
		String armorChest = this.itemName(guard.getEquippedStack(EquipmentSlot.CHEST));
		String armorLegs = this.itemName(guard.getEquippedStack(EquipmentSlot.LEGS));
		String armorFeet = this.itemName(guard.getEquippedStack(EquipmentSlot.FEET));
		String weapon = this.itemName(guard.getMainHandStack()) + " / " + this.itemName(guard.getOffHandStack());
		Formatting color = selected ? Formatting.AQUA : Formatting.GOLD;
		return this.card(
			Items.PLAYER_HEAD,
			guard.getHierarchyRole() + " Lv " + guard.getLevel(),
			color,
			"Row " + (guard.getHierarchyRow() + 1) + "  Col " + (guard.getHierarchyColumn() + 1),
			"H: " + armorHead,
			"C: " + armorChest,
			"L: " + armorLegs,
			"B: " + armorFeet,
			"W: " + weapon,
			"Left: Pick/move guard",
			"Right: Cycle column"
		);
	}

	private ItemStack paletteCard(ZoneColor color, boolean active) {
		return this.card(
			color.item,
			color.label + (active ? " (Active)" : ""),
			color.formatting,
			"Click to paint with this color"
		);
	}

	private void populateCommonControls() {
		if (this.mode == ViewMode.ZONES) {
			this.setStack(ZOOM_OUT_SLOT, this.card(Items.ARROW, "Zoom Out", Formatting.GRAY, "Increase map scale"));
			this.setStack(ZOOM_IN_SLOT, this.card(Items.SPECTRAL_ARROW, "Zoom In", Formatting.GRAY, "Decrease map scale"));
			this.setStack(PAN_WEST_SLOT, this.card(Items.COMPASS, "Pan West", Formatting.GRAY));
			this.setStack(PAN_NORTH_SLOT, this.card(Items.COMPASS, "Pan North", Formatting.GRAY));
			this.setStack(PAN_SOUTH_SLOT, this.card(Items.COMPASS, "Pan South", Formatting.GRAY));
			this.setStack(PAN_EAST_SLOT, this.card(Items.COMPASS, "Pan East", Formatting.GRAY));
<<<<<<< HEAD
			this.setStack(ADD_ROLE_SLOT, this.card(Items.NAME_TAG, "Selection Paint", Formatting.AQUA, "Client map supports drag-select + right-click paint"));
=======
			this.setStack(ADD_ROLE_SLOT, this.card(Items.NAME_TAG, "Region Fill", Formatting.AQUA, "Left-click first chunk to set anchor", "then left-click second chunk to fill area"));
>>>>>>> origin/main
			this.setStack(INFO_SLOT, this.card(
				Items.WRITABLE_BOOK,
				"Zone Controls",
				Formatting.YELLOW,
<<<<<<< HEAD
				"Left click chunk: clear",
				"Right click chunk: paint active color",
				"Use color cards to change active paint"
=======
				"Left click: set anchor / fill rectangle",
				"Right click: clear current chunk",
				"Use color cards to set paint color"
>>>>>>> origin/main
			));
		} else {
			int roleCount = this.playerTactics.roleCount();
			this.setStack(ZOOM_OUT_SLOT, this.card(Items.ARROW, "Rows Up", Formatting.GRAY, "Scroll hierarchy rows up"));
			this.setStack(ZOOM_IN_SLOT, this.card(Items.SPECTRAL_ARROW, "Rows Down", Formatting.GRAY, "Scroll hierarchy rows down"));
			this.setStack(PAN_WEST_SLOT, this.card(Items.HONEYCOMB, "Move Up", Formatting.GRAY, "With guard selected: move to higher row"));
			this.setStack(PAN_NORTH_SLOT, this.card(Items.HONEY_BOTTLE, "Roles", Formatting.GRAY, "Right-click row header to cycle role", "Custom rename: /guards hierarchy rename <row> <name>"));
			this.setStack(PAN_SOUTH_SLOT, this.card(Items.BARRIER, "Clear Drag", Formatting.GRAY, "Drop current guard selection"));
			this.setStack(PAN_EAST_SLOT, this.card(Items.HONEYCOMB, "Move Down", Formatting.GRAY, "With guard selected: move to lower row"));
			this.setStack(ADD_ROLE_SLOT, this.card(Items.NAME_TAG, "Add Role", Formatting.AQUA, "Current roles: " + roleCount));
			this.setStack(INFO_SLOT, this.card(
				Items.WRITABLE_BOOK,
				"Hierarchy Controls",
				Formatting.YELLOW,
				"Move: click guard -> click target row/slot",
				"Row columns map directly to zone colors",
				"Higher rows are near the frontline",
				"Use command for custom role names"
			));
		}

		this.setStack(MODE_SLOT, this.card(
			this.mode == ViewMode.ZONES ? Items.RECOVERY_COMPASS : Items.COMPASS,
			this.mode == ViewMode.ZONES ? "Switch: Hierarchy" : "Switch: Zones",
			Formatting.AQUA,
			"Click to switch tactical view"
		));
	}

	private void assignGuardTo(UUID guardId, int row, int column) {
		GuardEntity guard = this.ownedGuardCache.get(guardId);
		if (guard == null) {
			return;
		}
		int targetRow = Math.max(0, Math.min(31, row));
		this.playerTactics.ensureRoleCount(targetRow + 1);
		guard.setHierarchyRow(targetRow);
		guard.setHierarchyColumn(column);
		guard.setHierarchyRole(this.playerTactics.getRoleName(targetRow));
		this.applyZoneHome(guard);
		this.markTacticsDirty();
	}

	private void syncRoleNameToRow(int row) {
		String roleName = this.playerTactics.getRoleName(row);
		for (GuardEntity guard : this.ownedGuardCache.values()) {
			if (guard.getHierarchyRow() == row) {
				guard.setHierarchyRole(roleName);
			}
		}
	}

	private int getGuardRow(UUID guardId) {
		GuardEntity guard = this.ownedGuardCache.get(guardId);
		return guard == null ? 0 : guard.getHierarchyRow();
	}

	private int getGuardColumn(UUID guardId) {
		GuardEntity guard = this.ownedGuardCache.get(guardId);
		return guard == null ? 1 : guard.getHierarchyColumn();
	}

	private void applyZoneHomesForOwnedGuards() {
		Map<Integer, Set<Long>> zoneChunkCache = new HashMap<>();
		for (GuardEntity guard : this.ownedGuardCache.values()) {
			this.applyZoneHome(guard, zoneChunkCache);
		}
	}

	private void applyZoneHome(GuardEntity guard) {
		this.applyZoneHome(guard, null);
	}

	private void applyZoneHome(GuardEntity guard, Map<Integer, Set<Long>> zoneChunkCache) {
		int colorId = this.playerTactics.getRowColumnZone(guard.getHierarchyRow(), guard.getHierarchyColumn());
		if (colorId <= 0) {
			return;
		}
		Set<Long> matchingChunks = zoneChunkCache == null
			? this.playerTactics.getZoneChunksByColor(colorId)
			: this.getZoneChunksForColor(colorId, zoneChunkCache);
		if (matchingChunks.isEmpty()) {
			return;
		}

		int ownerChunkX = this.owner.getChunkPos().x;
		int ownerChunkZ = this.owner.getChunkPos().z;
		long bestChunk = 0L;
		double bestDistance = Double.MAX_VALUE;
		for (long packed : matchingChunks) {
			int x = ChunkPos.getPackedX(packed);
			int z = ChunkPos.getPackedZ(packed);
			double dx = x - ownerChunkX;
			double dz = z - ownerChunkZ;
			double distanceSq = dx * dx + dz * dz;
			if (distanceSq < bestDistance) {
				bestDistance = distanceSq;
				bestChunk = packed;
			}
		}

		int bestX = ChunkPos.getPackedX(bestChunk);
		int bestZ = ChunkPos.getPackedZ(bestChunk);
		if (!(guard.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}
		BlockPos zoneCenter = new BlockPos(bestX * 16 + 8, guard.getBlockY(), bestZ * 16 + 8);
		BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, zoneCenter);
		int radius = Math.max(16, 32 + guard.getHierarchyRow() * 4);
		guard.setHome(top, radius);
	}

	private Set<Long> getZoneChunksForColor(int colorId, Map<Integer, Set<Long>> zoneChunkCache) {
		Set<Long> cached = zoneChunkCache.get(colorId);
		if (cached != null) {
			return cached;
		}
		Set<Long> loaded = this.playerTactics.getZoneChunksByColor(colorId);
		zoneChunkCache.put(colorId, loaded);
		return loaded;
	}

<<<<<<< HEAD
	private String roleSummaryForColor(int colorId) {
		if (colorId <= 0) {
			return "none";
		}
		List<String> matches = new ArrayList<>();
		int extra = 0;
		int total = this.playerTactics.roleCount();
		for (int row = 0; row < total; row++) {
			StringBuilder columns = new StringBuilder();
			for (int column = 0; column < 3; column++) {
				if (this.playerTactics.getRowColumnZone(row, column) != colorId) {
					continue;
				}
				if (!columns.isEmpty()) {
					columns.append('/');
				}
				columns.append(column + 1);
			}
			if (columns.isEmpty()) {
				continue;
			}
			if (matches.size() < 3) {
				matches.add(this.playerTactics.getRoleName(row) + " (C" + columns + ")");
			} else {
				extra++;
			}
		}
		if (matches.isEmpty()) {
			return "none";
		}
		String summary = String.join(", ", matches);
		return extra > 0 ? summary + " +" + extra : summary;
	}

=======
>>>>>>> origin/main
	private List<GuardEntity> collectOwnedGuards() {
		MinecraftServer server = this.owner.getCommandSource().getServer();
		if (server == null) {
			return List.of();
		}
		return GuardOwnershipIndex.getOwnedGuards(server, this.owner.getUuid());
	}

	private ItemStack decorativePane(int slot) {
		Item pane = (slot % 2 == 0) ? Items.BLACK_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE;
		ItemStack stack = new ItemStack(pane);
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
		return stack;
	}

	private ItemStack card(Item item, String title, Formatting titleColor, String... lines) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title).formatted(titleColor, Formatting.BOLD));
		if (lines.length > 0) {
			List<Text> loreLines = new ArrayList<>();
			for (String line : lines) {
				loreLines.add(Text.literal(line).formatted(Formatting.GRAY));
			}
			stack.set(DataComponentTypes.LORE, new LoreComponent(loreLines));
		}
		return stack;
	}

	private void markTacticsDirty() {
		this.tacticsState.markDirty();
	}

	private String itemName(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "None";
		}
		return stack.getName().getString();
	}

	private enum ViewMode {
		ZONES,
		HIERARCHY
	}

	private enum ZoneColor {
		NONE(0, "None", Formatting.DARK_GRAY, Items.LIGHT_GRAY_STAINED_GLASS_PANE),
		GREEN(1, "Green", Formatting.GREEN, Items.GREEN_STAINED_GLASS_PANE),
		YELLOW(2, "Yellow", Formatting.YELLOW, Items.YELLOW_STAINED_GLASS_PANE),
		RED(3, "Red", Formatting.RED, Items.RED_STAINED_GLASS_PANE),
		BLUE(4, "Blue", Formatting.AQUA, Items.BLUE_STAINED_GLASS_PANE);

		private final int id;
		private final String label;
		private final Formatting formatting;
		private final Item item;

		ZoneColor(int id, String label, Formatting formatting, Item item) {
			this.id = id;
			this.label = label;
			this.formatting = formatting;
			this.item = item;
		}

		private ZoneColor next() {
			ZoneColor[] values = values();
			return values[(this.ordinal() + 1) % values.length];
		}

		private ZoneColor previous() {
			ZoneColor[] values = values();
			return values[(this.ordinal() - 1 + values.length) % values.length];
		}

		private static ZoneColor fromId(int id) {
			for (ZoneColor color : values()) {
				if (color.id == id) {
					return color;
				}
			}
			return NONE;
		}
	}

	private enum HierarchySlotType {
		ROW_HEADER,
		COLUMN_ZONE,
		GUARD,
		EMPTY_ROW_SLOT
	}

	private record HierarchySlotBinding(HierarchySlotType type, int row, int column, UUID guardId) {
		private static HierarchySlotBinding rowHeader(int row) {
			return new HierarchySlotBinding(HierarchySlotType.ROW_HEADER, row, 0, null);
		}

		private static HierarchySlotBinding columnZone(int row, int column) {
			return new HierarchySlotBinding(HierarchySlotType.COLUMN_ZONE, row, column, null);
		}

		private static HierarchySlotBinding guard(int row, int column, UUID guardId) {
			return new HierarchySlotBinding(HierarchySlotType.GUARD, row, column, guardId);
		}

		private static HierarchySlotBinding emptyRow(int row, int column) {
			return new HierarchySlotBinding(HierarchySlotType.EMPTY_ROW_SLOT, row, column, null);
		}
	}
}
