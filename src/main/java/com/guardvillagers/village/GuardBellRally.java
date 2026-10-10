package com.guardvillagers.village;

import com.guardvillagers.GuardDiplomacyManager;
import com.guardvillagers.entity.GuardEntity;
import java.util.Map;
import java.util.Optional;
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
	/** One rally per bell per 5 s, so a redstone clock on a bell cannot turn into a per-tick entity search. */
	private static final int BELL_COOLDOWN_TICKS = 100;
	/** How far outside the village bounds to look for owned guards that live there but wandered off. */
	private static final int OWNED_SEARCH_MARGIN = 64;
	private static final int MAX_TRACKED_BELLS = 4096;
	private static final Map<BellKey, Integer> LAST_RALLY_TICK = new ConcurrentHashMap<>();

	private GuardBellRally() {
	}

	public static int onBellRung(ServerLevel level, BlockPos bellPos, @Nullable Entity ringer) {
		int now = level.getServer().getTickCount();
		BellKey key = new BellKey(level.dimension(), bellPos.asLong());
		Integer last = LAST_RALLY_TICK.get(key);
		if (last != null && now >= last && now - last < BELL_COOLDOWN_TICKS) {
			return 0;
		}
		if (LAST_RALLY_TICK.size() >= MAX_TRACKED_BELLS) {
			LAST_RALLY_TICK.entrySet().removeIf(entry -> now - entry.getValue() >= BELL_COOLDOWN_TICKS || entry.getValue() > now);
		}
		LAST_RALLY_TICK.put(key, now);

		UUID ringerId = ringer instanceof Player player ? player.getUUID() : null;
		Optional<VillageDescriptor> village = VillageManagerHandler.findVillageDescriptor(level, bellPos);
		AABB naturalBox = new AABB(bellPos).inflate(NATURAL_RADIUS);
		AABB searchBox = village.map(v -> v.toEntityBox(OWNED_SEARCH_MARGIN).minmax(naturalBox)).orElse(naturalBox);
		double naturalRadiusSq = (double) NATURAL_RADIUS * NATURAL_RADIUS;

		int rallied = 0;
		for (GuardEntity guard : level.getEntitiesOfClass(GuardEntity.class, searchBox, GuardEntity::isAlive)) {
			boolean answers;
			if (!guard.hasOwner()) {
				answers = guard.blockPosition().distSqr(bellPos) <= naturalRadiusSq;
			} else {
				answers = guard.getHome().filter(home -> livesHere(home, bellPos, village)).isPresent()
						&& mayCommand(level, guard.getOwnerUuid(), ringerId);
			}
			if (answers) {
				guard.rallyTo(bellPos, RALLY_TICKS);
				rallied++;
			}
		}
		return rallied;
	}

	public static void clearSessionState() {
		LAST_RALLY_TICK.clear();
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
}
