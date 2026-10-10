package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.core.BlockPos;

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

	private boolean moving;

	void reset() {
		this.moving = false;
	}

	void tick(GuardEntity guard, BlockPos slot, double speed) {
		double distanceSq = guard.distanceToSqr(slot.getX() + 0.5D, slot.getY(), slot.getZ() + 0.5D);
		if (distanceSq <= (this.moving ? ARRIVE_DISTANCE_SQUARED : DEPART_DISTANCE_SQUARED)) {
			if (this.moving) {
				guard.getNavigation().stop();
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
