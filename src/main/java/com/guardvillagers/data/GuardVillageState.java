package com.guardvillagers.data;

import com.guardvillagers.GuardSecurityLimits;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class GuardVillageState extends PersistentState {
	private static final int MIN_GUARD_COUNT = 1;
	private static final int MAX_GUARD_COUNT = 10_000;
	private static final Codec<Map<String, VillageData>> VILLAGE_MAP_CODEC = Codec.unboundedMap(Codec.STRING, VillageData.CODEC);
	public static final Codec<GuardVillageState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		VILLAGE_MAP_CODEC.optionalFieldOf("villages", Map.of()).forGetter(GuardVillageState::villagesForCodec)
	).apply(instance, GuardVillageState::new));

	public static final PersistentStateType<GuardVillageState> TYPE = new PersistentStateType<>(
		"guardvillagers_village_state",
		GuardVillageState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<String, VillageData> villages;

	public GuardVillageState() {
		this(Map.of());
	}

	private GuardVillageState(Map<String, VillageData> villages) {
		this.villages = new LinkedHashMap<>(Math.max(16, villages.size()), 0.75F, true);
		for (Map.Entry<String, VillageData> entry : villages.entrySet()) {
			this.putBounded(entry.getKey(), entry.getValue());
		}
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
				this.markDirty();
			}
			return touched;
		}

		VillageData created = new VillageData(clampedInitial, Long.MIN_VALUE, clampedInitial, observedTick);
		this.putBounded(villageId, created);
		this.markDirty();
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
			this.markDirty();
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
			this.markDirty();
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
		this.markDirty();
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
			this.markDirty();
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
