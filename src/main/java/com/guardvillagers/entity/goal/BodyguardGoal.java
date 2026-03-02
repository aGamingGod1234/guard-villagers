package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;

public final class BodyguardGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private LivingEntity protectTarget;

	public BodyguardGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		if (this.guard.getBehavior() != GuardBehavior.BODYGUARD || !this.guard.canExecuteBehaviorGoals()) {
			return false;
		}
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world)) {
			return false;
		}
		this.protectTarget = this.guard.findBodyguardTarget(world);
		return this.protectTarget != null;
	}

	@Override
	public boolean shouldContinue() {
		return this.guard.getBehavior() == GuardBehavior.BODYGUARD
			&& this.guard.canExecuteBehaviorGoals()
			&& this.protectTarget != null
			&& this.protectTarget.isAlive();
	}

	@Override
	public void stop() {
		this.protectTarget = null;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (this.protectTarget == null || !this.protectTarget.isAlive()) {
			return;
		}

		this.guard.getLookControl().lookAt(this.protectTarget, 30.0F, 30.0F);
		double distanceSq = this.guard.squaredDistanceTo(this.protectTarget);
		if (distanceSq > 25.0D) {
			this.guard.getNavigation().startMovingTo(this.protectTarget, this.speed);
		} else if (distanceSq < 9.0D) {
			this.guard.getNavigation().stop();
		}

		LivingEntity attacker = this.protectTarget.getAttacker();
		if (attacker instanceof HostileEntity hostile && hostile.isAlive() && this.guard.canTargetWithinZone(hostile.getBlockPos())) {
			this.guard.setPriorityTarget(hostile);
		}
	}
}
