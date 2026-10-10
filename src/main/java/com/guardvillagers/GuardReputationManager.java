package com.guardvillagers;

import com.guardvillagers.data.GuardReputationState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;

public final class GuardReputationManager {
	public static final double TRUST_THRESHOLD = 0.50D;
	public static final double HOSTILE_THRESHOLD = 0.25D;
	private static final int LEGACY_RANGE_SPAN = 400;
	private static final int TRADE_COOLDOWN_TICKS = 200;
	private static final int COOLDOWN_RETENTION_TICKS = 20 * 60 * 10;
	private static final int COOLDOWN_CLEANUP_INTERVAL_TICKS = 20 * 60;
	public static final int DECAY_INTERVAL_TICKS = 20 * 36;
	public static final double DECAY_STEP = 0.01D;
	static final double TRADE_DELTA = legacyDeltaToNormalized(1);
	private static final double VILLAGER_HARM_DELTA = legacyDeltaToNormalized(-8);
	private static final double GUARD_HARM_DELTA = legacyDeltaToNormalized(-6);
	private static final double RAID_DEFENSE_DELTA = legacyDeltaToNormalized(4);
	private static final double HOSTILE_KILL_DELTA = legacyDeltaToNormalized(2);
	private static final double RAIDER_KILL_DELTA = legacyDeltaToNormalized(4);
	private static final Pattern REPUTATION_INPUT_PATTERN = Pattern.compile("^(?:0(?:\\.\\d{1,2})?|1(?:\\.0{1,2})?)$");
	/** A player's first hit on a guard is forgiven if they had not hit any guard in this window... */
	public static final int GUARD_HIT_GRACE_TICKS = 20 * 5;
	/** ...and it dealt at most this much damage (two hearts) and the player is not already distrusted as hostile. */
	public static final float GUARD_HIT_GRACE_MAX_DAMAGE = 4.0F;
	private static final Map<UUID, Long> LAST_GUARD_HIT_TICK = new ConcurrentHashMap<>();
	/** Owner -> tick of a raider kill made during an active raid, consumed by the matching recordRaidDefense call. */
	private static final Map<UUID, Long> PENDING_RAID_DEFENSE = new ConcurrentHashMap<>();
	private static final Map<TradeKey, Long> TRADE_REPUTATION_COOLDOWN = new ConcurrentHashMap<>();
	private static long lastCooldownCleanupTick = Long.MIN_VALUE;

	private GuardReputationManager() {
	}

	public static GuardReputationState getState(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(GuardReputationState.TYPE);
	}

	/** Reputation is global per player; the position and radius are kept for API compatibility. */
	public static double getEffectiveReputation(ServerLevel world, UUID playerUuid, BlockPos reference, int radius) {
		return getState(world.getServer()).get(playerUuid);
	}

	public static double getEffectiveReputation(ServerPlayer player) {
		return getState(player.level().getServer()).get(player.getUUID());
	}

	public static boolean isTrustedByGuards(ServerLevel world, UUID playerUuid, BlockPos reference) {
		return getEffectiveReputation(world, playerUuid, reference, 64) >= TRUST_THRESHOLD;
	}

	public static boolean shouldGuardsTurnHostile(ServerLevel world, UUID playerUuid, BlockPos reference) {
		return getEffectiveReputation(world, playerUuid, reference, 64) < HOSTILE_THRESHOLD;
	}

	public static double getStoredReputation(ServerLevel world, UUID playerUuid) {
		return getState(world.getServer()).get(playerUuid);
	}

	public static void setReputation(MinecraftServer server, UUID playerUuid, double value) {
		getState(server).set(playerUuid, value);
	}

	public static void setReputation(ServerLevel world, UUID playerUuid, double value) {
		setReputation(world.getServer(), playerUuid, value);
	}

	public static void resetReputation(ServerLevel world, UUID playerUuid) {
		setReputation(world, playerUuid, 0.0D);
	}

	public static double applyReputationDelta(ServerLevel world, UUID playerUuid, int legacyDelta) {
		return applyNormalizedReputationDelta(world, playerUuid, legacyDeltaToNormalized(legacyDelta));
	}

	public static double applyNormalizedReputationDelta(ServerLevel world, UUID playerUuid, double delta) {
		return getState(world.getServer()).add(playerUuid, delta);
	}

	/** Called once per completed villager trade (see VillagerEntityMixin). Rewarded at most every 10 s per villager. */
	public static void recordCompletedTrade(ServerPlayer player, Villager villager) {
		long now = player.level().getGameTime();
		cleanupSessionMaps(now);
		TradeKey key = new TradeKey(player.getUUID(), villager.getUUID());
		Long previous = TRADE_REPUTATION_COOLDOWN.get(key);
		if (previous != null && now >= previous && now - previous < TRADE_COOLDOWN_TICKS) {
			return;
		}

		TRADE_REPUTATION_COOLDOWN.put(key, now);
		applyNormalizedReputationDelta(player.level(), player.getUUID(), TRADE_DELTA);
	}

	public static void recordVillagerHarm(ServerLevel world, UUID playerUuid) {
		applyNormalizedReputationDelta(world, playerUuid, VILLAGER_HARM_DELTA);
	}

	/**
	 * Single entry point for "a player damaged a guard". Applies the grace rule, then the reputation penalty.
	 *
	 * @return {@code true} when the hit counts and guards should retaliate/rally; {@code false} when it was forgiven
	 */
	public static boolean onGuardHitByPlayer(ServerLevel world, UUID playerUuid, float damage) {
		long now = world.getGameTime();
		cleanupSessionMaps(now);
		Long previousHit = LAST_GUARD_HIT_TICK.put(playerUuid, now);
		double reputation = getState(world.getServer()).get(playerUuid);
		if (isForgivableGuardHit(previousHit, now, reputation, damage)) {
			return false;
		}
		applyNormalizedReputationDelta(world, playerUuid, GUARD_HARM_DELTA);
		return true;
	}

	/**
	 * Legacy hook without the damage amount (GuardEntity#hurtServer still calls this after rallying). Applies the
	 * time-based part of the grace rule so an accidental first hit costs no reputation.
	 */
	public static void recordGuardHarm(ServerLevel world, UUID playerUuid) {
		onGuardHitByPlayer(world, playerUuid, 0.0F);
	}

	static boolean isForgivableGuardHit(Long previousHitTick, long now, double reputation, float damage) {
		boolean recentlyHit = previousHitTick != null && now >= previousHitTick && now - previousHitTick < GUARD_HIT_GRACE_TICKS;
		return !recentlyHit && reputation >= HOSTILE_THRESHOLD && damage <= GUARD_HIT_GRACE_MAX_DAMAGE;
	}

	/**
	 * Raid-defense bonus. GuardEntity calls this right after {@link #recordHostileKill} for every {@link Raider} kill,
	 * so it only pays out when that kill was flagged as happening during an active raid (a lone patrol pillager is
	 * just a hostile kill).
	 */
	public static void recordRaidDefense(ServerLevel world, UUID playerUuid) {
		Long flaggedTick = PENDING_RAID_DEFENSE.remove(playerUuid);
		if (flaggedTick != null && flaggedTick == world.getGameTime()) {
			applyNormalizedReputationDelta(world, playerUuid, RAID_DEFENSE_DELTA);
		}
	}

	/** Explicit form for callers that have the raider; prefer this over the two-argument overload. */
	public static void recordRaidDefense(ServerLevel world, UUID playerUuid, Raider raider) {
		if (isDuringActiveRaid(world, raider)) {
			applyNormalizedReputationDelta(world, playerUuid, RAID_DEFENSE_DELTA);
		}
	}

	/**
	 * Raider#die removes the raider from its raid before the killer is notified, so also look for an active raid
	 * around where it died.
	 */
	static boolean isDuringActiveRaid(ServerLevel world, Raider raider) {
		if (raider.hasActiveRaid()) {
			return true;
		}
		Raid raid = world.getRaidAt(raider.blockPosition());
		return raid != null && raid.isActive();
	}

	public static void recordHostileKill(ServerLevel world, UUID playerUuid, LivingEntity target) {
		if (target == null) {
			return;
		}
		double delta = target instanceof Raider ? RAIDER_KILL_DELTA : HOSTILE_KILL_DELTA;
		applyNormalizedReputationDelta(world, playerUuid, delta);
		if (target instanceof Raider raider && isDuringActiveRaid(world, raider)) {
			PENDING_RAID_DEFENSE.put(playerUuid, world.getGameTime());
		} else {
			PENDING_RAID_DEFENSE.remove(playerUuid);
		}
	}

	public static void tickDecay(ServerLevel world) {
		if (world.getGameTime() % DECAY_INTERVAL_TICKS != 0) {
			return;
		}
		getState(world.getServer()).decayAll(DECAY_STEP);
	}

	/** Drops session-only caches; called when the server stops so an integrated server does not leak them into the next world. */
	public static void clearSessionState() {
		TRADE_REPUTATION_COOLDOWN.clear();
		LAST_GUARD_HIT_TICK.clear();
		PENDING_RAID_DEFENSE.clear();
		lastCooldownCleanupTick = Long.MIN_VALUE;
	}

	/**
	 * Parses operator input for {@code /guards reputation}: {@code 0.00} to {@code 1.00} with at most two decimals.
	 *
	 * @return the value, or {@code null} when the input is invalid
	 */
	public static Double parseReputationInput(String input) {
		if (input == null) {
			return null;
		}
		String normalized = input.trim();
		if (!REPUTATION_INPUT_PATTERN.matcher(normalized).matches()) {
			return null;
		}
		BigDecimal value = new BigDecimal(normalized);
		if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
			return null;
		}
		return value.setScale(2, RoundingMode.UNNECESSARY).doubleValue();
	}

	public static String formatReputation(double value) {
		return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private static void cleanupSessionMaps(long worldTime) {
		// The second clause recovers if game time went backwards (e.g. /time set or a new integrated-server world).
		if (lastCooldownCleanupTick != Long.MIN_VALUE && worldTime >= lastCooldownCleanupTick
				&& worldTime - lastCooldownCleanupTick < COOLDOWN_CLEANUP_INTERVAL_TICKS) {
			return;
		}
		lastCooldownCleanupTick = worldTime;
		long cutoff = worldTime - COOLDOWN_RETENTION_TICKS;
		TRADE_REPUTATION_COOLDOWN.entrySet().removeIf(entry -> entry.getValue() < cutoff || entry.getValue() > worldTime);
		LAST_GUARD_HIT_TICK.entrySet().removeIf(entry -> entry.getValue() < cutoff || entry.getValue() > worldTime);
	}

	static double legacyDeltaToNormalized(int legacyDelta) {
		return legacyDelta / (double) LEGACY_RANGE_SPAN;
	}

	/** Collision-free key; the old XOR of both UUIDs could merge unrelated player/villager pairs. */
	private record TradeKey(UUID player, UUID villager) {
	}
}
