package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Shared "walk to my slot and stand there" logic with a dead zone: a guard starts walking only
 * when it is more than {@link #DEPART_DISTANCE_SQUARED} from its slot and stops once within
 * {@link #ARRIVE_DISTANCE_SQUARED}, so guards parked at an anchor do not fidget back and forth.
 */
final class AnchorHold {
	// Slots are ~1.5 blocks apart, so the dead zone must stay below that; the path follower ends
	// within ~0.45 blocks of the target block centre.
	static final double DEPART_DISTANCE_SQUARED = 1.25D * 1.25D;
	static final double ARRIVE_DISTANCE_SQUARED = 0.6D * 0.6D;
	private static final double SETTLE_DISTANCE_SQUARED = 0.15D * 0.15D;
	private static final double FINAL_STEP_DISTANCE_SQUARED = 1.5D * 1.5D;

	private boolean moving;

	void reset() {
		this.moving = false;
	}

	/** Walks to {@code slot} (pathfinding) and then steps onto {@code exact} (same block). */
	void tick(GuardEntity guard, BlockPos slot, Vec3 exact, double speed) {
		Vec3 standAt = exact != null ? exact : Vec3.atBottomCenterOf(slot);
		double distanceSq = guard.distanceToSqr(standAt);
		if (distanceSq <= (this.moving ? SETTLE_DISTANCE_SQUARED : DEPART_DISTANCE_SQUARED)) {
			if (this.moving) {
				guard.getNavigation().stop();
				this.moving = false;
			}
			return;
		}
		if (this.moving && distanceSq <= FINAL_STEP_DISTANCE_SQUARED && guard.getNavigation().isDone()
				&& guard.blockPosition().distManhattan(slot) <= 1 && Math.abs(guard.getY() - standAt.y) < 0.5D) {
			// Last stretch inside or next to the slot block: no pathfinding needed.
			guard.getMoveControl().setWantedPosition(standAt.x, standAt.y, standAt.z, speed);
			if (distanceSq <= ARRIVE_DISTANCE_SQUARED && guard.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4D) {
				this.moving = false;
			}
			return;
		}
		this.moving = true;
		guard.getGuardNavigation().startMovingToStatic(slot, speed);
		if (guard.getGuardNavigation().isLastTargetUnreachable() && guard.getNavigation().isDone()) {
			// Already at the closest reachable point: stand here instead of re-pathing.
			this.moving = false;
		}
	}
}
