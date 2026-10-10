package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * Runs for the whole FOLLOW_OWNER intent and has two modes.
 *
 * <ul>
 * <li><b>Catch-up</b> (owner far away or on a different level): path to the ground under the
 * owner. All guards behind the owner share that one target, so they take the same route (the
 * squad route cache lets neighbours reuse it) and queue up the owner's ramp or stairs instead of
 * each aiming at a ring slot that may sit on top of an unreachable ledge.</li>
 * <li><b>Formation</b> (owner close): walk to this guard's unique ring slot and stand there. If the
 * slot turns out to be unreachable from here, approach the owner instead for a while.</li>
 * </ul>
 *
 * Arrive / depart thresholds differ so guards do not twitch at the edge of their slot.
 */
public final class FormationFollowOwnerGoal extends Goal {
	private static final double CATCH_UP_ENTER_DISTANCE = 12.0D;
	private static final double CATCH_UP_EXIT_DISTANCE = 8.0D;
	private static final double CATCH_UP_VERTICAL_ENTER = 3.0D;
	private static final double CATCH_UP_VERTICAL_EXIT = 2.0D;
	private static final double SPEED_BOOST_DISTANCE = 16.0D;
	private static final double SLOT_DEPART_DISTANCE_SQUARED = 2.5D * 2.5D;
	private static final double SLOT_ARRIVE_DISTANCE_SQUARED = 1.0D;
	private static final double OWNER_APPROACH_STOP_DISTANCE_SQUARED = 3.0D * 3.0D;
	private static final int REPATH_INTERVAL_TICKS = 5;
	private static final int UNREACHABLE_SLOT_TICKS = 40;
	private static final int TELEPORT_RETRY_TICKS = 40;
	private static final double OWNER_GROUND_MAX_DRIFT_SQUARED = 8.0D * 8.0D;
	private static final double CATCH_UP_RETARGET_DISTANCE_SQUARED = 3.0D * 3.0D;
	private static final int CATCH_UP_RETARGET_TICKS = 20;

	private final GuardEntity guard;
	private final double speed;
	private ServerPlayer owner;
	private BlockPos ownerGround;
	private boolean catchingUp;
	private boolean movingToSlot;
	private int repathCooldown;
	private long slotUnreachableUntil;
	private long nextTeleportAttempt;
	private BlockPos catchUpTarget;
	private long catchUpIssuedTick;

	public FormationFollowOwnerGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (!(this.guard.level() instanceof ServerLevel world) || !this.guard.canFollowOwnerFormation()) {
			return false;
		}
		ServerPlayer resolvedOwner = this.guard.resolveOwner(world);
		if (resolvedOwner == null || resolvedOwner.isSpectator()) {
			// Owner online but in another dimension: follow them through it.
			long now = world.getGameTime();
			if (now >= this.nextTeleportAttempt) {
				this.nextTeleportAttempt = now + TELEPORT_RETRY_TICKS;
				this.guard.tryTeleportToOwnerWorld(world);
			}
			return false;
		}
		this.owner = resolvedOwner;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return this.owner != null
				&& this.owner.isAlive()
				&& !this.owner.isRemoved()
				&& !this.owner.isSpectator()
				&& this.owner.level() == this.guard.level()
				&& this.guard.canFollowOwnerFormation();
	}

	@Override
	public void start() {
		this.repathCooldown = 0;
		this.catchingUp = false;
		this.movingToSlot = false;
		this.ownerGround = null;
		this.slotUnreachableUntil = 0L;
		this.catchUpTarget = null;
		this.guard.setPathfindingMalus(PathType.WATER, 0.0F);
	}

	@Override
	public void stop() {
		this.owner = null;
		this.ownerGround = null;
		this.guard.getNavigation().stop();
		this.guard.setPathfindingMalus(PathType.WATER, GuardEntity.DEFAULT_WATER_MALUS);
		this.guard.setCatchUpSpeedActive(false);
	}

	@Override
	public void tick() {
		ServerPlayer currentOwner = this.owner;
		if (currentOwner == null) {
			return;
		}
		this.updateOwnerGround(currentOwner);

		double ownerDistanceSq = this.guard.distanceToSqr(currentOwner);
		if (!this.guard.getNavigation().isInProgress() && ownerDistanceSq < 10.0D * 10.0D) {
			this.guard.getLookControl().setLookAt(currentOwner, 10.0F, this.guard.getMaxHeadXRot());
		}
		if (--this.repathCooldown > 0 || this.guard.isLeashed() || this.guard.isPassenger()) {
			return;
		}
		this.repathCooldown = this.adjustedTickDelay(REPATH_INTERVAL_TICKS);

		double ownerDistance = Math.sqrt(ownerDistanceSq);
		double verticalDelta = Math.abs(currentOwner.getY() - this.guard.getY());
		if (this.catchingUp) {
			this.catchingUp = ownerDistance > CATCH_UP_EXIT_DISTANCE || verticalDelta > CATCH_UP_VERTICAL_EXIT;
		} else {
			this.catchingUp = ownerDistance > CATCH_UP_ENTER_DISTANCE
					|| (verticalDelta > CATCH_UP_VERTICAL_ENTER && ownerDistance > 3.0D);
		}
		this.guard.setCatchUpSpeedActive(this.catchingUp && ownerDistance > SPEED_BOOST_DISTANCE);

		long now = this.guard.level().getGameTime();
		if (this.catchingUp) {
			this.movingToSlot = false;
			this.catchUp(now);
			return;
		}

		if (now < this.slotUnreachableUntil) {
			if (ownerDistanceSq <= OWNER_APPROACH_STOP_DISTANCE_SQUARED) {
				this.guard.getNavigation().stop();
			} else {
				this.guard.getGuardNavigation().startMovingToStatic(this.ownerGround, this.speed);
			}
			return;
		}

		BlockPos slot = this.guard.resolveFollowSlot(currentOwner, this.ownerGround);
		double slotDistanceSq = this.guard.distanceToSqr(slot.getX() + 0.5D, slot.getY(), slot.getZ() + 0.5D);
		double stopDistanceSq = this.movingToSlot ? SLOT_ARRIVE_DISTANCE_SQUARED : SLOT_DEPART_DISTANCE_SQUARED;
		if (slotDistanceSq <= stopDistanceSq) {
			if (this.movingToSlot) {
				this.guard.getNavigation().stop();
				this.movingToSlot = false;
			}
			return;
		}

		this.movingToSlot = true;
		this.guard.getGuardNavigation().startMovingToStatic(slot, this.speed);
		if (this.guard.getGuardNavigation().isLastTargetUnreachable()) {
			this.slotUnreachableUntil = now + UNREACHABLE_SLOT_TICKS;
		}
	}

	/**
	 * Re-targets the catch-up path only when the owner moved a few blocks, the path ended, or a
	 * second passed. Re-pathing every time the owner crosses a block border cost one A* search
	 * per guard every few ticks, which dominated tick time with large groups.
	 */
	private void catchUp(long now) {
		boolean retarget = this.catchUpTarget == null
				|| this.guard.getNavigation().isDone()
				|| this.catchUpTarget.distSqr(this.ownerGround) > CATCH_UP_RETARGET_DISTANCE_SQUARED
				|| now - this.catchUpIssuedTick >= CATCH_UP_RETARGET_TICKS;
		if (!retarget) {
			return;
		}
		this.catchUpTarget = this.ownerGround;
		this.catchUpIssuedTick = now;
		this.guard.getGuardNavigation().startMovingToStatic(this.catchUpTarget, this.speed);
	}

	/**
	 * Ground under the owner. While the owner jumps, flies or swims the last grounded position is
	 * kept (if still close) so guards do not path toward a point in mid-air.
	 */
	private void updateOwnerGround(ServerPlayer currentOwner) {
		BlockPos ownerPos = currentOwner.blockPosition();
		if (currentOwner.onGround() || this.ownerGround == null
				|| this.ownerGround.distSqr(ownerPos) > OWNER_GROUND_MAX_DRIFT_SQUARED) {
			this.ownerGround = ownerPos;
		}
	}
}
