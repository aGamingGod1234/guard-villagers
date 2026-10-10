package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * A submerged guard low on air swims to the nearest reachable air pocket.
 *
 * <p>Fixes: the goal used to declare the JUMP flag, which {@code FloatGoal} (priority 0) holds the
 * whole time a mob is in water, so this goal could never start. The air search also looked
 * straight up through solid ceilings and accepted flowing water as "breathable"; it now scans
 * nearby columns, stops at solid blocks, and requires a fluid-free, collision-free block.
 */
public final class SeekAirGoal extends Goal {
	private static final int MAX_VERTICAL_SCAN = 16;
	private static final int HORIZONTAL_SCAN_RADIUS = 3;
	private static final int REPATH_TICKS = 10;

	private final GuardEntity guard;
	private final double speed;
	private int repathTicks;
	private BlockPos targetAirPos;

	public SeekAirGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isAiIntent(GuardAiIntent.SEEK_AIR);
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isAiIntent(GuardAiIntent.SEEK_AIR);
	}

	@Override
	public void start() {
		this.guard.setPathfindingMalus(PathType.WATER, 0.0F);
		this.repathTicks = 0;
		this.targetAirPos = null;
	}

	@Override
	public void stop() {
		this.guard.getNavigation().stop();
		this.guard.setPathfindingMalus(PathType.WATER, GuardEntity.DEFAULT_WATER_MALUS);
		this.targetAirPos = null;
	}

	@Override
	public void tick() {
		// Always kick upward; ground navigation alone swims up slowly.
		this.guard.setDeltaMovement(this.guard.getDeltaMovement().x,
				Math.max(this.guard.getDeltaMovement().y, 0.04D),
				this.guard.getDeltaMovement().z);

		if (--this.repathTicks > 0) {
			return;
		}
		this.repathTicks = REPATH_TICKS;
		if (this.targetAirPos == null || !isBreathable(this.guard.level(), this.targetAirPos)) {
			this.targetAirPos = this.findNearestAir();
		}
		if (this.targetAirPos != null) {
			this.guard.getNavigation().moveTo(this.targetAirPos.getX() + 0.5D, this.targetAirPos.getY(),
					this.targetAirPos.getZ() + 0.5D, this.speed);
		}
	}

	private BlockPos findNearestAir() {
		Level level = this.guard.level();
		BlockPos origin = this.guard.blockPosition();
		BlockPos best = null;
		double bestDistanceSq = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -HORIZONTAL_SCAN_RADIUS; dx <= HORIZONTAL_SCAN_RADIUS; dx++) {
			for (int dz = -HORIZONTAL_SCAN_RADIUS; dz <= HORIZONTAL_SCAN_RADIUS; dz++) {
				for (int dy = 0; dy <= MAX_VERTICAL_SCAN; dy++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!level.isLoaded(cursor)) {
						break;
					}
					if (isBreathable(level, cursor)) {
						double distanceSq = cursor.distSqr(origin);
						if (distanceSq < bestDistanceSq) {
							bestDistanceSq = distanceSq;
							best = cursor.immutable();
						}
						break;
					}
					if (level.getFluidState(cursor).isEmpty()) {
						// Solid ceiling (or a dry wall) above the water in this column.
						break;
					}
				}
			}
		}
		return best;
	}

	private static boolean isBreathable(Level level, BlockPos pos) {
		return level.getFluidState(pos).isEmpty()
				&& level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
