package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import com.guardvillagers.entity.ai.GuardAiIntent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;

public final class GuardMeleeAttackGoal extends Goal {
	private static final int ATTACK_INTERVAL_TICKS = 20;
	private static final int CHASE_REPATH_INTERVAL_TICKS = 4;
	private static final float CHASE_LOOK_YAW = 30.0F;
	private static final float CHASE_LOOK_PITCH = 0.0F;
	private static final float ATTACK_LOOK_CHANGE = 30.0F;

	private final GuardEntity guard;
	private final double speed;
	private int repathTicks;
	private int attackCooldown;

	public GuardMeleeAttackGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		return this.hasValidTarget();
	}

	@Override
	public boolean shouldContinue() {
		return this.hasValidTarget();
	}

	@Override
	public void start() {
		this.repathTicks = 0;
		this.attackCooldown = 0;
	}

	@Override
	public void stop() {
		this.repathTicks = 0;
		this.attackCooldown = 0;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!this.hasValidTarget()) {
			this.guard.getNavigation().stop();
			return;
		}

		if (this.attackCooldown > 0) {
			this.attackCooldown--;
		}

		LivingEntity target = this.guard.getTarget();
		if (target == null || !(this.guard.getEntityWorld() instanceof ServerWorld world)) {
			this.guard.getNavigation().stop();
			return;
		}

		double distanceSq = this.guard.squaredDistanceTo(target);
		if (distanceSq <= this.getSquaredAttackRange(target)) {
			this.guard.getNavigation().stop();
			this.repathTicks = 0;
			this.guard.getLookControl().lookAt(target, ATTACK_LOOK_CHANGE, ATTACK_LOOK_CHANGE);
			if (this.attackCooldown <= 0) {
				this.attackCooldown = this.getTickCount(ATTACK_INTERVAL_TICKS);
				this.guard.tryAttack(world, target);
			}
			return;
		}

		this.guard.getLookControl().lookAt(target, CHASE_LOOK_YAW, CHASE_LOOK_PITCH);
		if (this.repathTicks-- <= 0) {
			this.repathTicks = this.getTickCount(CHASE_REPATH_INTERVAL_TICKS);
			this.guard.getGuardNavigation().startMovingToDynamic(
					this.guard.resolveCombatApproachSlot(world, target),
					this.speed);
		}
	}

	private boolean hasValidTarget() {
		LivingEntity target = this.guard.getTarget();
		return this.guard.isAiIntent(GuardAiIntent.ENGAGE_TARGET)
				&& this.guard.getRole() == GuardRole.SWORDSMAN
				&& target != null
				&& target.isAlive();
	}

	private double getSquaredAttackRange(LivingEntity target) {
		double attackReach = this.guard.getWidth() * 2.0F;
		return attackReach * attackReach + target.getWidth();
	}
}
