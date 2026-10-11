package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardBehaviorExecutor;
import com.guardvillagers.entity.ai.GuardMovementSlotResolver;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

/** Holds a slot around the nearest village meeting point (bell), home, or the starting spot. */
public final class CrowdControlGoal extends Goal {
	private static final int ANCHOR_REFRESH_TICKS = 200;

	private final GuardEntity guard;
	private final double speed;
	private final AnchorHold hold = new AnchorHold();
	private BlockPos anchor;
	private long nextAnchorRefresh;

	public CrowdControlGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isBehaviorExecutor(GuardBehaviorExecutor.CROWD_CONTROL) && this.refreshAnchor();
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isBehaviorExecutor(GuardBehaviorExecutor.CROWD_CONTROL) && this.anchor != null;
	}

	@Override
	public void start() {
		this.hold.reset();
	}

	@Override
	public void stop() {
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!this.refreshAnchor()) {
			return;
		}
		BlockPos slot = this.guard.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.CROWD, this.anchor, false);
		this.hold.tick(this.guard, slot, this.guard.getResolvedSlotPosition(), this.speed);
	}

	/** POI lookups are throttled (they used to run on every canUse check, i.e. every 2 ticks). */
	private boolean refreshAnchor() {
		if (!(this.guard.level() instanceof ServerLevel world)) {
			return false;
		}
		long now = world.getGameTime();
		if (this.anchor != null && now < this.nextAnchorRefresh) {
			return true;
		}
		this.nextAnchorRefresh = now + ANCHOR_REFRESH_TICKS + Math.floorMod(this.guard.getId(), 40);
		Optional<BlockPos> meetingPoint = world.getPoiManager().findClosest(
				entry -> entry.is(PoiTypes.MEETING),
				this.guard.blockPosition(),
				64,
				PoiManager.Occupancy.ANY);
		BlockPos resolved = meetingPoint.orElse(this.guard.getHome().orElse(null));
		if (resolved == null) {
			resolved = this.anchor != null ? this.anchor : this.guard.blockPosition();
		}
		if (!this.guard.canTargetWithinZone(resolved) && this.guard.getHome().isPresent()) {
			resolved = this.guard.getHome().get();
		}
		this.anchor = resolved.immutable();
		return true;
	}
}
