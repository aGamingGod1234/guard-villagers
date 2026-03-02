package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;

import java.util.EnumSet;

public final class RaidTacticsGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private Raid raid;

	public RaidTacticsGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setControls(EnumSet.of(Control.MOVE, Control.TARGET));
	}

	@Override
	public boolean canStart() {
		if (!this.guard.canExecuteBehaviorGoals()) {
			return false;
		}
		GuardBehavior behavior = this.guard.getBehavior();
		if (behavior != GuardBehavior.OFFENSIVE && behavior != GuardBehavior.DEFENSIVE) {
			return false;
		}
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world)) {
			return false;
		}
		this.raid = world.getRaidAt(this.guard.getBlockPos());
		return this.raid != null && this.raid.isActive();
	}

	@Override
	public boolean shouldContinue() {
		if (this.raid == null || !this.raid.isActive()) {
			return false;
		}
		GuardBehavior behavior = this.guard.getBehavior();
		return behavior == GuardBehavior.OFFENSIVE || behavior == GuardBehavior.DEFENSIVE;
	}

	@Override
	public void stop() {
		this.raid = null;
	}

	@Override
	public void tick() {
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world) || this.raid == null) {
			return;
		}

		GuardBehavior behavior = this.guard.getBehavior();
		if (behavior == GuardBehavior.OFFENSIVE) {
			RaiderEntity target = findNearestRaider(world);
			if (target != null) {
				this.guard.setPriorityTarget(target);
				if (this.guard.squaredDistanceTo(target) > 16.0D) {
					this.guard.getNavigation().startMovingTo(target, this.speed);
				}
			}
			return;
		}

		if (behavior == GuardBehavior.DEFENSIVE) {
			BlockPos center = this.raid.getCenter();
			double distanceSq = this.guard.squaredDistanceTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D);
			if (distanceSq > 25.0D) {
				this.guard.getNavigation().startMovingTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, this.speed);
			} else {
				this.guard.getNavigation().stop();
			}
		}
	}

	private RaiderEntity findNearestRaider(ServerWorld world) {
		RaiderEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (RaiderEntity raider : world.getEntitiesByClass(
			RaiderEntity.class,
			this.guard.getBoundingBox().expand(48.0D),
			entity -> entity.isAlive() && this.guard.canTargetWithinZone(entity.getBlockPos()))
		) {
			double distanceSq = this.guard.squaredDistanceTo(raider);
			if (distanceSq < bestDistance) {
				bestDistance = distanceSq;
				best = raider;
			}
		}
		return best;
	}
}
