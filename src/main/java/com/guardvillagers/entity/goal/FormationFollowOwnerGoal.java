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
	private int repathCooldown;
	private int teleportCooldown;

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
		this.repathCooldown = 0;
		this.teleportCooldown = 0;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (this.owner == null) {
			return;
		}

		Vec3d formationPoint = this.guard.getFormationAnchor(this.owner);
		double distanceSq = this.guard.squaredDistanceTo(formationPoint);
		double ownerDistanceSq = this.guard.squaredDistanceTo(this.owner);
		if (ownerDistanceSq > 6400.0D && this.teleportCooldown <= 0) {
			this.guard.teleportToFormationAnchor(formationPoint);
			this.teleportCooldown = 60;
			this.repathCooldown = 0;
			return;
		}

		if (ownerDistanceSq > 1936.0D && this.repathCooldown <= 0) {
			this.guard.getNavigation().startMovingTo(this.owner, this.speed + 0.4D);
			this.repathCooldown = 4;
		}

		if (distanceSq > 1.2D && this.repathCooldown <= 0) {
			double speedBoost = Math.min(0.75D, Math.sqrt(distanceSq) * 0.045D);
			double followSpeed = this.speed + speedBoost;
			this.guard.getNavigation().startMovingTo(formationPoint.x, formationPoint.y, formationPoint.z, followSpeed);
			this.repathCooldown = distanceSq > 64.0D ? 3 : 5;
		} else if (distanceSq <= 0.8D) {
			this.guard.getNavigation().stop();
		}
		if (this.repathCooldown > 0) {
			this.repathCooldown--;
		}
		if (this.teleportCooldown > 0) {
			this.teleportCooldown--;
		}
	}
}
