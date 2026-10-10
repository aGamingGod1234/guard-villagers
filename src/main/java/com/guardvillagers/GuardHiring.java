package com.guardvillagers;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;

/** Hiring personal guards from the shop: pricing, payment, spawn and refund. */
public final class GuardHiring {
	private static final int SPAWN_SEARCH_RANGE = 10;

	public enum Result {
		SUCCESS,
		NOT_TRUSTED,
		LIMIT_REACHED,
		INSUFFICIENT_FUNDS,
		SPAWN_FAILED,
		INTERNAL_ERROR
	}

	public record BatchResult(Result result, int spawnedCount, List<String> guardNames) {
		public BatchResult {
			guardNames = List.copyOf(guardNames);
		}

		static BatchResult of(Result result) {
			return new BatchResult(result, 0, List.of());
		}
	}

	private GuardHiring() {
	}

	public static int getAdjustedGuardCost(ServerPlayer player) {
		return GuardHirePricing.applyReputationModifier(GuardVillagersMod.getUpgradesView(player).getGuardCost(),
				GuardReputationManager.getEffectiveReputation(player));
	}

	public static BatchResult hire(ServerPlayer player, int requestedCount) {
		ServerLevel world = player.level();
		if (!GuardReputationManager.isTrustedByGuards(world, player.getUUID(), player.blockPosition())) {
			return BatchResult.of(Result.NOT_TRUSTED);
		}

		boolean creativeMode = player.getAbilities().instabuild;
		int costPerGuard = getAdjustedGuardCost(player);
		int toSpawn = Math.max(1, requestedCount);
		if (!creativeMode) {
			toSpawn = Math.min(toSpawn, GuardEconomy.countEmeraldBlocks(player.getInventory()) / costPerGuard);
			if (toSpawn <= 0) {
				return BatchResult.of(Result.INSUFFICIENT_FUNDS);
			}
		}

		int ownedGuardCount = GuardOwnershipIndex.countOwnedGuards(world.getServer(), player.getUUID());
		int remainingSlots = GuardSecurityLimits.remainingPersonalGuardSlots(ownedGuardCount);
		if (remainingSlots <= 0) {
			return BatchResult.of(Result.LIMIT_REACHED);
		}
		toSpawn = Math.min(toSpawn, remainingSlots);

		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgradesView(player);
		GuardRole[] roles = GuardRole.values();
		int startRoleIndex = ownedGuardCount % roles.length;
		int spawned = 0;
		// Emerald blocks taken for a guard that has not been confirmed in the world yet; refunded on any failure.
		int pendingDebit = 0;
		List<String> spawnedNames = new ArrayList<>();
		try {
			for (int i = 0; i < toSpawn; i++) {
				if (!GuardEconomy.spendEmeraldBlocks(player, costPerGuard)) {
					break;
				}
				pendingDebit = creativeMode ? 0 : costPerGuard;
				GuardEntity spawnedGuard = trySpawn(world, player, upgrades, roles[(startRoleIndex + i) % roles.length]);
				if (spawnedGuard == null) {
					break;
				}
				pendingDebit = 0;
				spawned++;
				spawnedNames.add(spawnedGuard.getName().getString());
			}
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.error("Guard purchase crashed for {}", player.getName().getString(), exception);
			GuardEconomy.refundEmeraldBlocks(player, pendingDebit);
			return new BatchResult(Result.INTERNAL_ERROR, spawned, spawnedNames);
		}
		GuardEconomy.refundEmeraldBlocks(player, pendingDebit);

		if (spawned > 0) {
			return new BatchResult(Result.SUCCESS, spawned, spawnedNames);
		}
		return BatchResult.of(Result.SPAWN_FAILED);
	}

	private static GuardEntity trySpawn(ServerLevel world, ServerPlayer player, GuardPlayerUpgrades upgrades, GuardRole role) {
		BlockPos candidate = new BlockPos(Mth.floor(player.getX()), player.getBlockY(), Mth.floor(player.getZ()));
		BlockPos spawn = GuardVillagersMod.findNearbyGuardSpawnPos(world, candidate, SPAWN_SEARCH_RANGE);
		if (spawn == null) {
			return null;
		}
		GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(world, EntitySpawnReason.EVENT);
		if (guard == null) {
			return null;
		}

		guard.snapTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, player.getYRot(), 0.0F);
		guard.setOwnerUuid(player.getUUID());
		guard.setStaying(false);
		guard.setFollowOverride(true);
		guard.setRole(role);
		guard.applyPurchasedLoadout(world, upgrades);
		guard.setSquadLeader(false);
		if (world.addFreshEntity(guard)) {
			return guard;
		}
		GuardOwnershipIndex.untrack(guard);
		return null;
	}
}
