package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;

public final class FormationFollowOwnerGoal extends Goal {
	private static final int REPATH_INTERVAL_TICKS = 10;
	private static final double RESUME_RANGE_SQ = 128.0D * 128.0D;

	private final GuardEntity guard;
	private final double speed;
	private final double startDistanceSq;
	private final double stopDistanceSq;
	private ServerPlayerEntity owner;
	private int updateCountdownTicks;
	private float oldWaterPathfindingPenalty;

	public FormationFollowOwnerGoal(GuardEntity guard, double speed) {
		this(guard, speed, 8.0D, 4.0D);
	}

	public FormationFollowOwnerGoal(GuardEntity guard, double speed, double startDistanceSq, double stopDistanceSq) {
		this.guard = guard;
		this.speed = speed;
		this.startDistanceSq = Math.max(1.0D, startDistanceSq);
		this.stopDistanceSq = Math.max(1.0D, stopDistanceSq);
		this.setControls(EnumSet.of(Control.MOVE));
	}

	@Override
	public boolean canStart() {
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world)) {
			return false;
		}
		ServerPlayerEntity resolvedOwner = this.guard.resolveOwner(world);
		if (resolvedOwner == null || resolvedOwner.isSpectator()) {
			return false;
		}
		if (!this.guard.canFollowOwnerFormation()) {
			return false;
		}
		if (this.guard.squaredDistanceTo(resolvedOwner) > RESUME_RANGE_SQ) {
			return false;
		}
		if (this.guard.squaredDistanceTo(resolvedOwner) < this.startDistanceSq) {
			return false;
		}
		this.owner = resolvedOwner;
		return true;
	}

	@Override
	public boolean shouldContinue() {
		if (this.owner == null || !this.owner.isAlive()) {
			return false;
		}
		if (!this.guard.canFollowOwnerFormation()) {
			return false;
		}
		if (this.guard.squaredDistanceTo(this.owner) > RESUME_RANGE_SQ) {
			return false;
		}
		return this.guard.squaredDistanceTo(this.owner) > this.stopDistanceSq;
	}

	@Override
	public void start() {
		this.updateCountdownTicks = 0;
		this.oldWaterPathfindingPenalty = this.guard.getPathfindingPenalty(PathNodeType.WATER);
		this.guard.setPathfindingPenalty(PathNodeType.WATER, 0.0F);
	}

	@Override
	public void stop() {
		this.owner = null;
		this.updateCountdownTicks = 0;
		this.guard.getNavigation().stop();
		this.guard.setPathfindingPenalty(PathNodeType.WATER, this.oldWaterPathfindingPenalty);
	}

	@Override
	public void tick() {
		if (this.owner == null) {
			return;
		}

		this.guard.getLookControl().lookAt(this.owner, 10.0F, this.guard.getMaxLookPitchChange());
		if (--this.updateCountdownTicks > 0) {
			return;
		}
		this.updateCountdownTicks = this.getTickCount(REPATH_INTERVAL_TICKS);

		if (this.guard.isLeashed() || this.guard.hasVehicle()) {
			return;
		}

		this.guard.getNavigation().startMovingTo(this.owner, this.speed);
	}
}
