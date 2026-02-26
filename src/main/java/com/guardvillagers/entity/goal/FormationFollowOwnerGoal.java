package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.EnumSet;

public final class FormationFollowOwnerGoal extends Goal {
	private static final int REPATH_INTERVAL_TICKS = 10;
	private static final int TELEPORT_DISTANCE_SQ = 144;

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
		if (this.guard.getNavigation().isIdle()) {
			return false;
		}
		if (!this.guard.canFollowOwnerFormation()) {
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

		if (this.guard.squaredDistanceTo(this.owner) >= TELEPORT_DISTANCE_SQ && this.tryTeleportToOwner()) {
			return;
		}
		this.guard.getNavigation().startMovingTo(this.owner, this.speed);
	}

	private boolean tryTeleportToOwner() {
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world) || this.owner == null) {
			return false;
		}

		BlockPos ownerPos = this.owner.getBlockPos();
		for (int attempt = 0; attempt < 10; attempt++) {
			int x = ownerPos.getX() + this.randomInt(-3, 3);
			int y = ownerPos.getY() + this.randomInt(-1, 1);
			int z = ownerPos.getZ() + this.randomInt(-3, 3);
			if (Math.abs(x - ownerPos.getX()) < 2 && Math.abs(z - ownerPos.getZ()) < 2) {
				continue;
			}
			if (!this.canTeleportTo(world, x, y, z)) {
				continue;
			}
			this.guard.refreshPositionAndAngles(x + 0.5D, y, z + 0.5D, this.guard.getYaw(), this.guard.getPitch());
			this.guard.getNavigation().stop();
			return true;
		}
		return false;
	}

	private boolean canTeleportTo(ServerWorld world, int x, int y, int z) {
		if (y <= world.getBottomY() || y >= world.getTopYInclusive() - 1) {
			return false;
		}

		BlockPos feet = new BlockPos(x, y, z);
		BlockPos below = feet.down();
		BlockPos head = feet.up();

		if (!world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) {
			return false;
		}
		if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
			return false;
		}
		if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
			return false;
		}
		if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()) {
			return false;
		}

		Box destination = this.guard.getBoundingBox().offset(
			x + 0.5D - this.guard.getX(),
			y - this.guard.getY(),
			z + 0.5D - this.guard.getZ()
		);
		return world.isSpaceEmpty(this.guard, destination);
	}

	private int randomInt(int min, int max) {
		return min + this.guard.getRandom().nextInt(max - min + 1);
	}
}
