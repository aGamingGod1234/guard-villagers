package com.guardvillagers.navigation;

import com.guardvillagers.entity.GuardEntity;
import java.util.function.Supplier;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Guard ground navigation.
 *
 * <ul>
 * <li>Static routes are shared through {@link SquadRouteCache}.</li>
 * <li>Repeated move requests are gated so goals can call every tick without re-pathing.</li>
 * <li>A target whose last path could not reach it is backed off for a while instead of re-running
 * a failing A* search every tick; goals can ask {@link #isLastTargetUnreachable()}.</li>
 * <li>Stall recovery sidesteps out of guard crowds onto a path-validated spot, or leaves flowing
 * water toward the nearest dry standing spot, and holds that recovery move for a short time so the
 * calling goal does not immediately cancel it.</li>
 * <li>Block-update re-paths ({@link #recomputePath()}) go to the destination the guard is actually
 * following. Vanilla re-paths to its private {@code targetPos}, which only a fresh A* search sets,
 * so after a squad-cache hit or a probe search it pointed at an old or probe block.</li>
 * </ul>
 */
public class GuardNavigation extends GroundPathNavigation {
	private static final int STALL_THRESHOLD_TICKS = 40;
	private static final double MIN_STALL_PROGRESS_SQUARED = 0.25D;
	private static final int STATIC_REPATH_INTERVAL_TICKS = 12;
	private static final int DYNAMIC_REPATH_INTERVAL_TICKS = 4;
	private static final double STATIC_REPATH_DISTANCE_SQUARED = 0.25D;
	private static final double DYNAMIC_REPATH_DISTANCE_SQUARED = 1.0D;
	private static final int UNREACHABLE_BACKOFF_TICKS = 40;
	private static final double UNREACHABLE_TARGET_MOVED_SQUARED = 4.0D;
	private static final int WATER_EXIT_SEARCH_RADIUS = 5;
	private static final int WATER_EXIT_VERTICAL_RANGE = 3;
	private static final int RECOVERY_HOLD_TICKS = 30;
	private static final int CROWD_SIDESTEP_DISTANCE = 2;
	private static final int CROWD_SIDESTEP_MAX_PATH = 6;
	private static final int CROWD_SIDESTEP_MAX_PATH_CHECKS = 3;
	private static final double CROWD_JAM_RADIUS = 0.9D;
	private static final int CROWD_CHECK_INTERVAL_TICKS = 5;

	// Shared across dimensions, keyed weakly so a stopped server cannot leak into the next session.
	private static final Map<MinecraftServer, PathRequestBudget> PATH_BUDGETS = new WeakHashMap<>();
	private static final int MOVEMENT_REQUESTS_PER_TICK = 64;
	private final PathRequestBudget.Ticket pathTicket = new PathRequestBudget.Ticket();
	private boolean deferredMove;
	private Entity deferredEntity;
	private final GuardEntity guard;

	private BlockPos lastTargetPos;
	private Vec3 lastStallCheckPos = Vec3.ZERO;
	private Vec3 lastRequestedTargetPos;
	private double lastRequestedSpeed = 1.0D;
	private int lastRequestedReach = 1;
	private RouteMode lastRequestedMode = RouteMode.STATIC;
	private Vec3 lastIssuedTargetPos = Vec3.ZERO;
	private RouteMode routeMode = RouteMode.STATIC;
	private RouteMode lastIssuedRouteMode = RouteMode.STATIC;
	private long lastMoveCommandTick = Long.MIN_VALUE;
	private double lastIssuedSpeed;
	private int lastIssuedReach;
	private int stallTicks;
	private int recoveryHoldTicks;
	private boolean crowdRecoveryActive;
	private long lastCrowdCheckTick = Long.MIN_VALUE;
	private boolean lastCrowdCheckResult;
	private Vec3 unreachableTargetPos;
	private int unreachableReach;
	private long unreachableUntilTick = Long.MIN_VALUE;
	/** Destination and reach range of the path being followed; what recomputePath re-paths to. */
	private BlockPos followTarget;
	private int followReach;
	/** Destination and reach range of the last createPath request, matched up in moveTo(Path). */
	private BlockPos requestedTarget;
	private int requestedReach;

	public GuardNavigation(GuardEntity guard, Level world) {
		super(guard, world);
		this.guard = guard;
	}

	@Override
	public Path createPath(BlockPos target, int distance) {
		if (target == null) {
			return null;
		}
		this.lastTargetPos = target.immutable();
		this.requestedTarget = this.lastTargetPos;
		this.requestedReach = distance;
		// Retain progress only if this path was requested with at least this precision.
		if (this.path != null && !this.path.isDone() && target.equals(this.path.getTarget())
				&& this.followReach <= distance) {
			return this.path;
		}

		if (this.routeMode != RouteMode.STATIC || this.guard.getAirSupply() < 80 || this.guard.getSquadId() == null) {
			return this.recordRequestedPath(this.search(() -> super.createPath(target, distance)), distance);
		}

		long currentTick = this.level.getGameTime();
		Path cached = SquadRouteCache.getSquadRoute(
				this.level.dimension(),
				this.guard.getSquadId(),
				this.guard.blockPosition(),
				target,
				distance,
				currentTick);
		if (cached != null) {
			return this.recordRequestedPath(cached, distance);
		}

		Path newPath = this.search(() -> super.createPath(target, distance));
		if (newPath != null) {
			SquadRouteCache.cacheSquadRoute(this.level.dimension(), this.guard.getSquadId(), target, newPath, distance, currentTick);
		}
		return this.recordRequestedPath(newPath, distance);
	}

	@Override
	public Path createPath(Entity entity, int distance) {
		if (entity == null) {
			return null;
		}
		this.lastTargetPos = entity.blockPosition().immutable();
		this.routeMode = RouteMode.DYNAMIC;
		this.requestedTarget = this.lastTargetPos;
		this.requestedReach = distance;
		if (this.path != null && !this.path.isDone() && this.lastTargetPos.equals(this.path.getTarget())
				&& this.followReach <= distance) {
			return this.path;
		}
		return this.recordRequestedPath(this.search(() -> super.createPath(entity, distance)), distance);
	}

	private Path recordRequestedPath(Path result, int reach) {
		// Ground navigation can project a requested air/solid block onto a different surface block.
		this.requestedTarget = result == null ? null : result.getTarget();
		this.requestedReach = reach;
		return result;
	}

	@Override
	public boolean moveTo(Path newPath, double speed) {
		Path previous = this.path;
		// Vanilla compares only node lists, retaining a completed cursor even for a fresh join.
		if (newPath != null && previous != null && (previous.isDone() && !newPath.isDone()
				|| this.requestedReach < this.followReach)) {
			this.path = newPath;
		}
		boolean started = super.moveTo(newPath, speed);
		if (this.path == null) {
			this.followTarget = null;
		} else {
			this.followTarget = this.path.getTarget();
			if (this.path != previous) {
				this.followReach = this.followTarget.equals(this.requestedTarget) ? this.requestedReach : 0;
			}
		}
		return started;
	}

	/**
	 * Vanilla's version re-paths to its private targetPos, which cache hits and probe searches
	 * leave stale. Same 20-tick throttle; the shared squad route to that block is dropped as well,
	 * since the block change that triggered this may have cut it.
	 */
	@Override
	public void recomputePath() {
		long now = this.level.getGameTime();
		if (now - this.timeLastRecompute <= 20L || !this.canUpdatePath()) {
			this.hasDelayedRecomputation = true;
			return;
		}
		this.hasDelayedRecomputation = false;
		BlockPos target = this.followTarget;
		if (target == null) {
			return;
		}
		if (!this.acquireMovementBudget()) {
			this.hasDelayedRecomputation = true;
			return;
		}
		this.timeLastRecompute = now;
		SquadRouteCache.invalidateSquadRoute(this.level.dimension(), this.guard.getSquadId(), target);
		int reach = this.followReach;
		this.path = null;
		this.path = this.search(() -> super.createPath(target, reach));
		if (this.path == null) {
			this.followTarget = null;
		}
	}

	/**
	 * Runs an A* search with no current path. Vanilla createPath returns the current path when the
	 * requested block equals its private targetPos, and that field can belong to a different route
	 * here (cache hit, probe search), so the early-out could hand back a path to the wrong place.
	 * Our own createPath overrides do the early-out against the current path's real target.
	 */
	private Path search(Supplier<Path> search) {
		Path current = this.path;
		this.path = null;
		try {
			return search.get();
		} finally {
			this.path = current;
		}
	}

	@Override
	public boolean moveTo(Entity entity, double speed) {
		if (entity == null) {
			return false;
		}
		this.deferredEntity = entity;
		Vec3 targetPos = entity.position();
		if (!this.shouldIssueMove(targetPos, speed, RouteMode.DYNAMIC, 1)) {
			return !this.isDone();
		}
		this.routeMode = RouteMode.DYNAMIC;
		this.lastTargetPos = entity.blockPosition().immutable();
		return this.afterMove(targetPos, super.moveTo(entity, speed));
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
		return this.startMovingToStatic(x, y, z, speed, 1);
	}

	/**
	 * @param reachRange how close (in blocks) the path may end to the target; vanilla's default of
	 *                   1 accepts any neighbouring block, so callers that must stand on the block
	 *                   itself (formation slots) pass 0
	 */
	public boolean startMovingToStatic(BlockPos target, double speed, int reachRange) {
		if (target == null) {
			return false;
		}
		return this.startMovingToStatic(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speed, reachRange);
	}

	private boolean startMovingToStatic(double x, double y, double z, double speed, int reachRange) {
		this.deferredEntity = null;
		Vec3 targetPos = new Vec3(x, y, z);
		if (!this.shouldIssueMove(targetPos, speed, RouteMode.STATIC, reachRange)) {
			return !this.isDone();
		}
		this.routeMode = RouteMode.STATIC;
		this.lastTargetPos = BlockPos.containing(x, y, z);
		return this.afterMove(targetPos, super.moveTo(x, y, z, reachRange, speed));
	}

	public boolean startMovingToDynamic(Vec3 targetPos, double speed) {
		if (targetPos == null) {
			return false;
		}
		this.deferredEntity = null;
		if (!this.shouldIssueMove(targetPos, speed, RouteMode.DYNAMIC, 1)) {
			return !this.isDone();
		}
		this.routeMode = RouteMode.DYNAMIC;
		this.lastTargetPos = BlockPos.containing(targetPos.x, targetPos.y, targetPos.z);
		return this.afterMove(targetPos, super.moveTo(targetPos.x, targetPos.y, targetPos.z, speed));
	}

	/**
	 * True when the most recent path toward the current target could not reach it (partial path or
	 * no path). Goals use this to pick another approach instead of standing at a cliff base.
	 */
	public boolean isLastTargetUnreachable() {
		return this.unreachableTargetPos != null && this.level.getGameTime() < this.unreachableUntilTick;
	}

	/** True when stalled inside a clump of guards, or while recovering from one. */
	public boolean isCrowdBlocked() {
		return (this.crowdRecoveryActive && this.recoveryHoldTicks > 0) || this.hasNearbyGuardCrowding();
	}

	@Override
	public void tick() {
		if (this.deferredMove) {
			this.deferredMove = false;
			if (this.deferredEntity != null) {
				if (this.deferredEntity.isAlive() && this.deferredEntity.level() == this.level) {
					this.moveTo(this.deferredEntity, this.lastRequestedSpeed);
				} else {
					this.pathTicket.cancel();
					this.deferredEntity = null;
				}
			} else {
				this.resumeRequestedMove();
			}
		}
		super.tick();
		if (this.recoveryHoldTicks > 0 && (--this.recoveryHoldTicks == 0 || this.isDone())) {
			this.recoveryHoldTicks = 0;
			this.crowdRecoveryActive = false;
			this.resumeRequestedMove();
		}

		if (this.isDone()) {
			this.stallTicks = 0;
			this.lastStallCheckPos = this.guard.position();
			return;
		}

		Vec3 currentPos = this.guard.position();
		if (this.lastStallCheckPos.distanceToSqr(currentPos) < MIN_STALL_PROGRESS_SQUARED) {
			if (++this.stallTicks >= STALL_THRESHOLD_TICKS) {
				this.stallTicks = 0;
				this.lastStallCheckPos = currentPos;
				this.recoverFromStall();
			}
			return;
		}

		this.lastStallCheckPos = currentPos;
		this.stallTicks = 0;
	}

	@Override
	public void stop() {
		this.deferredMove = false;
		this.deferredEntity = null;
		this.pathTicket.cancel();
		super.stop();
		this.followTarget = null;
		this.lastRequestedTargetPos = null;
		this.lastMoveCommandTick = Long.MIN_VALUE;
		// A goal handing over movement cancels any recovery in progress.
		this.recoveryHoldTicks = 0;
		this.crowdRecoveryActive = false;
	}

	private boolean afterMove(Vec3 targetPos, boolean started) {
		Path current = this.path;
		if (current == null && !this.canUpdatePath()) {
			// Ground navigation defers searches while airborne; no route was evaluated yet.
			return started;
		}
		if (current == null || !current.canReach()) {
			this.unreachableTargetPos = targetPos;
			this.unreachableReach = this.lastRequestedReach;
			this.unreachableUntilTick = this.level.getGameTime() + UNREACHABLE_BACKOFF_TICKS;
		} else {
			this.unreachableTargetPos = null;
		}
		return started;
	}

	private void resumeRequestedMove() {
		if (this.lastRequestedTargetPos == null) {
			return;
		}
		this.lastMoveCommandTick = Long.MIN_VALUE;
		Vec3 target = this.lastRequestedTargetPos;
		if (this.lastRequestedMode == RouteMode.DYNAMIC) {
			this.startMovingToDynamic(target, this.lastRequestedSpeed);
		} else {
			this.startMovingToStatic(target.x, target.y, target.z, this.lastRequestedSpeed, this.lastRequestedReach);
		}
	}

	private void recoverFromStall() {
		BlockPos stalledTarget = this.lastTargetPos;
		if (this.routeMode == RouteMode.STATIC && stalledTarget != null) {
			SquadRouteCache.invalidateSquadRoute(this.level.dimension(), this.guard.getSquadId(), stalledTarget);
		}

		if (this.hasNearbyGuardCrowding()) {
			Path sidestep = this.findCrowdSidestep();
			if (sidestep != null) {
				this.moveTo(sidestep, this.speedModifier);
				this.crowdRecoveryActive = true;
				this.recoveryHoldTicks = RECOVERY_HOLD_TICKS;
				return;
			}
		}

		if (this.isInOrAgainstFlowingWater()) {
			BlockPos dryExit = this.findNearbyDryExit();
			if (dryExit == null) {
				BlockPos lastLand = this.guard.getLastLandPos();
				if (lastLand != null && GuardGrounding.isStandable(this.guard, lastLand)) {
					dryExit = lastLand;
				}
			}
			if (dryExit != null) {
				BlockPos exit = dryExit;
				Path exitPath = this.search(() -> super.createPath(exit, 0));
				if (exitPath != null && this.moveTo(exitPath, this.speedModifier)) {
					this.recoveryHoldTicks = RECOVERY_HOLD_TICKS;
					return;
				}
			}
		}

		// Plain re-path toward whatever the goal last asked for.
		super.stop();
		this.resumeRequestedMove();
	}

	private boolean shouldIssueMove(Vec3 targetPos, double speed, RouteMode mode, int reachRange) {
		this.lastRequestedTargetPos = targetPos;
		this.lastRequestedSpeed = speed;
		this.lastRequestedReach = reachRange;
		this.lastRequestedMode = mode;
		if (this.recoveryHoldTicks > 0 && !this.isDone()) {
			// Let the recovery move finish; the request is resumed afterwards.
			this.routeMode = mode;
			return false;
		}

		long currentTick = this.level.getGameTime();
		if (this.isDone() && this.unreachableTargetPos != null && currentTick < this.unreachableUntilTick
				&& this.unreachableReach == reachRange
				&& this.unreachableTargetPos.distanceToSqr(targetPos) < UNREACHABLE_TARGET_MOVED_SQUARED) {
			// Same unreachable target and we already stand at the end of the partial path.
			return false;
		}

		int minInterval = mode == RouteMode.DYNAMIC ? DYNAMIC_REPATH_INTERVAL_TICKS : STATIC_REPATH_INTERVAL_TICKS;
		double minDistanceSq = mode == RouteMode.DYNAMIC ? DYNAMIC_REPATH_DISTANCE_SQUARED : STATIC_REPATH_DISTANCE_SQUARED;
		boolean shouldIssue = this.isDone()
				|| this.lastMoveCommandTick == Long.MIN_VALUE
				|| this.lastIssuedRouteMode != mode
				|| this.lastIssuedReach != reachRange
				|| Math.abs(speed - this.lastIssuedSpeed) > 0.05D
				|| this.lastIssuedTargetPos.distanceToSqr(targetPos) > minDistanceSq
				|| currentTick - this.lastMoveCommandTick >= minInterval;

		if (shouldIssue) {
			// Reusing the current path does not run A*. Keep walking it while new requests wait.
			boolean reuse = this.path != null && !this.path.isDone()
					&& this.path.getTarget().equals(BlockPos.containing(targetPos)) && this.followReach <= reachRange;
			if (!reuse && !this.acquireMovementBudget()) {
				this.deferredMove = true;
				return false;
			}
			this.deferredMove = false;
			this.pathTicket.cancel();
			this.lastMoveCommandTick = currentTick;
			this.lastIssuedTargetPos = targetPos;
			this.lastIssuedRouteMode = mode;
			this.lastIssuedSpeed = speed;
			this.lastIssuedReach = reachRange;
		}
		return shouldIssue;
	}

	private boolean acquireMovementBudget() {
		// Survival probes must remain immediate. The quota governs normal movement/recompute
		// requests, not direct reachability probes or emergency air/water escape searches.
		if (!(this.level instanceof ServerLevel world) || this.guard.getAirSupply() < 80 || this.guard.isInWater()) return true;
		MinecraftServer server = world.getServer();
		return PATH_BUDGETS.computeIfAbsent(server, ignored -> new PathRequestBudget(MOVEMENT_REQUESTS_PER_TICK))
				.tryAcquire(this.pathTicket, server.getTickCount());
	}

	private boolean hasNearbyGuardCrowding() {
		long now = this.level.getGameTime();
		if (now - this.lastCrowdCheckTick < CROWD_CHECK_INTERVAL_TICKS && this.lastCrowdCheckTick != Long.MIN_VALUE) {
			return this.lastCrowdCheckResult;
		}
		this.lastCrowdCheckTick = now;
		AABB crowdBox = this.guard.getBoundingBox().inflate(CROWD_JAM_RADIUS, 0.0D, CROWD_JAM_RADIUS);
		this.lastCrowdCheckResult = this.level.hasEntities(
				EntityTypeTest.forClass(GuardEntity.class),
				crowdBox,
				entity -> entity != this.guard && entity.isAlive());
		return this.lastCrowdCheckResult;
	}

	/** Short, path-validated sidestep to a free standing spot at roughly the same height. */
	private Path findCrowdSidestep() {
		Vec3 origin = this.guard.position();
		int baseY = this.guard.getBlockY();
		double baseAngle = Math.toRadians(Math.floorMod(this.guard.getUUID().hashCode(), 360));
		int pathChecks = 0;
		for (int index = 0; index < 8 && pathChecks < CROWD_SIDESTEP_MAX_PATH_CHECKS; index++) {
			double angle = baseAngle + (Math.PI / 4.0D) * index;
			int x = (int) Math.floor(origin.x + Math.cos(angle) * CROWD_SIDESTEP_DISTANCE);
			int z = (int) Math.floor(origin.z + Math.sin(angle) * CROWD_SIDESTEP_DISTANCE);
			BlockPos candidate = GuardGrounding.findStandable(this.guard, x, z, baseY, 1, 1);
			if (candidate == null || this.isOccupiedByGuard(candidate)) {
				continue;
			}
			pathChecks++;
			Path path = this.search(() -> super.createPath(candidate, 0, CROWD_SIDESTEP_MAX_PATH));
			if (path != null && path.canReach() && path.getNodeCount() <= CROWD_SIDESTEP_MAX_PATH + 2) {
				return path;
			}
		}
		return null;
	}

	private boolean isOccupiedByGuard(BlockPos pos) {
		return this.level.hasEntities(EntityTypeTest.forClass(GuardEntity.class), new AABB(pos), entity -> entity != this.guard);
	}

	private boolean isInOrAgainstFlowingWater() {
		if (this.guard.isInWater()) {
			return true;
		}
		BlockPos origin = this.guard.blockPosition();
		if (this.isFlowingWater(origin) || this.isFlowingWater(origin.above())) {
			return true;
		}
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (this.isFlowingWater(origin.relative(direction)) || this.isFlowingWater(origin.above().relative(direction))) {
				return true;
			}
		}
		return false;
	}

	private boolean isFlowingWater(BlockPos pos) {
		var fluid = this.level.getFluidState(pos);
		return fluid.is(FluidTags.WATER) && !fluid.isSource();
	}

	/**
	 * Nearest dry standing spot around the guard (Chebyshev rings out to 5 blocks, +/-3 vertically),
	 * classified by the walk pathfinder. No heightmap probing, so roofs and canopies are never picked.
	 */
	public BlockPos findNearbyDryExit() {
		return this.findNearbyDryExit(candidate -> true);
	}

	public BlockPos findNearbyDryExit(java.util.function.Predicate<BlockPos> reachable) {
		BlockPos origin = this.guard.blockPosition();
		for (int radius = 1; radius <= WATER_EXIT_SEARCH_RADIUS; radius++) {
			BlockPos best = null;
			double bestDistanceSq = Double.MAX_VALUE;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					BlockPos candidate = GuardGrounding.findStandable(this.guard, origin.getX() + dx, origin.getZ() + dz,
							origin.getY(), WATER_EXIT_VERTICAL_RANGE, WATER_EXIT_VERTICAL_RANGE);
					if (candidate == null || !this.level.getFluidState(candidate).isEmpty()) {
						continue;
					}
					double distanceSq = candidate.distSqr(origin);
					if (distanceSq < bestDistanceSq && reachable.test(candidate)) {
						bestDistanceSq = distanceSq;
						best = candidate;
					}
				}
			}
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	/**
	 * Nearest standing spot under open sky within 8 blocks (+/-2 vertically) that a short path
	 * actually reaches. Used to walk boxed-in idle guards out of houses and doorways.
	 */
	public BlockPos findOpenSkyExit() {
		BlockPos origin = this.guard.blockPosition();
		int pathChecks = 0;
		for (int radius = 2; radius <= 8; radius += 2) {
			for (int dx = -radius; dx <= radius; dx += 2) {
				for (int dz = -radius; dz <= radius; dz += 2) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					BlockPos candidate = GuardGrounding.findStandable(this.guard, origin.getX() + dx, origin.getZ() + dz,
							origin.getY(), 2, 2);
					if (candidate == null || !this.level.canSeeSky(candidate.above())) {
						continue;
					}
					if (++pathChecks > 4) {
						return null;
					}
					Path path = this.search(() -> super.createPath(candidate, 0, 16));
					if (path != null && path.canReach()) {
						return candidate;
					}
				}
			}
		}
		return null;
	}

	/** Moves to {@code target} and keeps goals from overriding the move for a short while. */
	public void beginRecoveryMove(BlockPos target, double speed) {
		Path path = this.search(() -> super.createPath(target, 0));
		if (path != null) {
			// This is a standalone watchdog escape, not a suspended goal request.
			this.lastRequestedTargetPos = null;
			this.requestedTarget = target;
			this.requestedReach = 0;
			if (this.moveTo(path, speed)) {
				this.recoveryHoldTicks = RECOVERY_HOLD_TICKS * 2;
			}
		}
	}

	private enum RouteMode {
		STATIC,
		DYNAMIC
	}
}
