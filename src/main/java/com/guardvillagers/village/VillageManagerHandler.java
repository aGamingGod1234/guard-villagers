package com.guardvillagers.village;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.data.GuardVillageState;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.FormationType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VillageManagerHandler {
	private static final int PROCESS_INTERVAL_TICKS = 100;
	private static final int MAX_VILLAGERS_SCANNED = 256;
	private static final int MAX_SPAWNS_PER_VILLAGE = 2;
	private static final int MAX_GUARDS_PER_VILLAGE = 12;
	private static final int RESPAWN_COOLDOWN_TICKS = 20 * 60 * 2;
	private static final int NATURAL_GUARD_SCAN_EXPANSION = 16;
	private static final int DOOR_COUNT_CACHE_TICKS = 20 * 60;
	private static final int DOOR_COUNT_CACHE_MAX_ENTRIES = 4096;
	private static final Map<String, DoorCountCache> DOOR_COUNT_CACHE = new ConcurrentHashMap<>();
	private static final GuardBehavior[] VILLAGE_BEHAVIOR_PATTERN = {
		GuardBehavior.DEFENSIVE,
		GuardBehavior.PERIMETER,
		GuardBehavior.CROWD_CONTROL,
		GuardBehavior.OFFENSIVE
	};
	private static final int[] SPAWN_RING_OFFSETS = {
		0, 0, 4, 0, -4, 0, 0, 4, 0, -4, 6, 3, -6, -3, 8, 0, -8, 0
	};

	private VillageManagerHandler() {
	}

	public static void maintainVillageGuards(ServerWorld world) {
		if (world.getTime() % PROCESS_INTERVAL_TICKS != 0) {
			return;
		}

		List<VillagerEntity> villagers = collectLoadedVillagers(world);
		if (villagers.isEmpty()) {
			return;
		}

		Map<String, VillageAggregation> villages = aggregateVillages(world, villagers);
		if (villages.isEmpty()) {
			return;
		}

		GuardVillageState state = world.getPersistentStateManager().getOrCreate(GuardVillageState.TYPE);
		for (VillageAggregation aggregation : villages.values()) {
			VillageDescriptor village = aggregation.descriptor;
			int villagerCount = aggregation.villagerCount;
			if (villagerCount <= 0) {
				continue;
			}

			double density = calculateDensity(village, villagerCount);
			if (density < 0.10D) {
				continue;
			}

			int beds = countBeds(world, village);
			int doors = countDoors(world, village);
			int deterministicCap = Math.max(1, Math.max(villagerCount / 2, Math.max(beds, doors)));
			if (deterministicCap <= 0) {
				continue;
			}

			GuardVillageState.VillageData data = state.getOrCreate(village.id(), deterministicCap);
			state.updateInitial(village.id(), Math.max(deterministicCap, data.initialSpawnCount()));
			int initial = state.getOrCreate(village.id(), deterministicCap).initialSpawnCount();
			int regrowthCap = Math.max(initial, (int) Math.floor(initial * 1.5D));
			int dynamicCap = Math.max(1, Math.min(MAX_GUARDS_PER_VILLAGE, Math.min(deterministicCap, regrowthCap)));

			int naturalGuardCount = countNaturalGuards(world, village);
			if (naturalGuardCount >= dynamicCap) {
				continue;
			}

			long now = world.getTime();
			long lastSpawnTick = state.getLastSpawnTick(village.id());
			if (lastSpawnTick != Long.MIN_VALUE && now - lastSpawnTick < RESPAWN_COOLDOWN_TICKS) {
				continue;
			}

			int spawnBudget = Math.min(MAX_SPAWNS_PER_VILLAGE, dynamicCap - naturalGuardCount);
			boolean spawned = false;
			for (int i = 0; i < spawnBudget; i++) {
				spawned |= spawnVillageGuard(world, village, naturalGuardCount + i);
			}
			if (spawned) {
				state.setLastSpawnTick(village.id(), now);
			}
		}
	}

	public static Optional<VillageDescriptor> findVillageDescriptor(ServerWorld world, BlockPos origin) {
		StructureStart start = world.getStructureAccessor().getStructureContaining(origin, StructureTags.VILLAGE);
		if (start == null || !start.hasChildren()) {
			return Optional.empty();
		}

		BlockBox bounds = start.getBoundingBox();
		if (bounds == null || bounds.getMaxX() < bounds.getMinX() || bounds.getMaxZ() < bounds.getMinZ()) {
			return Optional.empty();
		}

		BlockPos center = bounds.getCenter();
		int radius = Math.max(16, Math.max(bounds.getBlockCountX(), bounds.getBlockCountZ()) / 2);
		String id = world.getRegistryKey().getValue() + "|" + bounds.getMinX() + "|" + bounds.getMinZ() + "|" + bounds.getMaxX() + "|" + bounds.getMaxZ();
		return Optional.of(new VillageDescriptor(id, bounds, center, radius));
	}

	private static List<VillagerEntity> collectLoadedVillagers(ServerWorld world) {
		List<VillagerEntity> villagers = new ArrayList<>();
		for (Entity entity : world.iterateEntities()) {
			if (entity instanceof VillagerEntity villager && villager.isAlive() && !villager.isRemoved()) {
				villagers.add(villager);
				if (villagers.size() >= MAX_VILLAGERS_SCANNED) {
					break;
				}
			}
		}
		return villagers;
	}

	private static Map<String, VillageAggregation> aggregateVillages(ServerWorld world, List<VillagerEntity> villagers) {
		Map<Long, Optional<VillageDescriptor>> chunkCache = new HashMap<>();
		Map<String, VillageAggregation> villages = new HashMap<>();

		for (VillagerEntity villager : villagers) {
			int chunkX = ChunkSectionPos.getSectionCoord(villager.getBlockX());
			int chunkZ = ChunkSectionPos.getSectionCoord(villager.getBlockZ());
			long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
			Optional<VillageDescriptor> descriptor = chunkCache.computeIfAbsent(chunkKey, ignored -> {
				Optional<VillageDescriptor> structured = findVillageDescriptor(world, villager.getBlockPos());
				return structured.isPresent() ? structured : findFallbackVillageDescriptor(world, villager.getBlockPos());
			});
			if (descriptor.isEmpty()) {
				continue;
			}

			VillageDescriptor village = descriptor.get();
			VillageAggregation aggregation = villages.computeIfAbsent(village.id(), ignored -> new VillageAggregation(village));
			aggregation.villagerCount++;
		}

		return villages;
	}

	private static Optional<VillageDescriptor> findFallbackVillageDescriptor(ServerWorld world, BlockPos origin) {
		Optional<BlockPos> poiCenter = world.getPointOfInterestStorage().getNearestPosition(
			entry -> entry.matchesKey(PointOfInterestTypes.HOME),
			origin,
			48,
			PointOfInterestStorage.OccupationStatus.ANY
		);
		if (poiCenter.isEmpty()) {
			return Optional.empty();
		}

		BlockPos center = poiCenter.get();
		int radius = 32;
		BlockBox bounds = new BlockBox(
			center.getX() - radius,
			world.getBottomY(),
			center.getZ() - radius,
			center.getX() + radius,
			world.getTopYInclusive(),
			center.getZ() + radius
		);
		String id = world.getRegistryKey().getValue()
			+ "|poi|"
			+ ChunkSectionPos.getSectionCoord(center.getX())
			+ "|"
			+ ChunkSectionPos.getSectionCoord(center.getZ());
		return Optional.of(new VillageDescriptor(id, bounds, center, radius));
	}

	private static double calculateDensity(VillageDescriptor village, int villagerCount) {
		double area = Math.max(1.0D, village.bounds().getBlockCountX() * village.bounds().getBlockCountZ());
		return villagerCount / (area / 256.0D);
	}

	private static int countNaturalGuards(ServerWorld world, VillageDescriptor village) {
		return world.getEntitiesByClass(
			GuardEntity.class,
			village.toEntityBox(NATURAL_GUARD_SCAN_EXPANSION),
			guard -> !guard.hasOwner() && village.bounds().contains(guard.getBlockPos())
		).size();
	}

	private static int countBeds(ServerWorld world, VillageDescriptor village) {
		long beds = world.getPointOfInterestStorage().count(
			entry -> entry.matchesKey(PointOfInterestTypes.HOME),
			village.center(),
			village.horizontalRadius() + 16,
			PointOfInterestStorage.OccupationStatus.ANY
		);
		return (int) Math.min(Integer.MAX_VALUE, beds);
	}

	private static int countDoors(ServerWorld world, VillageDescriptor village) {
		long now = world.getTime();
		String cacheKey = world.getRegistryKey().getValue() + "|" + village.id();
		DoorCountCache cached = DOOR_COUNT_CACHE.get(cacheKey);
		if (cached != null && now - cached.sampleTick() <= DOOR_COUNT_CACHE_TICKS) {
			return cached.count();
		}

		int recalculated = countDoorsUncached(world, village);
		DOOR_COUNT_CACHE.put(cacheKey, new DoorCountCache(recalculated, now));
		cleanupDoorCache(now);
		return recalculated;
	}

	private static int countDoorsUncached(ServerWorld world, VillageDescriptor village) {
		int radius = Math.min(64, village.horizontalRadius() + 8);
		int minY = Math.max(world.getBottomY(), village.center().getY() - 4);
		int maxY = Math.min(world.getTopYInclusive(), village.center().getY() + 5);
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		int count = 0;

		for (int x = village.center().getX() - radius; x <= village.center().getX() + radius; x++) {
			for (int z = village.center().getZ() - radius; z <= village.center().getZ() + radius; z++) {
				for (int y = minY; y <= maxY; y++) {
					mutable.set(x, y, z);
					if (world.getBlockState(mutable).isIn(BlockTags.DOORS)) {
						count++;
						if (count >= 512) {
							return count;
						}
					}
				}
			}
		}
		return count;
	}

	private static void cleanupDoorCache(long worldTime) {
		if (DOOR_COUNT_CACHE.size() <= DOOR_COUNT_CACHE_MAX_ENTRIES) {
			return;
		}
		long cutoff = worldTime - (DOOR_COUNT_CACHE_TICKS * 4L);
		DOOR_COUNT_CACHE.entrySet().removeIf(entry -> entry.getValue().sampleTick() < cutoff);
	}

	private static boolean spawnVillageGuard(ServerWorld world, VillageDescriptor village, int guardIndex) {
		GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(world, SpawnReason.NATURAL);
		if (guard == null) {
			GuardVillagersMod.LOGGER.warn("Failed to create village guard for village {}", village.id());
			return false;
		}

		int ringIndex = (guardIndex * 2) % SPAWN_RING_OFFSETS.length;
		int xOffset = SPAWN_RING_OFFSETS[ringIndex];
		int zOffset = SPAWN_RING_OFFSETS[(ringIndex + 1) % SPAWN_RING_OFFSETS.length];
		BlockPos origin = village.center().add(xOffset, 0, zOffset);
		BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin);

		guard.refreshPositionAndAngles(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D, world.getRandom().nextFloat() * 360.0F, 0.0F);
		guard.applyNaturalLoadout(world);
		guard.setBehavior(pickVillageBehavior(village, guardIndex));
		guard.setFormationType(FormationType.LINE);
		guard.setSquadId(UUID.nameUUIDFromBytes(village.id().getBytes(StandardCharsets.UTF_8)));
		guard.setSquadLeader(false);
		guard.setHome(village.center(), Math.max(24, village.horizontalRadius()));

		if (!world.spawnEntity(guard)) {
			GuardVillagersMod.LOGGER.warn("Village guard spawn rejected at {} in {}", top, world.getRegistryKey().getValue());
			return false;
		}
		return true;
	}

	private static GuardBehavior pickVillageBehavior(VillageDescriptor village, int guardIndex) {
		int patternLength = VILLAGE_BEHAVIOR_PATTERN.length;
		if (patternLength == 0) {
			return GuardBehavior.DEFENSIVE;
		}
		int villageOffset = Math.floorMod(village.id().hashCode(), patternLength);
		int index = Math.floorMod(guardIndex + villageOffset, patternLength);
		return VILLAGE_BEHAVIOR_PATTERN[index];
	}

	private static final class VillageAggregation {
		private final VillageDescriptor descriptor;
		private int villagerCount;

		private VillageAggregation(VillageDescriptor descriptor) {
			this.descriptor = descriptor;
		}
	}

	private record DoorCountCache(int count, long sampleTick) {
	}
}
