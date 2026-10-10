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
		cleanupTradeCooldown(now);
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

	public static void recordGuardHarm(ServerLevel world, UUID playerUuid) {
		applyNormalizedReputationDelta(world, playerUuid, GUARD_HARM_DELTA);
	}

	public static void recordRaidDefense(ServerLevel world, UUID playerUuid) {
		applyNormalizedReputationDelta(world, playerUuid, RAID_DEFENSE_DELTA);
	}

	public static void recordHostileKill(ServerLevel world, UUID playerUuid, LivingEntity target) {
		if (target == null) {
			return;
		}
		double delta = target instanceof Raider ? RAIDER_KILL_DELTA : HOSTILE_KILL_DELTA;
		applyNormalizedReputationDelta(world, playerUuid, delta);
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

	private static void cleanupTradeCooldown(long worldTime) {
		// The second clause recovers if game time went backwards (e.g. /time set or a new integrated-server world).
		if (lastCooldownCleanupTick != Long.MIN_VALUE && worldTime >= lastCooldownCleanupTick
				&& worldTime - lastCooldownCleanupTick < COOLDOWN_CLEANUP_INTERVAL_TICKS) {
			return;
		}
		lastCooldownCleanupTick = worldTime;
		long cutoff = worldTime - COOLDOWN_RETENTION_TICKS;
		TRADE_REPUTATION_COOLDOWN.entrySet().removeIf(entry -> entry.getValue() < cutoff || entry.getValue() > worldTime);
	}

	static double legacyDeltaToNormalized(int legacyDelta) {
		return legacyDelta / (double) LEGACY_RANGE_SPAN;
	}

	/** Collision-free key; the old XOR of both UUIDs could merge unrelated player/villager pairs. */
	private record TradeKey(UUID player, UUID villager) {
	}
}
