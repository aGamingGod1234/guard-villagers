package com.guardvillagers.navigation;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

public class GuardNavigation extends MobNavigation {
	private static final int STALL_THRESHOLD_TICKS = 40;
	private static final double MIN_STALL_PROGRESS_SQUARED = 0.25D;
	private static final int WATER_EXIT_SEARCH_RADIUS = 4;
	private static final int WATER_EXIT_VERTICAL_RANGE = 3;

	private final GuardEntity guard;

	private long lastRecalculateTick;
	private BlockPos lastTargetPos;
	private Vec3d lastStallCheckPos = Vec3d.ZERO;
	private int stallTicks;

	public GuardNavigation(GuardEntity guard, World world) {
		super(guard, world);
		this.guard = guard;
	}

	@Override
	public Path findPathTo(BlockPos target, int distance) {
		if (target != null) {
			this.lastTargetPos = target.toImmutable();
		}

		if (this.guard.getAir() < 80) {
			this.lastRecalculateTick = this.guard.getEntityWorld().getTime();
			return super.findPathTo(target, distance);
		}

		long currentTick = this.guard.getEntityWorld().getTime();
		Path cached = SquadRouteCache.getSquadRoute(this.guard.getSquadId(), this.guard.getBlockPos(), target, currentTick);
		if (cached != null) {
			this.lastRecalculateTick = currentTick;
			return cached;
		}

		Path newPath = super.findPathTo(target, distance);
		if (newPath != null) {
			SquadRouteCache.cacheSquadRoute(this.guard.getSquadId(), this.guard.getBlockPos(), target, newPath, currentTick);
		}
		this.lastRecalculateTick = currentTick;
		return newPath;
	}

	@Override
	public Path findPathTo(Entity entity, int distance) {
		return entity == null ? null : this.findPathTo(entity.getBlockPos(), distance);
	}

	@Override
	public void tick() {
		super.tick();

		if (this.isIdle()) {
			this.stallTicks = 0;
			this.lastStallCheckPos = this.guard.getEntityPos();
			return;
		}

		Vec3d currentPos = this.guard.getEntityPos();
		if (this.lastStallCheckPos.squaredDistanceTo(currentPos) < MIN_STALL_PROGRESS_SQUARED) {
			this.stallTicks++;
			if (this.stallTicks >= STALL_THRESHOLD_TICKS) {
				this.recoverFromStall();
				this.stallTicks = 0;
				this.lastStallCheckPos = currentPos;
			}
			return;
		}

		this.lastStallCheckPos = currentPos;
		this.stallTicks = 0;
	}

	private void recoverFromStall() {
		BlockPos cachedTarget = this.lastTargetPos != null ? this.lastTargetPos.toImmutable() : this.getTargetPos();
		BlockPos recoveryTarget = this.resolveWaterRecoveryTarget();
		this.stop();

		if (cachedTarget != null) {
			SquadRouteCache.invalidateSquadRoute(this.guard.getSquadId(), cachedTarget);
		}

		BlockPos repathTarget = recoveryTarget != null ? recoveryTarget : cachedTarget;
		if (repathTarget == null) {
			return;
		}

		Path repathPath = this.findPathTo(repathTarget, 0);
		if (repathPath != null) {
			this.startMovingAlong(repathPath, this.speed);
		}
	}

	private BlockPos resolveWaterRecoveryTarget() {
		if (!(this.world instanceof ServerWorld serverWorld)) {
			return null;
		}

		if (!this.isInOrAgainstFlowingWater()) {
			return null;
		}

		BlockPos lastLand = this.guard.getLastLandPos();
		if (lastLand != null && GuardVillagersMod.canGuardSpawnAt(serverWorld, lastLand)) {
			return lastLand.toImmutable();
		}
		return this.findNearbyDryExit(serverWorld);
	}

	private boolean isInOrAgainstFlowingWater() {
		BlockPos origin = this.guard.getBlockPos();
		if (this.isFlowingWater(origin) || this.isFlowingWater(origin.up())) {
			return true;
		}
		for (Direction direction : Direction.Type.HORIZONTAL) {
			if (this.isFlowingWater(origin.offset(direction)) || this.isFlowingWater(origin.up().offset(direction))) {
				return true;
			}
		}
		return this.guard.isTouchingWater();
	}

	private boolean isFlowingWater(BlockPos pos) {
		return this.world.getFluidState(pos).isIn(FluidTags.WATER) && !this.world.getFluidState(pos).isStill();
	}

	private BlockPos findNearbyDryExit(ServerWorld serverWorld) {
		BlockPos origin = this.guard.getBlockPos();
		BlockPos best = null;
		double bestDistanceSq = Double.MAX_VALUE;

		for (int radius = 1; radius <= WATER_EXIT_SEARCH_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}

					BlockPos topCandidate = this.world.getTopPosition(
							Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
							origin.add(dx, 0, dz));
					best = this.pickBestDryExit(serverWorld, topCandidate, best, origin, bestDistanceSq);
					if (best != null) {
						bestDistanceSq = best.getSquaredDistance(origin);
					}

					for (int dy = -WATER_EXIT_VERTICAL_RANGE; dy <= WATER_EXIT_VERTICAL_RANGE; dy++) {
						BlockPos candidate = origin.add(dx, dy, dz);
						best = this.pickBestDryExit(serverWorld, candidate, best, origin, bestDistanceSq);
						if (best != null) {
							bestDistanceSq = best.getSquaredDistance(origin);
						}
					}
				}
			}
		}

		return best;
	}

	private BlockPos pickBestDryExit(ServerWorld serverWorld, BlockPos candidate, BlockPos currentBest, BlockPos origin, double bestDistanceSq) {
		if (!GuardVillagersMod.canGuardSpawnAt(serverWorld, candidate)) {
			return currentBest;
		}

		double distanceSq = candidate.getSquaredDistance(origin);
		if (distanceSq >= bestDistanceSq) {
			return currentBest;
		}
		return candidate.toImmutable();
	}
}
