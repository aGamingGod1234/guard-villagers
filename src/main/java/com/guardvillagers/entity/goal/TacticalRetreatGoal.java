package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

public final class TacticalRetreatGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private BlockPos safePoint;
	private int recalculateTicks;

	public TacticalRetreatGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setControls(EnumSet.of(Control.MOVE, Control.TARGET));
	}

	@Override
	public boolean canStart() {
		return this.guard.shouldTacticallyRetreat();
	}

	@Override
	public boolean shouldContinue() {
		return this.guard.shouldContinueRetreat();
	}

	@Override
	public void start() {
		this.guard.setRetreating(true);
		this.safePoint = null;
		this.recalculateTicks = 0;
	}

	@Override
	public void stop() {
		this.guard.setRetreating(false);
		this.safePoint = null;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}

		if (this.recalculateTicks-- <= 0 || this.safePoint == null) {
			this.safePoint = this.guard.findSafeRetreatPoint(world);
			this.recalculateTicks = 20;
		}

		if (this.safePoint != null) {
			this.guard.getNavigation().startMovingTo(this.safePoint.getX() + 0.5D, this.safePoint.getY(), this.safePoint.getZ() + 0.5D, this.speed);
		}
		this.guard.setTarget(null);
	}
}
