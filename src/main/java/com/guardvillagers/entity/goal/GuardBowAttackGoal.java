package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import java.util.EnumSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;

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
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.hasValidTarget();
	}

	@Override
	public boolean canContinueToUse() {
		return this.hasValidTarget() || !this.guard.getNavigation().isDone();
	}

	@Override
	public void start() {
		this.guard.setAggressive(true);
	}

	@Override
	public void stop() {
		this.guard.setAggressive(false);
		this.targetVisibleTicks = 0;
		this.guard.stopUsingItem();
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		LivingEntity target = this.guard.getTarget();
		if (target == null || !(this.guard.level() instanceof net.minecraft.server.level.ServerLevel world)) {
			return;
		}

		double distanceSq = this.guard.distanceToSqr(target.getX(), target.getY(), target.getZ());
		boolean canSeeTarget = this.guard.hasLineOfSight(target);
		boolean sawTargetLastTick = this.targetVisibleTicks > 0;
		if (canSeeTarget != sawTargetLastTick) {
			this.targetVisibleTicks = 0;
		}
		if (canSeeTarget) {
			this.targetVisibleTicks++;
		} else {
			this.targetVisibleTicks--;
		}

		if (distanceSq <= (double) this.squaredRange && this.targetVisibleTicks >= 20) {
			this.guard.getNavigation().stop();
		} else {
			this.guard.getGuardNavigation().startMovingToDynamic(this.guard.resolveCombatApproachSlot(world, target),
					this.speed);
		}

		this.guard.getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (this.guard.isUsingItem()) {
			if (!canSeeTarget && this.targetVisibleTicks < -60) {
				this.guard.stopUsingItem();
			} else if (canSeeTarget) {
				int useTicks = this.guard.getTicksUsingItem();
				if (useTicks >= MIN_PULL_TICKS) {
					this.guard.stopUsingItem();
					this.guard.performRangedAttack(target, BowItem.getPowerForTime(useTicks));
				}
			}
		} else if (this.targetVisibleTicks >= -60) {
			this.guard.startUsingItem(InteractionHand.MAIN_HAND);
		}
	}

	private boolean hasValidTarget() {
		LivingEntity target = this.guard.getTarget();
		return this.guard.isAiIntent(GuardAiIntent.ENGAGE_TARGET)
			&& target != null
			&& target.isAlive()
			&& this.guard.getMainHandItem().is(Items.BOW);
	}
}
