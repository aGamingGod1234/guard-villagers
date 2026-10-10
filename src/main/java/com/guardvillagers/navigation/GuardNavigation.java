package com.guardvillagers.navigation;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class GuardNavigation extends GroundPathNavigation {
	private static final int STALL_THRESHOLD_TICKS = 40;
	private static final double MIN_STALL_PROGRESS_SQUARED = 0.25D;
	private static final int STATIC_REPATH_INTERVAL_TICKS = 12;
	private static final int DYNAMIC_REPATH_INTERVAL_TICKS = 4;
	private static final double STATIC_REPATH_DISTANCE_SQUARED = 0.25D;
	private static final double DYNAMIC_REPATH_DISTANCE_SQUARED = 1.0D;
	private static final int WATER_EXIT_SEARCH_RADIUS = 4;
	private static final int WATER_EXIT_VERTICAL_RANGE = 3;
	private static final int CROWD_RECOVERY_TICKS = 20;
	private static final double CROWD_RECOVERY_DISTANCE = 2.5D;
	private static final double CROWD_JAM_RADIUS = 1.35D;
	private static final int CROWD_RECOVERY_VERTICAL_RANGE = 6;

	private final GuardEntity guard;

	private long lastRecalculateTick;
	private BlockPos lastTargetPos;
	private Vec3 lastStallCheckPos = Vec3.ZERO;
	private Vec3 lastRequestedTargetPos;
	private Vec3 lastIssuedTargetPos = Vec3.ZERO;
	private RouteMode routeMode = RouteMode.STATIC;
	private RouteMode lastIssuedRouteMode = RouteMode.STATIC;
	private long lastMoveCommandTick = Long.MIN_VALUE;
	private double lastIssuedSpeed;
	private int stallTicks;
	private int crowdRecoveryTicks;

	public GuardNavigation(GuardEntity guard, Level world) {
		super(guard, world);
		this.guard = guard;
	}

	@Override
	public Path createPath(BlockPos target, int distance) {
		if (target != null) {
			this.lastTargetPos = target.immutable();
		}

		if (this.guard.getAirSupply() < 80 || this.routeMode != RouteMode.STATIC) {
			this.lastRecalculateTick = this.guard.level().getGameTime();
			return super.createPath(target, distance);
		}

		long currentTick = this.guard.level().getGameTime();
		Path cached = SquadRouteCache.getSquadRoute(
				this.level.dimension(),
				this.guard.getSquadId(),
				this.guard.blockPosition(),
				target,
				currentTick);
		if (cached != null) {
			this.lastRecalculateTick = currentTick;
			return cached;
		}

		Path newPath = super.createPath(target, distance);
		if (newPath != null) {
			SquadRouteCache.cacheSquadRoute(
					this.level.dimension(),
					this.guard.getSquadId(),
					this.guard.blockPosition(),
					target,
					newPath,
					currentTick);
		}
		this.lastRecalculateTick = currentTick;
		return newPath;
	}

	@Override
	public Path createPath(Entity entity, int distance) {
		if (entity == null) {
			return null;
		}
		this.lastTargetPos = entity.blockPosition().immutable();
		this.lastRequestedTargetPos = new Vec3(entity.getX(), entity.getY(), entity.getZ());
		this.routeMode = RouteMode.DYNAMIC;
		this.lastRecalculateTick = this.guard.level().getGameTime();
		return super.createPath(entity, distance);
	}

	@Override
	public boolean moveTo(Entity entity, double speed) {
		if (entity == null) {
			return false;
		}
		this.routeMode = RouteMode.DYNAMIC;
		this.lastTargetPos = entity.blockPosition().immutable();
		this.lastRequestedTargetPos = new Vec3(entity.getX(), entity.getY(), entity.getZ());
		if (!this.shouldIssueMove(this.lastRequestedTargetPos, speed, RouteMode.DYNAMIC)) {
			return !this.isDone();
		}
		return super.moveTo(entity, speed);
	}

	@Override
	public boolean moveTo(double x, double y, double z, double speed) {
		return this.startMovingToStatic(x, y, z, speed);
	}

	public boolean startMovingToStatic(BlockPos target, double speed) {
		if (target == null) {
			return false;
		}
		return this.startMovingToStatic(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speed);
	}

	public boolean startMovingToStatic(double x, double y, double z, double speed) {
		Vec3 targetPos = new Vec3(x, y, z);
		this.routeMode = RouteMode.STATIC;
		this.lastRequestedTargetPos = targetPos;
		this.lastTargetPos = BlockPos.containing(x, y, z);
		if (!this.shouldIssueMove(targetPos, speed, RouteMode.STATIC)) {
			return !this.isDone();
		}
		return super.moveTo(x, y, z, speed);
	}

	public boolean startMovingToDynamic(Vec3 targetPos, double speed) {
		if (targetPos == null) {
			return false;
		}
		this.routeMode = RouteMode.DYNAMIC;
		this.lastRequestedTargetPos = targetPos;
		this.lastTargetPos = BlockPos.containing(targetPos.x, targetPos.y, targetPos.z);
		if (!this.shouldIssueMove(targetPos, speed, RouteMode.DYNAMIC)) {
			return !this.isDone();
		}
		return super.moveTo(targetPos.x, targetPos.y, targetPos.z, speed);
	}

	public boolean isCrowdBlocked() {
		return this.crowdRecoveryTicks > 0 || this.hasNearbyAlliedCrowding();
	}

	@Override
	public void tick() {
		super.tick();
		if (this.crowdRecoveryTicks > 0) {
			this.crowdRecoveryTicks--;
		}

		if (this.isDone()) {
			this.stallTicks = 0;
			this.lastStallCheckPos = this.guard.position();
			return;
		}

		Vec3 currentPos = this.guard.position();
		if (this.lastStallCheckPos.distanceToSqr(currentPos) < MIN_STALL_PROGRESS_SQUARED) {
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
		BlockPos cachedTarget = this.lastTargetPos != null ? this.lastTargetPos.immutable() : this.getTargetPos();
		BlockPos recoveryTarget = this.resolveWaterRecoveryTarget();
		Vec3 crowdRecoveryTarget = this.resolveCrowdRecoveryTarget();
		this.stop();

		if (this.routeMode == RouteMode.STATIC && cachedTarget != null) {
			SquadRouteCache.invalidateSquadRoute(this.level.dimension(), this.guard.getSquadId(), cachedTarget);
		}

		if (crowdRecoveryTarget != null) {
			this.crowdRecoveryTicks = CROWD_RECOVERY_TICKS;
			this.startMovingToDynamic(crowdRecoveryTarget, this.speedModifier);
			return;
		}

		if (recoveryTarget != null) {
			this.startMovingToStatic(recoveryTarget, this.speedModifier);
			return;
		}

		if (this.routeMode == RouteMode.DYNAMIC && this.lastRequestedTargetPos != null) {
			this.startMovingToDynamic(this.lastRequestedTargetPos, this.speedModifier);
			return;
		}

		if (cachedTarget != null) {
			this.startMovingToStatic(cachedTarget, this.speedModifier);
		}
	}

	private boolean shouldIssueMove(Vec3 targetPos, double speed, RouteMode mode) {
		long currentTick = this.guard.level().getGameTime();
		int minInterval = mode == RouteMode.DYNAMIC ? DYNAMIC_REPATH_INTERVAL_TICKS : STATIC_REPATH_INTERVAL_TICKS;
		double minDistanceSq = mode == RouteMode.DYNAMIC ? DYNAMIC_REPATH_DISTANCE_SQUARED : STATIC_REPATH_DISTANCE_SQUARED;

		boolean shouldIssue = this.isDone()
				|| this.lastMoveCommandTick == Long.MIN_VALUE
				|| this.lastIssuedRouteMode != mode
				|| Math.abs(speed - this.lastIssuedSpeed) > 0.05D
				|| this.lastIssuedTargetPos.distanceToSqr(targetPos) > minDistanceSq
				|| currentTick - this.lastMoveCommandTick >= minInterval;

		if (shouldIssue) {
			this.lastMoveCommandTick = currentTick;
			this.lastIssuedTargetPos = targetPos;
			this.lastIssuedRouteMode = mode;
			this.lastIssuedSpeed = speed;
		}
		return shouldIssue;
	}

	private boolean hasNearbyAlliedCrowding() {
		AABB crowdBox = this.guard.getBoundingBox().inflate(CROWD_JAM_RADIUS);
		List<GuardEntity> nearby = this.level.getEntitiesOfClass(
				GuardEntity.class,
				crowdBox,
				entity -> entity != this.guard && entity.isAlive() && this.guard.isSameMovementGroup(entity));
		return !nearby.isEmpty();
	}

	private Vec3 resolveCrowdRecoveryTarget() {
		if (!(this.level instanceof ServerLevel serverWorld) || !this.hasNearbyAlliedCrowding()) {
			return null;
		}

		Vec3 origin = this.guard.position();
		double baseAngle = Math.toRadians(Math.floorMod(this.guard.getUUID().hashCode(), 360));
		for (int index = 0; index < 8; index++) {
			double angle = baseAngle + (Math.PI / 4.0D) * index;
			Vec3 probe = origin.add(Math.cos(angle) * CROWD_RECOVERY_DISTANCE, 0.0D,
					Math.sin(angle) * CROWD_RECOVERY_DISTANCE);
			BlockPos candidateOrigin = BlockPos.containing(probe.x, this.guard.getBlockY(), probe.z);
			BlockPos grounded = GuardVillagersMod.findNearbyGuardSpawnPos(
					serverWorld,
					candidateOrigin,
					CROWD_RECOVERY_VERTICAL_RANGE);
			if (grounded == null) {
				continue;
			}
			return Vec3.atBottomCenterOf(grounded);
		}
		return null;
	}

	private BlockPos resolveWaterRecoveryTarget() {
		if (!(this.level instanceof ServerLevel serverWorld)) {
			return null;
		}

		if (!this.isInOrAgainstFlowingWater()) {
			return null;
		}

		BlockPos lastLand = this.guard.getLastLandPos();
		if (lastLand != null && GuardVillagersMod.canGuardSpawnAt(serverWorld, lastLand)) {
			return lastLand.immutable();
		}
		return this.findNearbyDryExit(serverWorld);
	}

	private boolean isInOrAgainstFlowingWater() {
		BlockPos origin = this.guard.blockPosition();
		if (this.isFlowingWater(origin) || this.isFlowingWater(origin.above())) {
			return true;
		}
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (this.isFlowingWater(origin.relative(direction)) || this.isFlowingWater(origin.above().relative(direction))) {
				return true;
			}
		}
		return this.guard.isInWater();
	}

	private boolean isFlowingWater(BlockPos pos) {
		var fluid = this.level.getFluidState(pos);
		return fluid.is(FluidTags.WATER) && !fluid.isSource();
	}

	private BlockPos findNearbyDryExit(ServerLevel serverWorld) {
		BlockPos origin = this.guard.blockPosition();
		BlockPos best = null;
		double bestDistanceSq = Double.MAX_VALUE;

		for (int radius = 1; radius <= WATER_EXIT_SEARCH_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}

					BlockPos topCandidate = this.level.getHeightmapPos(
							Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
							origin.offset(dx, 0, dz));
					best = this.pickBestDryExit(serverWorld, topCandidate, best, origin, bestDistanceSq);
					if (best != null) {
						bestDistanceSq = best.distSqr(origin);
					}

					for (int dy = -WATER_EXIT_VERTICAL_RANGE; dy <= WATER_EXIT_VERTICAL_RANGE; dy++) {
						BlockPos candidate = origin.offset(dx, dy, dz);
						best = this.pickBestDryExit(serverWorld, candidate, best, origin, bestDistanceSq);
						if (best != null) {
							bestDistanceSq = best.distSqr(origin);
						}
					}
				}
			}
		}

		return best;
	}

	private BlockPos pickBestDryExit(ServerLevel serverWorld, BlockPos candidate, BlockPos currentBest, BlockPos origin, double bestDistanceSq) {
		if (!GuardVillagersMod.canGuardSpawnAt(serverWorld, candidate)) {
			return currentBest;
		}

		double distanceSq = candidate.distSqr(origin);
		if (distanceSq >= bestDistanceSq) {
			return currentBest;
		}
		return candidate.immutable();
	}

	private enum RouteMode {
		STATIC,
		DYNAMIC
	}
}
