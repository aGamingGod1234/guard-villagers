package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

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
			&& this.guard.tickCount % 80 == 0
			&& this.guard.level() instanceof ServerLevel;
	}

	@Override
	public boolean canContinueToUse() {
		return false;
	}

	private static final Comparator<GuardEntity> LEADER_COMPARATOR = Comparator
			.comparingInt(GuardEntity::getLevel)
			.thenComparingInt(GuardEntity::getExperience)
			.thenComparing(entity -> entity.getUUID());

	@Override
	public void start() {
		if (!(this.guard.level() instanceof ServerLevel world) || !this.guard.hasSquad()) {
			return;
		}

		List<GuardEntity> squad = world.getEntitiesOfClass(
			GuardEntity.class,
			this.guard.getBoundingBox().inflate(this.searchRadius),
			other -> other.isAlive() && this.guard.isSameSquad(other)
		);
		if (squad.isEmpty()) {
			return;
		}

		GuardEntity leader = null;
		for (GuardEntity member : squad) {
			if (member.isSquadLeader()) {
				return;
			}
			if (leader == null || LEADER_COMPARATOR.compare(member, leader) > 0) {
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
