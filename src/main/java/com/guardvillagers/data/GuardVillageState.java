package com.guardvillagers.data;

import com.guardvillagers.GuardSecurityLimits;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class GuardVillageState extends SavedData {
	private static final int MIN_GUARD_COUNT = 1;
	private static final int MAX_GUARD_COUNT = 10_000;
	private static final Codec<Map<String, VillageData>> VILLAGE_MAP_CODEC = Codec.unboundedMap(Codec.STRING, VillageData.CODEC);
	private static final Codec<Map<UUID, UUID>> GUARD_MAP_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, UUIDUtil.STRING_CODEC);
	public static final Codec<GuardVillageState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		VILLAGE_MAP_CODEC.optionalFieldOf("villages", Map.of()).forGetter(GuardVillageState::villagesForCodec),
		GUARD_MAP_CODEC.optionalFieldOf("natural_guards", Map.of()).forGetter(state -> Collections.unmodifiableMap(state.naturalGuards))
	).apply(instance, GuardVillageState::new));

	public static final SavedDataType<GuardVillageState> TYPE = new SavedDataType<>(
		GuardSavedData.id("village_state"),
		GuardVillageState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<String, VillageData> villages;
	private final Map<UUID, UUID> naturalGuards = new HashMap<>();
	private final Map<UUID, Set<UUID>> guardsBySquad = new HashMap<>();

	public GuardVillageState() {
		this(Map.of(), Map.of());
	}

	private GuardVillageState(Map<String, VillageData> villages, Map<UUID, UUID> naturalGuards) {
		this.villages = new LinkedHashMap<>(Math.max(16, villages.size()), 0.75F, true);
		for (Map.Entry<String, VillageData> entry : villages.entrySet()) {
			this.putBounded(entry.getKey(), entry.getValue());
		}
		naturalGuards.forEach(this::putNaturalGuard);
	}

	/** Membership survives chunk unloads and village retirement; only an observed departure removes a guard. */
	public void recordNaturalGuard(UUID guardId, UUID squadId) {
		if (!squadId.equals(this.naturalGuards.get(guardId))) {
			this.removeNaturalGuard(guardId);
			this.putNaturalGuard(guardId, squadId);
			this.setDirty();
		}
	}

	private void putNaturalGuard(UUID guardId, UUID squadId) {
		this.naturalGuards.put(guardId, squadId);
		this.guardsBySquad.computeIfAbsent(squadId, ignored -> new HashSet<>()).add(guardId);
	}

	public void removeNaturalGuard(UUID guardId) {
		UUID squadId = this.naturalGuards.remove(guardId);
		if (squadId != null) {
			Set<UUID> guards = this.guardsBySquad.get(squadId);
			guards.remove(guardId);
			if (guards.isEmpty()) {
				this.guardsBySquad.remove(squadId);
			}
			this.setDirty();
		}
	}

	public int countNaturalGuards(UUID squadId) {
		return this.guardsBySquad.getOrDefault(squadId, Set.of()).size();
	}

	public VillageData getOrCreate(String villageId, int initialSpawnCount) {
		return this.getOrCreate(villageId, initialSpawnCount, Long.MIN_VALUE);
	}

	public VillageData getOrCreate(String villageId, int initialSpawnCount, long observedTick) {
		int clampedInitial = clampGuardCount(initialSpawnCount);
		VillageData existing = this.villages.get(villageId);
		if (existing != null) {
			VillageData touched = existing.withLastSeenTick(observedTick);
			if (!touched.equals(existing)) {
				this.villages.put(villageId, touched);
				this.setDirty();
			}
			return touched;
		}

		VillageData created = new VillageData(clampedInitial, Long.MIN_VALUE, clampedInitial, observedTick);
		this.putBounded(villageId, created);
		this.setDirty();
		return created;
	}

	public void updateInitial(String villageId, int newInitial) {
		if (newInitial <= 0) {
			return;
		}
		int clampedInitial = clampGuardCount(newInitial);
		VillageData current = this.villages.get(villageId);
		if (current == null || clampedInitial > current.initialSpawnCount()) {
			long lastSpawnTick = current == null ? Long.MIN_VALUE : current.lastSpawnTick();
			int maxGuards = current == null ? clampedInitial : Math.max(clampedInitial, current.maxGuardCount());
			long lastSeenTick = current == null ? Long.MIN_VALUE : current.lastSeenTick();
			this.putBounded(villageId, new VillageData(clampedInitial, lastSpawnTick, maxGuards, lastSeenTick));
			this.setDirty();
		}
	}

	public void updateMaxGuardCount(String villageId, int newMax) {
		if (newMax <= 0) {
			return;
		}
		VillageData current = this.villages.get(villageId);
		if (current == null) {
			return;
		}
		int clampedMax = clampGuardCount(newMax);
		if (clampedMax > current.maxGuardCount()) {
			this.villages.put(villageId, new VillageData(current.initialSpawnCount(), current.lastSpawnTick(), clampedMax, current.lastSeenTick()));
			this.setDirty();
		}
	}

	public int getMaxGuardCount(String villageId) {
		VillageData data = this.villages.get(villageId);
		return data == null ? 0 : data.maxGuardCount();
	}

	public long getLastSpawnTick(String villageId) {
		VillageData data = this.villages.get(villageId);
		return data == null ? Long.MIN_VALUE : data.lastSpawnTick();
	}

	public void setLastSpawnTick(String villageId, long tick) {
		VillageData data = this.villages.get(villageId);
		if (data == null || data.lastSpawnTick() == tick) {
			return;
		}
		this.villages.put(villageId, new VillageData(data.initialSpawnCount(), tick, data.maxGuardCount(), data.lastSeenTick()));
		this.setDirty();
	}

	public void retireStale(long currentTick, Set<String> observedVillageIds, long maxUnseenTicks) {
		if (this.villages.isEmpty()) {
			return;
		}
		int before = this.villages.size();
		long retention = Math.max(0L, maxUnseenTicks);
		this.villages.entrySet().removeIf(entry -> {
			if (observedVillageIds != null && observedVillageIds.contains(entry.getKey())) {
				return false;
			}
			long lastSeen = entry.getValue().lastSeenTick();
			return lastSeen == Long.MIN_VALUE || currentTick - lastSeen > retention;
		});
		this.evictOverflow(observedVillageIds);
		if (this.villages.size() != before) {
			this.setDirty();
		}
	}

	public int trackedVillageCount() {
		return this.villages.size();
	}

	private Map<String, VillageData> villagesForCodec() {
		return Collections.unmodifiableMap(this.villages);
	}

	private static int clampGuardCount(int count) {
		return Math.max(MIN_GUARD_COUNT, Math.min(MAX_GUARD_COUNT, count));
	}

	public record VillageData(int initialSpawnCount, long lastSpawnTick, int maxGuardCount, long lastSeenTick) {
		public static final Codec<VillageData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("initial_spawn_count").forGetter(VillageData::initialSpawnCount),
			Codec.LONG.optionalFieldOf("last_spawn_tick", Long.MIN_VALUE).forGetter(VillageData::lastSpawnTick),
			Codec.INT.optionalFieldOf("max_guard_count", 1).forGetter(VillageData::maxGuardCount),
			Codec.LONG.optionalFieldOf("last_seen_tick", Long.MIN_VALUE).forGetter(VillageData::lastSeenTick)
		).apply(instance, VillageData::new));

		public VillageData {
			initialSpawnCount = clampGuardCount(initialSpawnCount);
			maxGuardCount = Math.max(initialSpawnCount, clampGuardCount(maxGuardCount));
		}

		private VillageData withLastSeenTick(long tick) {
			if (tick == Long.MIN_VALUE || tick == this.lastSeenTick) {
				return this;
			}
			return new VillageData(this.initialSpawnCount, this.lastSpawnTick, this.maxGuardCount, tick);
		}
	}

	private void putBounded(String villageId, VillageData data) {
		if (!this.villages.containsKey(villageId) && this.villages.size() >= GuardSecurityLimits.MAX_VILLAGES) {
			this.evictEldest(Set.of());
		}
		this.villages.put(villageId, data);
	}

	private void evictOverflow(Set<String> protectedIds) {
		while (this.villages.size() > GuardSecurityLimits.MAX_VILLAGES) {
			if (!this.evictEldest(protectedIds)) {
				return;
			}
		}
	}

	private boolean evictEldest(Set<String> protectedIds) {
		Iterator<String> iterator = this.villages.keySet().iterator();
		while (iterator.hasNext()) {
			String villageId = iterator.next();
			if (protectedIds != null && protectedIds.contains(villageId)) {
				continue;
			}
			iterator.remove();
			return true;
		}
		return false;
	}
}
