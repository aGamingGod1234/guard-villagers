package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import com.guardvillagers.navigation.GuardGrounding;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * An otherwise idle guard that is swimming heads for the nearest dry standing spot (falling back
 * to the last land position). This intent now ranks below follow and combat, so it no longer
 * drags a guard back to the bank it came from while it is crossing water behind its owner.
 */
public final class ReturnToLandGoal extends Goal {
	private static final int RETARGET_TICKS = 40;

	private final GuardEntity guard;
	private final double speed;
	private BlockPos destination;
	private long nextRetarget;

	public ReturnToLandGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETURN_TO_LAND);
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETURN_TO_LAND);
	}

	@Override
	public void start() {
		this.guard.setPathfindingMalus(PathType.WATER, 0.0F);
		this.destination = null;
		this.nextRetarget = 0L;
	}

	@Override
	public void stop() {
		this.guard.getNavigation().stop();
		this.guard.setPathfindingMalus(PathType.WATER, GuardEntity.DEFAULT_WATER_MALUS);
		this.destination = null;
	}

	@Override
	public void tick() {
		long now = this.guard.level().getGameTime();
		if (now >= this.nextRetarget) {
			this.nextRetarget = now + RETARGET_TICKS;
			BlockPos exit = this.guard.getGuardNavigation().findNearbyDryExit(this::canReachDryGround);
			BlockPos lastLand = this.guard.getLastLandPos();
			this.destination = exit;
			if (this.destination == null && lastLand != null && this.canReachDryGround(lastLand)) {
				this.destination = lastLand;
			}
		}
		if (this.destination != null) {
			this.guard.getGuardNavigation().startMovingToStatic(this.destination, this.speed, 0);
		}
	}

	private boolean canReachDryGround(BlockPos pos) {
		if (!this.guard.level().isLoaded(pos) || !this.guard.level().getFluidState(pos).isEmpty()
				|| !GuardGrounding.isStandable(this.guard, pos)) {
			return false;
		}
		var path = this.guard.getNavigation().createPath(pos, 0);
		return path != null && path.canReach() && path.getEndNode() != null && path.getEndNode().asBlockPos().equals(pos);
	}
}
