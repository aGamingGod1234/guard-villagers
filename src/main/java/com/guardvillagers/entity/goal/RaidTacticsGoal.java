package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardBehaviorExecutor;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;

public final class RaidTacticsGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private Raid raid;

	public RaidTacticsGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.TARGET));
	}

	@Override
	public boolean canUse() {
		if (!(this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_OFFENSIVE)
				|| this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_DEFENSIVE))) {
			return false;
		}
		if (!(this.guard.level() instanceof ServerLevel world)) {
			return false;
		}
		this.raid = world.getRaidAt(this.guard.blockPosition());
		return this.raid != null && this.raid.isActive();
	}

	@Override
	public boolean canContinueToUse() {
		if (this.raid == null || !this.raid.isActive()) {
			return false;
		}
		return this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_OFFENSIVE)
				|| this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_DEFENSIVE);
	}

	@Override
	public void stop() {
		this.raid = null;
	}

	@Override
	public void tick() {
		if (!(this.guard.level() instanceof ServerLevel world) || this.raid == null) {
			return;
		}

		if (this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_OFFENSIVE)) {
			Raider target = findNearestRaider(world);
			if (target != null) {
				if (this.guard.distanceToSqr(target) > 16.0D) {
					this.guard.getGuardNavigation().startMovingToDynamic(this.guard.resolveCombatApproachSlot(world, target),
							this.speed);
				} else {
					this.guard.getNavigation().stop();
				}
			} else {
				BlockPos center = this.raid.getCenter();
				BlockPos slot = this.guard.resolveGroundMovementSlot(world, center, 1.75D, false);
				this.guard.getGuardNavigation().startMovingToStatic(slot, this.speed);
			}
			return;
		}

		if (this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_DEFENSIVE)) {
			BlockPos center = this.raid.getCenter();
			BlockPos slot = this.guard.resolveGroundMovementSlot(world, center, 1.75D, false);
			double distanceSq = this.guard.distanceToSqr(slot.getX() + 0.5D, slot.getY(), slot.getZ() + 0.5D);
			if (distanceSq > 25.0D) {
				this.guard.getGuardNavigation().startMovingToStatic(slot, this.speed);
			} else {
				this.guard.getNavigation().stop();
			}
		}
	}

	private Raider findNearestRaider(ServerLevel world) {
		Raider best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Raider raider : world.getEntitiesOfClass(
			Raider.class,
			this.guard.getBoundingBox().inflate(48.0D),
			entity -> entity.isAlive() && this.guard.canTargetWithinZone(entity.blockPosition()))
		) {
			double distanceSq = this.guard.distanceToSqr(raider);
			if (distanceSq < bestDistance) {
				bestDistance = distanceSq;
				best = raider;
			}
		}
		return best;
	}
}
