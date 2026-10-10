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
	/** How far above or below the buyer's feet a hired guard may appear, always in the buyer's own column. */
	static final int SPAWN_VERTICAL_RANGE = 3;

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

	/**
	 * README: hired guards spawn on the buyer's exact X/Z column or the purchase fails cleanly. Searches feet level
	 * first, then up/down to {@link #SPAWN_VERTICAL_RANGE}, and only accepts a spot connected to the buyer through
	 * open blocks, so a guard is never dropped into a sealed pocket under the floor.
	 */
	static BlockPos findSpawnInBuyerColumn(ServerLevel world, ServerPlayer player) {
		BlockPos feet = new BlockPos(Mth.floor(player.getX()), player.getBlockY(), Mth.floor(player.getZ()));
		for (int offset : spawnOffsets()) {
			BlockPos candidate = feet.above(offset);
			if (GuardVillagersMod.canGuardSpawnAt(world, candidate) && isOpenBetween(world, feet, candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/** 0, +1, -1, +2, -2, ... nearest first, preferring up. */
	static int[] spawnOffsets() {
		int[] offsets = new int[SPAWN_VERTICAL_RANGE * 2 + 1];
		for (int step = 1; step <= SPAWN_VERTICAL_RANGE; step++) {
			offsets[step * 2 - 1] = step;
			offsets[step * 2] = -step;
		}
		return offsets;
	}

	/** Every block strictly between the buyer's feet and the candidate (same column) must be passable. */
	private static boolean isOpenBetween(ServerLevel world, BlockPos feet, BlockPos candidate) {
		int step = Integer.signum(candidate.getY() - feet.getY());
		for (int y = feet.getY() + step; y != candidate.getY(); y += step) {
			BlockPos between = new BlockPos(feet.getX(), y, feet.getZ());
			if (!world.getBlockState(between).getCollisionShape(world, between).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static GuardEntity trySpawn(ServerLevel world, ServerPlayer player, GuardPlayerUpgrades upgrades, GuardRole role) {
		BlockPos spawn = findSpawnInBuyerColumn(world, player);
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
