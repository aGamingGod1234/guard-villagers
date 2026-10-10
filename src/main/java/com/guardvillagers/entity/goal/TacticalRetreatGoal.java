package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

public final class TacticalRetreatGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private BlockPos safePoint;
	private int recalculateTicks;

	public TacticalRetreatGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.TARGET));
	}

	@Override
	public boolean canUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETREAT);
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETREAT);
	}

	@Override
	public void start() {
		this.safePoint = null;
		this.recalculateTicks = 0;
	}

	@Override
	public void stop() {
		this.safePoint = null;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!(this.guard.level() instanceof ServerLevel world)) {
			return;
		}

		if (this.recalculateTicks-- <= 0 || this.safePoint == null) {
			this.safePoint = this.guard.findSafeRetreatPoint(world);
			this.recalculateTicks = 20;
		}

		if (this.safePoint != null) {
			this.guard.getGuardNavigation().startMovingToStatic(this.safePoint, this.speed);
		}
	}
}
