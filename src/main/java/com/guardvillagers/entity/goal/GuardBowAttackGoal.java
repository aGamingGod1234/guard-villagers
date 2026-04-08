package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.item.BowItem;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.EnumSet;

public final class GuardBowAttackGoal extends Goal {
	private static final int MIN_PULL_TICKS = 20;

	private final GuardEntity guard;
	private final double speed;
	private final float squaredRange;
	private int targetVisibleTicks;

	public GuardBowAttackGoal(GuardEntity guard, double speed, float range) {
		this.guard = guard;
		this.speed = speed;
		this.squaredRange = range * range;
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
		this.guard.setAttacking(true);
	}

	@Override
	public void stop() {
		this.guard.setAttacking(false);
		this.targetVisibleTicks = 0;
		this.guard.clearActiveItem();
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		LivingEntity target = this.guard.getTarget();
		if (target == null || !(this.guard.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld world)) {
			return;
		}

		double distanceSq = this.guard.squaredDistanceTo(target.getX(), target.getY(), target.getZ());
		boolean canSeeTarget = this.guard.canSee(target);
		boolean sawTargetLastTick = this.targetVisibleTicks > 0;
		if (canSeeTarget != sawTargetLastTick) {
			this.targetVisibleTicks = 0;
		}
		if (canSeeTarget) {
			this.targetVisibleTicks++;
		} else {
			this.targetVisibleTicks = Math.max(this.targetVisibleTicks - 1, -60);
		}

		if (distanceSq <= (double) this.squaredRange && this.targetVisibleTicks >= 20) {
			this.guard.getNavigation().stop();
		} else {
			this.guard.getGuardNavigation().startMovingToDynamic(this.guard.resolveCombatApproachSlot(world, target),
					this.speed);
		}

		this.guard.getLookControl().lookAt(target, 30.0F, 30.0F);
		if (this.guard.isUsingItem()) {
			if (!canSeeTarget && this.targetVisibleTicks < -60) {
				this.guard.clearActiveItem();
			} else if (canSeeTarget) {
				int useTicks = this.guard.getItemUseTime();
				if (useTicks >= MIN_PULL_TICKS) {
					this.guard.clearActiveItem();
					this.guard.shootAt(target, BowItem.getPullProgress(useTicks));
				}
			}
		} else if (this.targetVisibleTicks >= -60) {
			this.guard.setCurrentHand(Hand.MAIN_HAND);
		}
	}

	private boolean hasValidTarget() {
		LivingEntity target = this.guard.getTarget();
		return this.guard.isAiIntent(GuardAiIntent.ENGAGE_TARGET)
			&& target != null
			&& target.isAlive()
			&& this.guard.getMainHandStack().isOf(Items.BOW);
	}
}
