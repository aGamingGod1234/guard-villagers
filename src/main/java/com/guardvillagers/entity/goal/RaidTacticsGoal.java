package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardBehaviorExecutor;
import com.guardvillagers.entity.ai.GuardMovementSlotResolver;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;

/**
 * Raid positioning while no raider is in sight (combat itself is the ENGAGE intent):
 * offensive guards head for the nearest raider, defensive guards hold slots around the raid centre.
 * The raider search runs once a second instead of every tick.
 */
public final class RaidTacticsGoal extends Goal {
	private static final double RAIDER_SEARCH_RANGE = 48.0D;
	private static final int RAIDER_SEARCH_TICKS = 20;

	private final GuardEntity guard;
	private final double speed;
	private final AnchorHold hold = new AnchorHold();
	private Raid raid;
	private Raider seekTarget;
	private long nextRaiderSearch;

	public RaidTacticsGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		if (!this.isRaidExecutor() || !(this.guard.level() instanceof ServerLevel world)) {
			return false;
		}
		this.raid = world.getRaidAt(this.guard.blockPosition());
		return this.raid != null && this.raid.isActive();
	}

	@Override
	public boolean canContinueToUse() {
		return this.raid != null && this.raid.isActive() && this.isRaidExecutor();
	}

	@Override
	public void start() {
		this.hold.reset();
		this.seekTarget = null;
		this.nextRaiderSearch = 0L;
	}

	@Override
	public void stop() {
		this.raid = null;
		this.seekTarget = null;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!(this.guard.level() instanceof ServerLevel world) || this.raid == null) {
			return;
		}

		if (this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_OFFENSIVE)) {
			long now = world.getGameTime();
			if (now >= this.nextRaiderSearch || this.seekTarget == null || !this.seekTarget.isAlive()) {
				this.nextRaiderSearch = now + RAIDER_SEARCH_TICKS;
				this.seekTarget = this.findNearestRaider(world);
			}
			if (this.seekTarget != null) {
				if (this.guard.distanceToSqr(this.seekTarget) > 16.0D) {
					this.guard.getNavigation().moveTo(this.seekTarget, this.speed);
				} else {
					this.guard.getNavigation().stop();
				}
				return;
			}
		}

		BlockPos slot = this.guard.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.RAID, this.raid.getCenter(), false);
		this.hold.tick(this.guard, slot, this.speed);
	}

	private boolean isRaidExecutor() {
		return this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_OFFENSIVE)
				|| this.guard.isBehaviorExecutor(GuardBehaviorExecutor.RAID_DEFENSIVE);
	}

	private Raider findNearestRaider(ServerLevel world) {
		Raider best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Raider raider : world.getEntitiesOfClass(
				Raider.class,
				this.guard.getBoundingBox().inflate(RAIDER_SEARCH_RANGE),
				entity -> entity.isAlive() && this.guard.canTargetWithinZone(entity.blockPosition()))) {
			double distanceSq = this.guard.distanceToSqr(raider);
			if (distanceSq < bestDistance) {
				bestDistance = distanceSq;
				best = raider;
			}
		}
		return best;
	}
}
