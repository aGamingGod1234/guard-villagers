package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/** Falls back to a cleric, a bed, home, or away from the threat while health recovers. */
public final class TacticalRetreatGoal extends Goal {
	private static final int SAFE_POINT_REFRESH_TICKS = 40;

	private final GuardEntity guard;
	private final double speed;
	private final AnchorHold hold = new AnchorHold();
	private BlockPos safePoint;
	private long nextRefresh;

	public TacticalRetreatGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETREAT);
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETREAT);
	}

	@Override
	public void start() {
		this.safePoint = null;
		this.nextRefresh = 0L;
		this.hold.reset();
	}

	@Override
	public void stop() {
		this.safePoint = null;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!(this.guard.level() instanceof ServerLevel world)) {
			return;
		}
		long now = world.getGameTime();
		if (this.safePoint == null || now >= this.nextRefresh) {
			this.safePoint = this.guard.findSafeRetreatPoint(world);
			this.nextRefresh = now + SAFE_POINT_REFRESH_TICKS;
		}
		this.hold.tick(this.guard, this.safePoint, this.speed);
	}
}
