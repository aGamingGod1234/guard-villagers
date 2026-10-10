package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import com.guardvillagers.entity.ai.GuardMovementSlotResolver;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/** Gathers around a rally point (whistle / bell) in non-overlapping slots. */
public final class GuardRallyGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private final AnchorHold hold = new AnchorHold();

	public GuardRallyGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isAiIntent(GuardAiIntent.RALLY) && this.guard.getRallyPoint().isPresent();
	}

	@Override
	public boolean canContinueToUse() {
		return this.canUse();
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
		BlockPos rallyPoint = this.guard.getRallyPoint().orElse(null);
		if (rallyPoint == null) {
			return;
		}
		BlockPos slot = this.guard.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.RALLY, rallyPoint,
				this.guard.isRallyExact());
		this.hold.tick(this.guard, slot, this.guard.getResolvedSlotPosition(), this.speed);
	}
}
