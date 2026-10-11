package com.guardvillagers.village;

import com.guardvillagers.GuardDiplomacyManager;
import com.guardvillagers.entity.GuardEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Ringing a bell (by hand, projectile or redstone) calls guards to it: unowned village guards within
 * {@link #NATURAL_RADIUS} blocks, plus owned guards whose home is in the bell's village. Owned guards only answer
 * their owner, players their owner whitelisted, or a ring with no player behind it (redstone alarms).
 */
public final class GuardBellRally {
	public static final int NATURAL_RADIUS = 32;
	public static final int RALLY_TICKS = 240;
	/** Refresh candidates every 5 s; accepted commands have a separate cooldown for each owner. */
	private static final int BELL_COOLDOWN_TICKS = 100;
	/** How far outside the village bounds to look for owned guards that live there but wandered off. */
	private static final int OWNED_SEARCH_MARGIN = 64;
	private static final int MAX_TRACKED_BELLS = 4096;
	private static final Map<BellKey, BellState> BELLS = new ConcurrentHashMap<>();

	private GuardBellRally() {
	}

	public static int onBellRung(ServerLevel level, BlockPos bellPos, @Nullable Entity ringer) {
		int now = level.getServer().getTickCount();
		BellKey key = new BellKey(level.dimension(), bellPos.asLong());
		BellState state = BELLS.get(key);
		if (state == null || !onCooldown(state.sampleTick, now)) {
			if (BELLS.size() >= MAX_TRACKED_BELLS) {
				BELLS.entrySet().removeIf(entry -> !onCooldown(entry.getValue().lastUseTick(), now));
			}
			Optional<VillageDescriptor> village = VillageManagerHandler.findVillageDescriptor(level, bellPos);
			AABB naturalBox = new AABB(bellPos).inflate(NATURAL_RADIUS);
			AABB searchBox = village.map(v -> v.toEntityBox(OWNED_SEARCH_MARGIN).minmax(naturalBox)).orElse(naturalBox);
			List<UUID> candidates = level.getEntitiesOfClass(GuardEntity.class, searchBox, GuardEntity::isAlive)
				.stream().map(GuardEntity::getUUID).toList();
			state = new BellState(now, village, searchBox, candidates, state == null ? new HashMap<>() : state.lastRallyTicks);
			state.lastRallyTicks.entrySet().removeIf(entry -> !onCooldown(entry.getValue(), now));
			BELLS.put(key, state);
		}

		UUID ringerId = ringer instanceof Player player ? player.getUUID() : null;
		double naturalRadiusSq = (double) NATURAL_RADIUS * NATURAL_RADIUS;

		int rallied = 0;
		Set<UUID> ralliedOwners = new HashSet<>();
		for (UUID guardId : state.candidates) {
			if (!(level.getEntity(guardId) instanceof GuardEntity guard) || !guard.isAlive()
					|| !state.searchBox.intersects(guard.getBoundingBox())) {
				continue;
			}
			UUID owner = guard.getOwnerUuid();
			Integer last = state.lastRallyTicks.get(owner);
			if (last != null && onCooldown(last, now)) {
				continue;
			}
			boolean answers;
			if (!guard.hasOwner()) {
				answers = guard.blockPosition().distSqr(bellPos) <= naturalRadiusSq;
			} else {
				Optional<VillageDescriptor> village = state.village;
				answers = guard.getHome().filter(home -> livesHere(home, bellPos, village)).isPresent()
						&& mayCommand(level, guard.getOwnerUuid(), ringerId);
			}
			if (answers) {
				guard.rallyTo(bellPos, RALLY_TICKS);
				ralliedOwners.add(owner);
				rallied++;
			}
		}
		for (UUID owner : ralliedOwners) {
			state.lastRallyTicks.put(owner, now);
		}
		return rallied;
	}

	public static void clearSessionState() {
		BELLS.clear();
	}

	private static boolean onCooldown(int last, int now) {
		return now >= last && now - last < BELL_COOLDOWN_TICKS;
	}

	private static boolean livesHere(BlockPos home, BlockPos bellPos, Optional<VillageDescriptor> village) {
		if (village.isPresent()) {
			return village.get().bounds().isInside(home);
		}
		return home.distSqr(bellPos) <= (double) NATURAL_RADIUS * NATURAL_RADIUS;
	}

	static boolean mayCommand(ServerLevel level, UUID ownerId, @Nullable UUID ringerId) {
		return ringerId == null
				|| ringerId.equals(ownerId)
				|| GuardDiplomacyManager.isWhitelisted(level.getServer(), ownerId, ringerId);
	}

	private record BellKey(ResourceKey<Level> dimension, long pos) {
	}

	private record BellState(int sampleTick, Optional<VillageDescriptor> village, AABB searchBox, List<UUID> candidates,
			Map<UUID, Integer> lastRallyTicks) {
		private int lastUseTick() {
			return Math.max(this.sampleTick, this.lastRallyTicks.values().stream().mapToInt(Integer::intValue).max().orElse(this.sampleTick));
		}
	}
}
