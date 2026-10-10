package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * After a hunt ends (target lost/killed/aborted) while the guard is in water,
 * path back to the last known land position, then resume normal behavior.
 */
public final class ReturnToLandGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private float oldWaterPenalty;
	private int repathTicks;

	public ReturnToLandGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETURN_TO_LAND)
				&& this.guard.getLastLandPos() != null;
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETURN_TO_LAND)
				&& this.guard.getLastLandPos() != null;
	}

	@Override
	public void start() {
		this.oldWaterPenalty = this.guard.getPathfindingMalus(PathType.WATER);
		this.guard.setPathfindingMalus(PathType.WATER, 0.0F);
		this.repathTicks = 0;
	}

	@Override
	public void stop() {
		this.guard.getNavigation().stop();
		this.guard.setPathfindingMalus(PathType.WATER, this.oldWaterPenalty);
	}

	@Override
	public void tick() {
		if (--this.repathTicks > 0) {
			return;
		}
		this.repathTicks = 10;

		BlockPos landPos = this.guard.getLastLandPos();
		if (landPos == null) {
			return;
		}
		this.guard.getNavigation().moveTo(
			landPos.getX() + 0.5D, landPos.getY(), landPos.getZ() + 0.5D, this.speed
		);
	}
}
