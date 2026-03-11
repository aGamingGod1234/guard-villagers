package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;

public final class GuardIdleGoal extends WanderAroundFarGoal {
	private final GuardEntity guard;

	public GuardIdleGoal(GuardEntity guard, double speed) {
		super(guard, speed);
		this.guard = guard;
	}

	@Override
	public boolean canStart() {
		return this.guard.isAiIntent(GuardAiIntent.IDLE)
				&& !this.guard.isStaying()
				&& super.canStart();
	}

	@Override
	public boolean shouldContinue() {
		return this.guard.isAiIntent(GuardAiIntent.IDLE)
				&& !this.guard.isStaying()
				&& super.shouldContinue();
	}
}
