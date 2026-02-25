package com.guardvillagers.tactics;

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
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GuardTacticsInventory extends SimpleInventory {
	private static final int SIZE = 54;
	private static final int MAP_SLOT_START = 18;
	private static final int MAP_SLOT_END = 44;
	private static final int ZOOM_OUT_SLOT = 45;
	private static final int INFO_SLOT = 49;
	private static final int MODE_SLOT = 50;
	private static final int ZOOM_IN_SLOT = 53;

	private final ServerPlayerEntity owner;
	private final Map<Integer, Long> zoneSlotChunks = new HashMap<>();
	private final Map<Long, ZoneColor> zoneColors = new HashMap<>();
	private final Map<UUID, Integer> guardColumns = new HashMap<>();
	private final Map<Integer, ZoneColor> columnZones = new HashMap<>();
	private final Map<Integer, UUID> hierarchySlots = new HashMap<>();
	private ViewMode mode = ViewMode.ZONES;
	private int zoom = 1;

	public GuardTacticsInventory(ServerPlayerEntity owner) {
		super(SIZE);
		this.owner = owner;
		this.columnZones.put(0, ZoneColor.GREEN);
		this.columnZones.put(1, ZoneColor.YELLOW);
		this.columnZones.put(2, ZoneColor.RED);
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
		if (slot == ZOOM_OUT_SLOT && this.mode == ViewMode.ZONES) {
			this.zoom = Math.min(16, this.zoom + 1);
			return true;
		}
		if (slot == ZOOM_IN_SLOT && this.mode == ViewMode.ZONES) {
			this.zoom = Math.max(1, this.zoom - 1);
			return true;
		}
		if (slot < MAP_SLOT_START || slot > MAP_SLOT_END) {
			return false;
		}

		if (this.mode == ViewMode.ZONES) {
			Long key = this.zoneSlotChunks.get(slot);
			if (key == null) {
				return false;
			}
			ZoneColor current = this.zoneColors.getOrDefault(key, ZoneColor.NONE);
			ZoneColor next = button == 1 ? current.previous() : current.next();
			if (next == ZoneColor.NONE) {
				this.zoneColors.remove(key);
			} else {
				this.zoneColors.put(key, next);
			}
			return true;
		}

		UUID guardId = this.hierarchySlots.get(slot);
		if (guardId == null) {
			return false;
		}
		int column = this.guardColumns.getOrDefault(guardId, 0);
		if (button == 1) {
			ZoneColor zone = this.columnZones.getOrDefault(column, ZoneColor.GREEN).next();
			this.columnZones.put(column, zone == ZoneColor.NONE ? ZoneColor.GREEN : zone);
		} else {
			this.guardColumns.put(guardId, (column + 1) % 3);
		}
		return true;
	}

	public void refresh() {
		this.zoneSlotChunks.clear();
		this.hierarchySlots.clear();
		for (int i = 0; i < this.size(); i++) {
			this.setStack(i, ItemStack.EMPTY);
		}
		for (int i = 0; i < this.size(); i++) {
			if (i >= MAP_SLOT_START && i <= MAP_SLOT_END) {
				continue;
			}
			if (i == ZOOM_OUT_SLOT || i == INFO_SLOT || i == MODE_SLOT || i == ZOOM_IN_SLOT) {
				continue;
			}
			this.setStack(i, this.decorativePane(i));
		}

		if (this.mode == ViewMode.ZONES) {
			this.populateZoneMap();
		} else {
			this.populateHierarchy();
		}
		this.populateControls();
	}

	private void populateZoneMap() {
		ChunkPos center = this.owner.getChunkPos();
		for (int slot = MAP_SLOT_START; slot <= MAP_SLOT_END; slot++) {
			int index = slot - MAP_SLOT_START;
			int row = index / 9;
			int col = index % 9;
			int chunkX = center.x + (col - 4) * this.zoom;
			int chunkZ = center.z + (row - 1) * this.zoom;
			long key = ChunkPos.toLong(chunkX, chunkZ);
			this.zoneSlotChunks.put(slot, key);
			ZoneColor color = this.zoneColors.getOrDefault(key, ZoneColor.NONE);
			this.setStack(slot, this.card(
				color.item,
				"Chunk " + chunkX + ", " + chunkZ,
				color.formatting,
				"Zone: " + color.label,
				"Left: Next color",
				"Right: Previous color"
			));
		}

		int colored = this.zoneColors.size();
		this.setStack(4, this.card(
			Items.FILLED_MAP,
			"Zones",
			Formatting.AQUA,
			"Top-down chunk selector",
			"Zoom: " + this.zoom + "x",
			"Assigned chunks: " + colored
		));
		this.setStack(5, this.card(Items.GREEN_STAINED_GLASS_PANE, "Green", Formatting.GREEN, "Friendly patrol"));
		this.setStack(6, this.card(Items.YELLOW_STAINED_GLASS_PANE, "Yellow", Formatting.YELLOW, "Caution patrol"));
		this.setStack(7, this.card(Items.RED_STAINED_GLASS_PANE, "Red", Formatting.RED, "High alert"));
	}

	private void populateHierarchy() {
		List<GuardEntity> guards = this.collectOwnedGuards();
		guards.sort(Comparator
			.comparingInt(GuardEntity::getLevel).reversed()
			.thenComparingDouble(guard -> guard.squaredDistanceTo(this.owner))
			.thenComparing(guard -> guard.getUuid().toString()));

		for (int i = 0; i < Math.min(27, guards.size()); i++) {
			int slot = MAP_SLOT_START + i;
			GuardEntity guard = guards.get(i);
			int rankRow = i / 9;
			int column = this.guardColumns.computeIfAbsent(guard.getUuid(), ignored -> Math.min(2, rankRow));
			ZoneColor zone = this.columnZones.getOrDefault(column, ZoneColor.GREEN);
			String armor = this.itemName(guard.getEquippedStack(EquipmentSlot.HEAD)) + " | "
				+ this.itemName(guard.getEquippedStack(EquipmentSlot.CHEST)) + " | "
				+ this.itemName(guard.getEquippedStack(EquipmentSlot.LEGS)) + " | "
				+ this.itemName(guard.getEquippedStack(EquipmentSlot.FEET));
			String weapon = this.itemName(guard.getMainHandStack()) + " + " + this.itemName(guard.getOffHandStack());
			this.setStack(slot, this.card(
				Items.PLAYER_HEAD,
				"Guard Lv " + guard.getLevel(),
				Formatting.GOLD,
				"Row: " + (rankRow + 1) + "  Column: " + (column + 1),
				"Zone: " + zone.label,
				"Armor: " + armor,
				"Weapon: " + weapon,
				"Left: Move guard column",
				"Right: Cycle column zone"
			));
			this.hierarchySlots.put(slot, guard.getUuid());
		}

		this.setStack(4, this.card(
			Items.BOOK,
			"Hierarchy",
			Formatting.LIGHT_PURPLE,
			"Player at top authority",
			"Higher rows stay closer",
			"Rows split into 3 columns"
		));
		this.setStack(5, this.card(Items.GREEN_BANNER, "Column 1", Formatting.GREEN, "Zone: " + this.columnZones.getOrDefault(0, ZoneColor.GREEN).label));
		this.setStack(6, this.card(Items.YELLOW_BANNER, "Column 2", Formatting.YELLOW, "Zone: " + this.columnZones.getOrDefault(1, ZoneColor.YELLOW).label));
		this.setStack(7, this.card(Items.RED_BANNER, "Column 3", Formatting.RED, "Zone: " + this.columnZones.getOrDefault(2, ZoneColor.RED).label));
	}

	private List<GuardEntity> collectOwnedGuards() {
		MinecraftServer server = this.owner.getCommandSource().getServer();
		if (server == null) {
			return List.of();
		}
		List<GuardEntity> guards = new ArrayList<>();
		for (ServerWorld world : server.getWorlds()) {
			guards.addAll(world.getEntitiesByClass(
				GuardEntity.class,
				this.owner.getBoundingBox().expand(2048.0D),
				guard -> guard.isOwnedBy(this.owner.getUuid())
			));
		}
		return guards;
	}

	private void populateControls() {
		this.setStack(ZOOM_OUT_SLOT, this.card(
			Items.ARROW,
			"Zoom Out",
			Formatting.GRAY,
			this.mode == ViewMode.ZONES ? "Current zoom: " + this.zoom + "x" : "Only in Zones mode"
		));
		this.setStack(ZOOM_IN_SLOT, this.card(
			Items.SPECTRAL_ARROW,
			"Zoom In",
			Formatting.GRAY,
			this.mode == ViewMode.ZONES ? "Current zoom: " + this.zoom + "x" : "Only in Zones mode"
		));
		this.setStack(INFO_SLOT, this.card(
			Items.COMPASS,
			"Tactics",
			Formatting.GOLD,
			this.mode == ViewMode.ZONES ? "Mode: Zones" : "Mode: Hierarchy",
			"Clean minimal tactical controls"
		));
		this.setStack(MODE_SLOT, this.card(
			Items.WRITABLE_BOOK,
			this.mode == ViewMode.ZONES ? "Hierarchy" : "Zones",
			Formatting.AQUA,
			"Click to switch mode"
		));
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
		NONE("None", Formatting.DARK_GRAY, Items.LIGHT_GRAY_STAINED_GLASS_PANE),
		GREEN("Green", Formatting.GREEN, Items.GREEN_STAINED_GLASS_PANE),
		YELLOW("Yellow", Formatting.YELLOW, Items.YELLOW_STAINED_GLASS_PANE),
		RED("Red", Formatting.RED, Items.RED_STAINED_GLASS_PANE),
		BLUE("Blue", Formatting.AQUA, Items.BLUE_STAINED_GLASS_PANE);

		private final String label;
		private final Formatting formatting;
		private final Item item;

		ZoneColor(String label, Formatting formatting, Item item) {
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
	}
}
