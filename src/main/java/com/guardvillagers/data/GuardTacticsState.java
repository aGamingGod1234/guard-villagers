package com.guardvillagers.data;

import com.guardvillagers.entity.FormationType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.util.Uuids;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

public final class GuardTacticsState extends PersistentState {
	private static final Codec<Map<String, Integer>> STRING_INT_MAP_CODEC = Codec.unboundedMap(Codec.STRING, Codec.intRange(0, 4));
	private static final Codec<List<String>> ROLE_LIST_CODEC = Codec.STRING.listOf();
	private static final Codec<Map<UUID, PlayerTactics>> TACTICS_MAP_CODEC = Codec.unboundedMap(Uuids.STRING_CODEC, PlayerTactics.CODEC);

	public static final Codec<GuardTacticsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		TACTICS_MAP_CODEC.optionalFieldOf("players", Map.of()).forGetter(GuardTacticsState::entriesForCodec)
	).apply(instance, GuardTacticsState::new));

	public static final PersistentStateType<GuardTacticsState> TYPE = new PersistentStateType<>(
		"guardvillagers_tactics",
		GuardTacticsState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<UUID, PlayerTactics> entries;

	public GuardTacticsState() {
		this(Map.of());
	}

	private GuardTacticsState(Map<UUID, PlayerTactics> entries) {
		this.entries = new HashMap<>();
		for (Map.Entry<UUID, PlayerTactics> entry : entries.entrySet()) {
			this.entries.put(entry.getKey(), entry.getValue().copy());
		}
	}

	public PlayerTactics getOrCreate(UUID ownerId) {
		return this.entries.computeIfAbsent(ownerId, ignored -> {
			this.markDirty();
			return new PlayerTactics();
		});
	}

	private Map<UUID, PlayerTactics> entriesForCodec() {
		return Collections.unmodifiableMap(this.entries);
	}

	public static final class PlayerTactics {
		private static final int MAX_COLOR_ID = 4;
		private static final int DEFAULT_FORMATION_ID = FormationType.FOLLOW.getId();
		private static final int MIN_ROW_INDEX = 0;
		private static final int MAX_ROW_INDEX = 31;
		private static final int MIN_COLUMN_INDEX = 0;
		private static final int MAX_COLUMN_INDEX = 2;
		private static final int MAX_ROLE_NAME_LENGTH = 24;
		private static final int MAX_ROLE_COUNT = MAX_ROW_INDEX + 1;
		private static final List<String> DEFAULT_ROLES = List.of("Vanguard", "Core", "Reserve");
		private static final List<String> ROLE_NAME_CYCLE = List.of(
			"Vanguard",
			"Core",
			"Reserve",
			"Skirmisher",
			"Sentinel",
			"Scout",
			"Guard Wing",
			"Spearhead",
			"Support",
			"Shield Wall"
		);

		public static final Codec<PlayerTactics> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			STRING_INT_MAP_CODEC.optionalFieldOf("zone_colors", Map.of()).forGetter(PlayerTactics::zoneColorsForCodec),
			STRING_INT_MAP_CODEC.optionalFieldOf("row_column_zones", Map.of()).forGetter(PlayerTactics::rowColumnZonesForCodec),
			ROLE_LIST_CODEC.optionalFieldOf("roles", DEFAULT_ROLES).forGetter(PlayerTactics::roleNamesForCodec),
			Codec.INT.optionalFieldOf("preferred_formation", DEFAULT_FORMATION_ID).forGetter(PlayerTactics::preferredFormationIdForCodec)
		).apply(instance, PlayerTactics::new));

		private final Map<String, Integer> zoneColors;
		private final Map<String, Integer> rowColumnZones;
		private final List<String> roleNames;
		private int preferredFormationId;

		public PlayerTactics() {
			this(Map.of(), Map.of(), DEFAULT_ROLES, DEFAULT_FORMATION_ID);
		}

		private PlayerTactics(Map<String, Integer> zoneColors, Map<String, Integer> rowColumnZones, List<String> roleNames, int preferredFormationId) {
			this.zoneColors = new HashMap<>();
			for (Map.Entry<String, Integer> entry : zoneColors.entrySet()) {
				Integer colorId = entry.getValue();
				if (colorId == null || !isValidColorId(colorId)) {
					continue;
				}
				OptionalLong parsedChunk = tryParseChunkKey(entry.getKey());
				if (parsedChunk.isEmpty()) {
					continue;
				}
				long packed = parsedChunk.getAsLong();
				int chunkX = net.minecraft.util.math.ChunkPos.getPackedX(packed);
				int chunkZ = net.minecraft.util.math.ChunkPos.getPackedZ(packed);
				this.zoneColors.put(toChunkKey(chunkX, chunkZ), colorId);
			}
			this.rowColumnZones = new HashMap<>();
			for (Map.Entry<String, Integer> entry : rowColumnZones.entrySet()) {
				Integer colorId = entry.getValue();
				if (colorId == null || !isValidColorId(colorId)) {
					continue;
				}
				int[] rowColumn = parseRowColumnKey(entry.getKey());
				if (rowColumn == null) {
					continue;
				}
				this.rowColumnZones.put(toRowColumnKey(rowColumn[0], rowColumn[1]), colorId);
			}
			this.roleNames = new ArrayList<>();
			for (String role : roleNames) {
				String sanitized = sanitizeRoleName(role);
				if (!sanitized.isEmpty()) {
					this.roleNames.add(sanitized);
					if (this.roleNames.size() >= MAX_ROLE_COUNT) {
						break;
					}
				}
			}
			if (this.roleNames.isEmpty()) {
				this.roleNames.addAll(DEFAULT_ROLES);
			}
			this.preferredFormationId = normalizeFormationId(preferredFormationId);
		}

		public PlayerTactics copy() {
			return new PlayerTactics(this.zoneColors, this.rowColumnZones, this.roleNames, this.preferredFormationId);
		}

		public FormationType getPreferredFormation() {
			return FormationType.fromId(this.preferredFormationId);
		}

		public boolean setPreferredFormation(FormationType formationType) {
			FormationType resolved = formationType == null ? FormationType.FOLLOW : formationType;
			int nextId = normalizeFormationId(resolved.getId());
			if (nextId == this.preferredFormationId) {
				return false;
			}
			this.preferredFormationId = nextId;
			return true;
		}

		public int getZoneColor(int chunkX, int chunkZ) {
			return this.zoneColors.getOrDefault(toChunkKey(chunkX, chunkZ), 0);
		}

		public void setZoneColor(int chunkX, int chunkZ, int colorId) {
			String key = toChunkKey(chunkX, chunkZ);
			int normalizedColor = normalizeColorId(colorId);
			if (normalizedColor <= 0) {
				this.zoneColors.remove(key);
			} else {
				this.zoneColors.put(key, normalizedColor);
			}
		}

		public Set<Long> getZoneChunksByColor(int colorId) {
			Set<Long> chunks = new LinkedHashSet<>();
			if (!isValidColorId(colorId)) {
				return chunks;
			}
			for (Map.Entry<String, Integer> entry : this.zoneColors.entrySet()) {
				if (entry.getValue() == colorId) {
					OptionalLong parsedChunk = tryParseChunkKey(entry.getKey());
					parsedChunk.ifPresent(chunks::add);
				}
			}
			return chunks;
		}

		public int getColoredChunkCount() {
			return this.zoneColors.size();
		}

		public int getRowColumnZone(int row, int column) {
			int normalizedRow = normalizeRow(row);
			int normalizedColumn = normalizeColumn(column);
			return this.rowColumnZones.getOrDefault(toRowColumnKey(normalizedRow, normalizedColumn), defaultZoneForColumn(normalizedColumn));
		}

		public void setRowColumnZone(int row, int column, int colorId) {
			int normalizedRow = normalizeRow(row);
			int normalizedColumn = normalizeColumn(column);
			String key = toRowColumnKey(normalizedRow, normalizedColumn);
			int normalizedColor = normalizeColorId(colorId);
			if (normalizedColor <= 0) {
				this.rowColumnZones.remove(key);
			} else {
				this.rowColumnZones.put(key, normalizedColor);
			}
		}

		public int roleCount() {
			return this.roleNames.size();
		}

		public String getRoleName(int row) {
			int normalizedRow = normalizeRow(row);
			this.ensureRoleCount(normalizedRow + 1);
			return this.roleNames.get(normalizedRow);
		}

		public void setRoleName(int row, String roleName) {
			if (row < 0) {
				return;
			}
			int normalizedRow = normalizeRow(row);
			this.ensureRoleCount(normalizedRow + 1);
			this.roleNames.set(normalizedRow, sanitizeRoleName(roleName));
		}

		public int addRole() {
			if (this.roleNames.size() >= MAX_ROLE_COUNT) {
				return MAX_ROLE_COUNT - 1;
			}
			String roleName = "Role " + (this.roleNames.size() + 1);
			this.roleNames.add(roleName);
			return this.roleNames.size() - 1;
		}

		public int cycleRoleName(int row) {
			if (row < 0) {
				return -1;
			}
			int normalizedRow = normalizeRow(row);
			this.ensureRoleCount(normalizedRow + 1);
			String current = this.roleNames.get(normalizedRow);
			int index = ROLE_NAME_CYCLE.indexOf(current);
			int nextIndex = (index + 1) % ROLE_NAME_CYCLE.size();
			if (index < 0) {
				nextIndex = 0;
			}
			this.roleNames.set(normalizedRow, ROLE_NAME_CYCLE.get(nextIndex));
			return nextIndex;
		}

		public void ensureRoleCount(int count) {
			int normalizedCount = Math.max(0, Math.min(MAX_ROLE_COUNT, count));
			if (normalizedCount <= this.roleNames.size()) {
				return;
			}
			while (this.roleNames.size() < normalizedCount) {
				this.roleNames.add("Role " + (this.roleNames.size() + 1));
			}
		}

		private static int defaultZoneForColumn(int column) {
			return switch (column) {
				case 1 -> 2;
				case 2 -> 3;
				default -> 1;
			};
		}

		private Map<String, Integer> zoneColorsForCodec() {
			return Collections.unmodifiableMap(this.zoneColors);
		}

		private Map<String, Integer> rowColumnZonesForCodec() {
			return Collections.unmodifiableMap(this.rowColumnZones);
		}

		private List<String> roleNamesForCodec() {
			return Collections.unmodifiableList(this.roleNames);
		}

		private int preferredFormationIdForCodec() {
			return this.preferredFormationId;
		}

		private static String toChunkKey(int chunkX, int chunkZ) {
			return chunkX + "," + chunkZ;
		}

		private static OptionalLong tryParseChunkKey(String key) {
			if (key == null || key.isBlank()) {
				return OptionalLong.empty();
			}
			int comma = key.indexOf(',');
			if (comma <= 0 || comma >= key.length() - 1) {
				return OptionalLong.empty();
			}
			try {
				int x = Integer.parseInt(key.substring(0, comma).trim());
				int z = Integer.parseInt(key.substring(comma + 1).trim());
				return OptionalLong.of(net.minecraft.util.math.ChunkPos.toLong(x, z));
			} catch (NumberFormatException ignored) {
				return OptionalLong.empty();
			}
		}

		private static String toRowColumnKey(int row, int column) {
			return row + ":" + column;
		}

		private static int[] parseRowColumnKey(String key) {
			if (key == null || key.isBlank()) {
				return null;
			}
			int colon = key.indexOf(':');
			if (colon <= 0 || colon >= key.length() - 1) {
				return null;
			}
			try {
				int row = Integer.parseInt(key.substring(0, colon).trim());
				int column = Integer.parseInt(key.substring(colon + 1).trim());
				if (!isValidRow(row) || !isValidColumn(column)) {
					return null;
				}
				return new int[]{row, column};
			} catch (NumberFormatException ignored) {
				return null;
			}
		}

		private static boolean isValidColorId(int colorId) {
			return colorId >= 1 && colorId <= MAX_COLOR_ID;
		}

		private static int normalizeColorId(int colorId) {
			return isValidColorId(colorId) ? colorId : 0;
		}

		private static boolean isValidRow(int row) {
			return row >= MIN_ROW_INDEX && row <= MAX_ROW_INDEX;
		}

		private static boolean isValidColumn(int column) {
			return column >= MIN_COLUMN_INDEX && column <= MAX_COLUMN_INDEX;
		}

		private static int normalizeRow(int row) {
			return Math.max(MIN_ROW_INDEX, Math.min(MAX_ROW_INDEX, row));
		}

		private static int normalizeColumn(int column) {
			return Math.max(MIN_COLUMN_INDEX, Math.min(MAX_COLUMN_INDEX, column));
		}

		private static String sanitizeRoleName(String roleName) {
			if (roleName == null || roleName.isBlank()) {
				return "Role";
			}
			String trimmed = roleName.trim();
			return trimmed.length() <= MAX_ROLE_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_ROLE_NAME_LENGTH);
		}

		private static int normalizeFormationId(int formationId) {
			return FormationType.fromId(formationId).getId();
		}
	}
}
