package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

public final class GuardRallyGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;

	public GuardRallyGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setControls(EnumSet.of(Control.MOVE));
	}

	@Override
	public boolean canStart() {
		return this.guard.isAiIntent(GuardAiIntent.RALLY)
				&& this.guard.getRallyPoint().isPresent();
	}

	@Override
	public boolean shouldContinue() {
		return this.guard.isAiIntent(GuardAiIntent.RALLY)
				&& this.guard.getRallyPoint().isPresent();
	}

	@Override
	public void stop() {
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		BlockPos rallyPoint = this.guard.getRallyPoint().orElse(null);
		if (rallyPoint == null) {
			return;
		}
		this.guard.getNavigation().startMovingTo(
				rallyPoint.getX() + 0.5D,
				rallyPoint.getY(),
				rallyPoint.getZ() + 0.5D,
				this.speed);
	}
}
