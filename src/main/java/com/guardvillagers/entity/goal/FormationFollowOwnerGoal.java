package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

public final class FormationFollowOwnerGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private ServerPlayerEntity owner;

	public FormationFollowOwnerGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setControls(EnumSet.of(Control.MOVE));
	}

	@Override
	public boolean canStart() {
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world)) {
			return false;
		}
		this.owner = this.guard.resolveOwner(world);
		return this.owner != null && this.guard.canFollowOwnerFormation();
	}

	@Override
	public boolean shouldContinue() {
		return this.owner != null && this.owner.isAlive() && this.guard.canFollowOwnerFormation();
	}

	@Override
	public void stop() {
		this.owner = null;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (this.owner == null) {
			return;
		}

		Vec3d formationPoint = this.guard.getFormationAnchor(this.owner);
		double distanceSq = this.guard.squaredDistanceTo(formationPoint);
		if (distanceSq > 324.0D) {
			this.guard.teleportToFormationAnchor(formationPoint);
			return;
		}

		if (distanceSq > 4.0D) {
			this.guard.getNavigation().startMovingTo(formationPoint.x, formationPoint.y, formationPoint.z, this.speed);
		} else {
			this.guard.getNavigation().stop();
		}
	}
}
