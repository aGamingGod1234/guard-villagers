package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardBehaviorExecutor;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

public final class CrowdControlGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private BlockPos anchor;
	private int recalculateTicks;

	public CrowdControlGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.guard.isBehaviorExecutor(GuardBehaviorExecutor.CROWD_CONTROL)
			&& this.resolveAnchor();
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isBehaviorExecutor(GuardBehaviorExecutor.CROWD_CONTROL)
			&& this.anchor != null;
	}

	@Override
	public void stop() {
		this.anchor = null;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (this.anchor == null || !(this.guard.level() instanceof ServerLevel world)) {
			return;
		}
		if (this.recalculateTicks-- <= 0) {
			this.resolveAnchor();
			this.recalculateTicks = 100;
		}

		BlockPos slot = this.guard.resolveGroundMovementSlot(world, this.anchor, 1.75D, false);
		double distanceSq = this.guard.distanceToSqr(slot.getX() + 0.5D, slot.getY(), slot.getZ() + 0.5D);
		if (distanceSq > 2.25D) {
			this.guard.getGuardNavigation().startMovingToStatic(slot, this.speed);
		} else {
			this.guard.getNavigation().stop();
		}
	}

	private boolean resolveAnchor() {
		if (!(this.guard.level() instanceof ServerLevel world)) {
			return false;
		}

		Optional<BlockPos> meetingPoint = world.getPoiManager().findClosest(
			entry -> entry.is(PoiTypes.MEETING),
			this.guard.blockPosition(),
			64,
			PoiManager.Occupancy.ANY
		);

		this.anchor = meetingPoint.orElse(this.guard.getHome().orElse(this.guard.blockPosition()));
		return this.anchor != null;
	}
}
