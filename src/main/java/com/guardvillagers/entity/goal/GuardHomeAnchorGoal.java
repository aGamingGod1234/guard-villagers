package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardBehaviorExecutor;
import com.guardvillagers.entity.ai.GuardMovementSlotResolver;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/** Walks a guard that strayed outside its zone back to a slot around home. */
public final class GuardHomeAnchorGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private final AnchorHold hold = new AnchorHold();

	public GuardHomeAnchorGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isBehaviorExecutor(GuardBehaviorExecutor.HOME_ANCHOR) && this.guard.getHome().isPresent();
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
		BlockPos home = this.guard.getHome().orElse(null);
		if (home == null) {
			return;
		}
		BlockPos slot = this.guard.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.HOME, home, false);
		this.hold.tick(this.guard, slot, this.guard.getResolvedSlotPosition(), this.speed);
	}
}
