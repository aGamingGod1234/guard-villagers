package com.guardvillagers.entity.goal;

import com.guardvillagers.command.hierarchy.GuardSeniority;
import com.guardvillagers.entity.GuardEntity;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Squad leader election for unowned village guards. Owned guards get their ranks from
 * {@link com.guardvillagers.command.hierarchy.GuardHierarchy} instead, so this goal skips them.
 */
public final class ElectLeaderGoal extends Goal {
	private final GuardEntity guard;
	private final double searchRadius;

	public ElectLeaderGoal(GuardEntity guard, double searchRadius) {
		this.guard = guard;
		this.searchRadius = searchRadius;
		this.setFlags(EnumSet.noneOf(Flag.class));
	}

	@Override
	public boolean canUse() {
		return this.guard.hasSquad()
			&& !this.guard.hasOwner()
			// Mob#serverAiStep only evaluates canUse when (tickCount + id) is even, so a bare
			// tickCount % 80 never fired for odd-id guards.
			&& (this.guard.tickCount + this.guard.getId()) % 80 == 0
			&& this.guard.level() instanceof ServerLevel;
	}

	@Override
	public boolean canContinueToUse() {
		return false;
	}

	@Override
	public void start() {
		if (!(this.guard.level() instanceof ServerLevel world) || !this.guard.hasSquad() || this.guard.hasOwner()) {
			return;
		}

		List<GuardEntity> squad = world.getEntitiesOfClass(
			GuardEntity.class,
			this.guard.getBoundingBox().inflate(this.searchRadius),
			other -> other.isAlive() && !other.hasOwner() && this.guard.isSameSquad(other)
		);
		if (squad.isEmpty()) {
			return;
		}

		GuardEntity leader = null;
		for (GuardEntity member : squad) {
			if (member.isSquadLeader()) {
				return;
			}
			if (leader == null || GuardSeniority.ORDER.compare(member, leader) > 0) {
				leader = member;
			}
		}
		if (leader == null) {
			leader = this.guard;
		}

		for (GuardEntity member : squad) {
			member.setSquadLeader(member == leader);
		}
	}
}
