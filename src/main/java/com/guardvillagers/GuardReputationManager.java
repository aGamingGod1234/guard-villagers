package com.guardvillagers;

import com.guardvillagers.data.GuardReputationState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerGossipType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuardReputationManager {
	private static final int TRUST_THRESHOLD = -10;
	private static final int HOSTILE_THRESHOLD = -35;
	private static final int MAX_GOSSIP_VILLAGERS = 24;
	private static final Map<UUID, Long> TRADE_REPUTATION_COOLDOWN = new ConcurrentHashMap<>();

	private GuardReputationManager() {
	}

	public static GuardReputationState getState(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(GuardReputationState.TYPE);
	}

	public static int getEffectiveReputation(ServerWorld world, UUID playerUuid, BlockPos reference, int radius) {
		int manual = getState(world.getServer()).get(playerUuid);
		int gossip = getGossipScore(world, playerUuid, reference, radius);
		return manual + gossip;
	}

	public static int getEffectiveReputation(ServerPlayerEntity player) {
		int reputation = getEffectiveReputation(player.getEntityWorld(), player.getUuid(), player.getBlockPos(), 64);
		if (player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE)) {
			reputation += 10;
		}
		return reputation;
	}

	public static boolean isTrustedByGuards(ServerWorld world, UUID playerUuid, BlockPos reference) {
		return getEffectiveReputation(world, playerUuid, reference, 64) >= TRUST_THRESHOLD;
	}

	public static boolean shouldGuardsTurnHostile(ServerWorld world, UUID playerUuid, BlockPos reference) {
		return getEffectiveReputation(world, playerUuid, reference, 64) <= HOSTILE_THRESHOLD;
	}

	public static int applyReputationDelta(ServerWorld world, UUID playerUuid, int delta) {
		return getState(world.getServer()).add(playerUuid, delta);
	}

	public static int getAdjustedGuardCost(ServerPlayerEntity player, int baseCost) {
		int rep = getEffectiveReputation(player);
		double discountFactor = rep >= 0
			? 1.0D - Math.min(0.45D, rep / 200.0D)
			: 1.0D + Math.min(0.75D, Math.abs(rep) / 120.0D);
		int adjusted = (int) Math.round(baseCost * discountFactor);
		return Math.max(1, adjusted);
	}

	public static void recordTradeInteraction(ServerPlayerEntity player, VillagerEntity villager) {
		long now = player.getEntityWorld().getTime();
		UUID key = mix(player.getUuid(), villager.getUuid());
		long previous = TRADE_REPUTATION_COOLDOWN.getOrDefault(key, Long.MIN_VALUE);
		if (now - previous < 200) {
			return;
		}

		TRADE_REPUTATION_COOLDOWN.put(key, now);
		villager.getGossip().startGossip(player.getUuid(), VillagerGossipType.TRADING, 2);
		applyReputationDelta(player.getEntityWorld(), player.getUuid(), 1);
	}

	public static void recordVillagerHarm(ServerWorld world, UUID playerUuid) {
		applyReputationDelta(world, playerUuid, -8);
	}

	public static void recordGuardHarm(ServerWorld world, UUID playerUuid) {
		applyReputationDelta(world, playerUuid, -6);
	}

	public static void recordRaidDefense(ServerWorld world, UUID playerUuid) {
		applyReputationDelta(world, playerUuid, 4);
	}

	public static void recordHostileKill(ServerWorld world, UUID playerUuid, LivingEntity target) {
		if (target == null) {
			return;
		}
		int delta = target.getType().toString().contains("raider") ? 4 : 2;
		applyReputationDelta(world, playerUuid, delta);
	}

	private static int getGossipScore(ServerWorld world, UUID playerUuid, BlockPos reference, int radius) {
		Box box = new Box(reference).expand(radius);
		int score = 0;
		int counted = 0;
		ServerPlayerEntity resolvedPlayer = world.getServer().getPlayerManager().getPlayer(playerUuid);
		for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
			if (resolvedPlayer != null) {
				score += villager.getReputation(resolvedPlayer);
			} else {
				score += villager.getGossip().getReputationFor(playerUuid, type -> true);
			}
			counted++;
			if (counted >= MAX_GOSSIP_VILLAGERS) {
				break;
			}
		}
		return score / Math.max(1, counted);
	}

	private static UUID mix(UUID a, UUID b) {
		long msb = a.getMostSignificantBits() ^ b.getMostSignificantBits();
		long lsb = a.getLeastSignificantBits() ^ b.getLeastSignificantBits();
		return new UUID(msb, lsb);
	}
}
