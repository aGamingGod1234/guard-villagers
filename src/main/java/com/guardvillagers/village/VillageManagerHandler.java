package com.guardvillagers.village;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.data.GuardVillageState;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public final class VillageManagerHandler {
	private static final int PROCESS_INTERVAL_TICKS = 100;
	private static final int MAX_VILLAGERS_SCANNED = 256;
	private static final int MAX_SPAWNS_PER_VILLAGE = 2;
	private static final int MAX_GUARDS_PER_VILLAGE = 12;
	private static final int RESPAWN_COOLDOWN_TICKS = 20 * 60 * 2;
	private static final int NATURAL_GUARD_SCAN_EXPANSION = 16;
	private static final int DOOR_COUNT_CACHE_TICKS = 20 * 60;
	private static final int DOOR_COUNT_CACHE_MAX_ENTRIES = 4096;
	private static final long VILLAGE_RETENTION_TICKS = 20L * 60L * 60L * 6L;
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

	public static void maintainVillageGuards(ServerLevel world) {
		if (world.getGameTime() % PROCESS_INTERVAL_TICKS != 0) {
			return;
		}

		List<Villager> villagers = collectLoadedVillagers(world);
		GuardVillageState state = world.getDataStorage().computeIfAbsent(GuardVillageState.TYPE);
		if (villagers.isEmpty()) {
			state.retireStale(world.getGameTime(), Set.of(), VILLAGE_RETENTION_TICKS);
			return;
		}

		Map<String, VillageAggregation> villages = aggregateVillages(world, villagers);
		if (villages.isEmpty()) {
			state.retireStale(world.getGameTime(), Set.of(), VILLAGE_RETENTION_TICKS);
			return;
		}

		long now = world.getGameTime();
		Set<String> observedVillageIds = new HashSet<>();
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
			observedVillageIds.add(village.id());

			GuardVillageState.VillageData data = state.getOrCreate(village.id(), deterministicCap, now);
			state.updateInitial(village.id(), Math.max(deterministicCap, data.initialSpawnCount()));
			int initial = state.getOrCreate(village.id(), deterministicCap, now).initialSpawnCount();
			int regrowthCap = Math.max(initial, (int) Math.floor(initial * 1.5D));
			int dynamicCap = Math.max(1, Math.min(MAX_GUARDS_PER_VILLAGE, Math.min(deterministicCap, regrowthCap)));

			// Update max guard count if the dynamic cap has increased
			state.updateMaxGuardCount(village.id(), dynamicCap);
			int maxGuards = Math.min(MAX_GUARDS_PER_VILLAGE, state.getMaxGuardCount(village.id()));
			if (maxGuards <= 0) {
				maxGuards = dynamicCap;
			}

			int naturalGuardCount = countNaturalGuards(world, village);
			if (naturalGuardCount >= maxGuards) {
				continue;
			}

			long lastSpawnTick = state.getLastSpawnTick(village.id());
			if (lastSpawnTick != Long.MIN_VALUE && now - lastSpawnTick < RESPAWN_COOLDOWN_TICKS) {
				continue;
			}

			int spawnBudget = Math.min(MAX_SPAWNS_PER_VILLAGE, maxGuards - naturalGuardCount);
			boolean spawned = false;
			for (int i = 0; i < spawnBudget; i++) {
				spawned |= spawnVillageGuard(world, village, naturalGuardCount + i);
			}
			if (spawned) {
				state.setLastSpawnTick(village.id(), now);
			}
		}
		state.retireStale(now, observedVillageIds, VILLAGE_RETENTION_TICKS);
	}

	public static Optional<VillageDescriptor> findVillageDescriptor(ServerLevel world, BlockPos origin) {
		StructureStart start = world.structureManager().getStructureWithPieceAt(origin, StructureTags.VILLAGE);
		if (start == null || !start.isValid()) {
			return Optional.empty();
		}

		BoundingBox bounds = start.getBoundingBox();
		if (bounds == null || bounds.maxX() < bounds.minX() || bounds.maxZ() < bounds.minZ()) {
			return Optional.empty();
		}

		BlockPos center = bounds.getCenter();
		int radius = Math.max(16, Math.max(bounds.getXSpan(), bounds.getZSpan()) / 2);
		String id = world.dimension().identifier() + "|" + bounds.minX() + "|" + bounds.minZ() + "|" + bounds.maxX() + "|" + bounds.maxZ();
		return Optional.of(new VillageDescriptor(id, bounds, center, radius));
	}

	private static List<Villager> collectLoadedVillagers(ServerLevel world) {
		List<Villager> villagers = new ArrayList<>();
		for (Entity entity : world.getAllEntities()) {
			if (entity instanceof Villager villager && villager.isAlive() && !villager.isRemoved()) {
				villagers.add(villager);
				if (villagers.size() >= MAX_VILLAGERS_SCANNED) {
					break;
				}
			}
		}
		return villagers;
	}

	/**
	 * B1: A villager belongs to a village iff the villager has either a bed (HOME POI)
	 * or a job site located within the inner bounding box of the chunk.
	 */
	private static Map<String, VillageAggregation> aggregateVillages(ServerLevel world, List<Villager> villagers) {
		Map<Long, Optional<VillageDescriptor>> chunkCache = new HashMap<>();
		Map<String, VillageAggregation> villages = new HashMap<>();

		for (Villager villager : villagers) {
			int chunkX = SectionPos.blockToSectionCoord(villager.getBlockX());
			int chunkZ = SectionPos.blockToSectionCoord(villager.getBlockZ());
			long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
			Optional<VillageDescriptor> descriptor = chunkCache.computeIfAbsent(chunkKey, ignored -> {
				Optional<VillageDescriptor> structured = findVillageDescriptor(world, villager.blockPosition());
				return structured.isPresent() ? structured : findFallbackVillageDescriptor(world, villager.blockPosition());
			});
			if (descriptor.isEmpty()) {
				continue;
			}

			VillageDescriptor village = descriptor.get();

			// B1: Verify the villager has a bed or job site within the village bounds
			if (!villagerBelongsToVillage(world, villager, village)) {
				continue;
			}

			VillageAggregation aggregation = villages.computeIfAbsent(village.id(), ignored -> new VillageAggregation(village));
			aggregation.villagerCount++;
		}

		return villages;
	}

	/**
	 * A villager belongs to a village iff it has a bed (HOME POI) or a job site
	 * located within the inner bounding box.
	 */
	private static boolean villagerBelongsToVillage(ServerLevel world, Villager villager, VillageDescriptor village) {
		BoundingBox bounds = village.bounds();

		// Check for bed (HOME POI) within village bounds near the villager
		boolean hasBedInBounds = world.getPoiManager().getInSquare(
			entry -> entry.is(PoiTypes.HOME),
			villager.blockPosition(),
			48,
			PoiManager.Occupancy.ANY
		).anyMatch(poi -> bounds.isInside(poi.getPos()));

		if (hasBedInBounds) {
			return true;
		}

		// Check for any job site POI within village bounds near the villager
		boolean hasJobSiteInBounds = world.getPoiManager().getInSquare(
			entry -> !entry.is(PoiTypes.HOME)
				&& !entry.is(PoiTypes.MEETING)
				&& !entry.is(PoiTypes.NETHER_PORTAL)
				&& !entry.is(PoiTypes.LIGHTNING_ROD)
				&& !entry.is(PoiTypes.LODESTONE)
				&& !entry.is(PoiTypes.BEE_NEST)
				&& !entry.is(PoiTypes.BEEHIVE),
			villager.blockPosition(),
			48,
			PoiManager.Occupancy.ANY
		).anyMatch(poi -> bounds.isInside(poi.getPos()));

		return hasJobSiteInBounds;
	}

	private static Optional<VillageDescriptor> findFallbackVillageDescriptor(ServerLevel world, BlockPos origin) {
		Optional<BlockPos> poiCenter = world.getPoiManager().findClosest(
			entry -> entry.is(PoiTypes.HOME),
			origin,
			48,
			PoiManager.Occupancy.ANY
		);
		if (poiCenter.isEmpty()) {
			return Optional.empty();
		}

		BlockPos center = poiCenter.get();
		int radius = 32;
		BoundingBox bounds = new BoundingBox(
			center.getX() - radius,
			world.getMinY(),
			center.getZ() - radius,
			center.getX() + radius,
			world.getMaxY(),
			center.getZ() + radius
		);
		String id = world.dimension().identifier()
			+ "|poi|"
			+ SectionPos.blockToSectionCoord(center.getX())
			+ "|"
			+ SectionPos.blockToSectionCoord(center.getZ());
		return Optional.of(new VillageDescriptor(id, bounds, center, radius));
	}

	private static double calculateDensity(VillageDescriptor village, int villagerCount) {
		double area = Math.max(1.0D, village.bounds().getXSpan() * village.bounds().getZSpan());
		return villagerCount / (area / 256.0D);
	}

	private static int countNaturalGuards(ServerLevel world, VillageDescriptor village) {
		return world.getEntitiesOfClass(
			GuardEntity.class,
			village.toEntityBox(NATURAL_GUARD_SCAN_EXPANSION),
			guard -> !guard.hasOwner() && village.bounds().isInside(guard.blockPosition())
		).size();
	}

	private static int countBeds(ServerLevel world, VillageDescriptor village) {
		long beds = world.getPoiManager().getCountInRange(
			entry -> entry.is(PoiTypes.HOME),
			village.center(),
			village.horizontalRadius() + 16,
			PoiManager.Occupancy.ANY
		);
		return (int) Math.min(Integer.MAX_VALUE, beds);
	}

	private static int countDoors(ServerLevel world, VillageDescriptor village) {
		long now = world.getGameTime();
		String cacheKey = world.dimension().identifier() + "|" + village.id();
		DoorCountCache cached = DOOR_COUNT_CACHE.get(cacheKey);
		if (cached != null && now - cached.sampleTick() <= DOOR_COUNT_CACHE_TICKS) {
			return cached.count();
		}

		int recalculated = countDoorsUncached(world, village);
		DOOR_COUNT_CACHE.put(cacheKey, new DoorCountCache(recalculated, now));
		cleanupDoorCache(now);
		return recalculated;
	}

	private static int countDoorsUncached(ServerLevel world, VillageDescriptor village) {
		int radius = Math.min(64, village.horizontalRadius() + 8);
		int minY = Math.max(world.getMinY(), village.center().getY() - 4);
		int maxY = Math.min(world.getMaxY(), village.center().getY() + 5);
		BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
		int count = 0;

		for (int x = village.center().getX() - radius; x <= village.center().getX() + radius; x++) {
			for (int z = village.center().getZ() - radius; z <= village.center().getZ() + radius; z++) {
				for (int y = minY; y <= maxY; y++) {
					mutable.set(x, y, z);
					if (world.getBlockState(mutable).is(BlockTags.DOORS)) {
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

	private static boolean spawnVillageGuard(ServerLevel world, VillageDescriptor village, int guardIndex) {
		GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(world, EntitySpawnReason.NATURAL);
		if (guard == null) {
			GuardVillagersMod.LOGGER.warn("Failed to create village guard for village {}", village.id());
			return false;
		}

		int ringIndex = (guardIndex * 2) % SPAWN_RING_OFFSETS.length;
		int xOffset = SPAWN_RING_OFFSETS[ringIndex];
		int zOffset = SPAWN_RING_OFFSETS[(ringIndex + 1) % SPAWN_RING_OFFSETS.length];
		BlockPos origin = village.center().offset(xOffset, 0, zOffset);
		BlockPos spawn = GuardVillagersMod.findNearbyGuardSpawnPos(world, origin, 10);
		if (spawn == null) {
			GuardVillagersMod.LOGGER.warn("No valid spawn position found for village guard in {}", village.id());
			return false;
		}

		guard.snapTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, world.getRandom().nextFloat() * 360.0F, 0.0F);
		guard.applyNaturalLoadout(world);
		guard.setBehavior(pickVillageBehavior(village, guardIndex));
		guard.setSquadId(UUID.nameUUIDFromBytes(village.id().getBytes(StandardCharsets.UTF_8)));
		guard.setSquadLeader(false);
		guard.setHome(village.center(), Math.max(24, village.horizontalRadius()));

		if (!world.addFreshEntity(guard)) {
			GuardVillagersMod.LOGGER.warn("Village guard spawn rejected at {} in {}", spawn, world.dimension().identifier());
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
