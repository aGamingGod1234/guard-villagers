package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class GuardIdleGoal extends WaterAvoidingRandomStrollGoal {
	private static final double STAY_RADIUS_SQ = (double) GuardEntity.STAY_RADIUS * GuardEntity.STAY_RADIUS;

	private final GuardEntity guard;

	public GuardIdleGoal(GuardEntity guard, double speed) {
		super(guard, speed);
		this.guard = guard;
	}

	@Override
	public boolean canUse() {
		if (!this.guard.isAiIntent(GuardAiIntent.IDLE)) {
			return false;
		}
		if (this.guard.isStaying()) {
			BlockPos origin = this.guard.getStayOrigin();
			if (origin == null) {
				return false;
			}
			if (origin.distSqr(this.guard.blockPosition()) > STAY_RADIUS_SQ) {
				this.wantedX = origin.getX() + 0.5;
				this.wantedY = origin.getY();
				this.wantedZ = origin.getZ() + 0.5;
				return true;
			}
			return super.canUse();
		}
		return super.canUse();
	}

	@Override
	public boolean canContinueToUse() {
		if (!this.guard.isAiIntent(GuardAiIntent.IDLE)) {
			return false;
		}
		if (this.guard.isStaying() && this.guard.getStayOrigin() == null) {
			return false;
		}
		return super.canContinueToUse();
	}

	@Nullable
	@Override
	protected Vec3 getPosition() {
		if (this.guard.isStaying()) {
			BlockPos origin = this.guard.getStayOrigin();
			if (origin == null) {
				return null;
			}
			Vec3 candidate = LandRandomPos.getPos(this.guard, GuardEntity.STAY_RADIUS, 2);
			if (candidate != null
					&& origin.distSqr(BlockPos.containing(candidate.x, candidate.y, candidate.z)) <= STAY_RADIUS_SQ) {
				return candidate;
			}
			return null;
		}
		return super.getPosition();
	}
}
