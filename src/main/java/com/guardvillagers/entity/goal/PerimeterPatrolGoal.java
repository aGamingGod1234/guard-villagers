package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardBehaviorExecutor;
import com.guardvillagers.navigation.GuardGrounding;
import com.guardvillagers.village.VillageDescriptor;
import com.guardvillagers.village.VillageManagerHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Walks the village edge and interior.
 *
 * <p>Each waypoint is grounded near the guard's own height with the pathfinder's walkability
 * rules (no heightmap tops, so no roofs), and is skipped if the path to it cannot reach it. A
 * waypoint is chosen once and walked to; the old goal re-resolved a slot and re-issued the move on
 * every tick, and rebuilt (re-jittered) all waypoints every 120 ticks.
 */
public final class PerimeterPatrolGoal extends Goal {
	private static final int EDGE_STEP = 6;
	private static final int EDGE_JITTER = 2;
	private static final int INTERIOR_STEP = 8;
	private static final int INTERIOR_JITTER = 3;
	private static final int MIN_NEXT_DISTANCE_SQ = 36;
	private static final int VILLAGE_REFRESH_TICKS = 200;
	private static final int CAN_USE_CHECK_TICKS = 20;
	private static final int MODE_SWITCH_BASE = 400;
	private static final double ARRIVE_DISTANCE_SQUARED = 2.5D * 2.5D;
	private static final int GROUND_MAX_UP = 6;
	private static final int GROUND_MAX_DOWN = 6;
	private static final int MAX_WAYPOINT_ATTEMPTS = 3;
	private static final int WAYPOINT_TIMEOUT_TICKS = 400;
	/** After every candidate waypoint failed, wait this long before spending more A* searches. */
	private static final int NO_WAYPOINT_BACKOFF_TICKS = 40;

	private final GuardEntity guard;
	private final double speed;
	private final Random shuffleRandom = new Random();
	private final List<BlockPos> edgePoints = new ArrayList<>();
	private final List<BlockPos> interiorPoints = new ArrayList<>();
	private VillageDescriptor village;
	private boolean patrollingInterior;
	private int pointIndex;
	private long nextVillageRefresh;
	private long nextCanUseCheck;
	private int modeSwitchTicks;
	private BlockPos currentWaypoint;
	private long waypointDeadline;
	private long nextWaypointSearch;

	public PerimeterPatrolGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		if (!this.guard.isBehaviorExecutor(GuardBehaviorExecutor.PERIMETER)
				|| !(this.guard.level() instanceof ServerLevel world)) {
			return false;
		}
		long now = world.getGameTime();
		if (now < this.nextCanUseCheck) {
			return false;
		}
		this.nextCanUseCheck = now + CAN_USE_CHECK_TICKS;
		return this.refreshVillageData(world);
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isBehaviorExecutor(GuardBehaviorExecutor.PERIMETER)
				&& (!this.edgePoints.isEmpty() || !this.interiorPoints.isEmpty());
	}

	@Override
	public void start() {
		this.modeSwitchTicks = MODE_SWITCH_BASE + this.guard.getRandom().nextInt(200);
		this.currentWaypoint = null;
	}

	@Override
	public void stop() {
		this.currentWaypoint = null;
		this.nextWaypointSearch = 0L;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!(this.guard.level() instanceof ServerLevel world)) {
			return;
		}
		long now = world.getGameTime();
		if (now >= this.nextVillageRefresh) {
			this.refreshVillageData(world);
		}

		this.modeSwitchTicks -= 2;
		if (this.modeSwitchTicks <= 0) {
			this.patrollingInterior = !this.patrollingInterior;
			this.modeSwitchTicks = MODE_SWITCH_BASE + this.guard.getRandom().nextInt(200);
			List<BlockPos> active = this.activePoints();
			if (!active.isEmpty()) {
				this.shuffleRandom.setSeed(this.guard.getRandom().nextLong());
				Collections.shuffle(active, this.shuffleRandom);
				this.pointIndex = 0;
			}
			this.currentWaypoint = null;
		}

		if (this.currentWaypoint != null) {
			boolean arrived = this.guard.distanceToSqr(this.currentWaypoint.getX() + 0.5D, this.currentWaypoint.getY(),
					this.currentWaypoint.getZ() + 0.5D) < ARRIVE_DISTANCE_SQUARED;
			boolean stalled = this.guard.getNavigation().isDone() || now >= this.waypointDeadline;
			if (!arrived && !stalled) {
				return;
			}
			this.currentWaypoint = null;
		}
		this.chooseNextWaypoint(now);
	}

	private void chooseNextWaypoint(long now) {
		if (now < this.nextWaypointSearch) {
			return;
		}
		List<BlockPos> points = this.activePoints();
		if (points.isEmpty()) {
			this.patrollingInterior = !this.patrollingInterior;
			points = this.activePoints();
			if (points.isEmpty()) {
				return;
			}
		}
		for (int attempt = 0; attempt < MAX_WAYPOINT_ATTEMPTS; attempt++) {
			this.advancePoint(points);
			BlockPos point = points.get(this.pointIndex % points.size());
			if (!this.guard.canTargetWithinZone(point)) {
				continue;
			}
			BlockPos grounded = GuardGrounding.findStandableNear(this.guard, point.getX(), point.getZ(),
					this.guard.getBlockY(), GROUND_MAX_UP, GROUND_MAX_DOWN, 1);
			if (grounded == null) {
				continue;
			}
			this.guard.getGuardNavigation().startMovingToStatic(grounded, this.speed);
			if (this.guard.getGuardNavigation().isLastTargetUnreachable()) {
				continue;
			}
			this.currentWaypoint = grounded;
			this.waypointDeadline = now + WAYPOINT_TIMEOUT_TICKS;
			return;
		}
		// Nothing reachable right now (fenced in, village edge across water): up to three path
		// searches per goal tick until it changes was pure waste.
		this.guard.getNavigation().stop();
		this.nextWaypointSearch = now + NO_WAYPOINT_BACKOFF_TICKS;
	}

	private void advancePoint(List<BlockPos> points) {
		if (points.size() <= 1) {
			this.pointIndex = 0;
			return;
		}
		BlockPos guardPos = this.guard.blockPosition();
		int attempts = 0;
		do {
			int advance = 1 + this.guard.getRandom().nextInt(Math.min(4, points.size()));
			this.pointIndex = (this.pointIndex + advance) % points.size();
			attempts++;
		} while (attempts < 3 && guardPos.distSqr(points.get(this.pointIndex)) < MIN_NEXT_DISTANCE_SQ);
	}

	private List<BlockPos> activePoints() {
		return this.patrollingInterior ? this.interiorPoints : this.edgePoints;
	}

	private boolean refreshVillageData(ServerLevel world) {
		this.nextVillageRefresh = world.getGameTime() + VILLAGE_REFRESH_TICKS;
		Optional<VillageDescriptor> descriptor = VillageManagerHandler.findVillageDescriptor(world, this.guard.blockPosition());
		if (descriptor.isEmpty()) {
			return !this.edgePoints.isEmpty() || !this.interiorPoints.isEmpty();
		}
		VillageDescriptor found = descriptor.get();
		if (this.village == null || !found.bounds().equals(this.village.bounds())) {
			this.village = found;
			this.rebuildPatrolPoints();
		}
		return !this.edgePoints.isEmpty() || !this.interiorPoints.isEmpty();
	}

	private void rebuildPatrolPoints() {
		this.edgePoints.clear();
		this.interiorPoints.clear();
		this.currentWaypoint = null;
		if (this.village == null) {
			return;
		}

		int minX = this.village.bounds().minX();
		int maxX = this.village.bounds().maxX();
		int minZ = this.village.bounds().minZ();
		int maxZ = this.village.bounds().maxZ();
		int y = this.village.center().getY();

		for (int x = minX; x <= maxX; x += EDGE_STEP) {
			this.edgePoints.add(this.jittered(x, y, minZ, EDGE_JITTER));
			this.edgePoints.add(this.jittered(x, y, maxZ, EDGE_JITTER));
		}
		for (int z = minZ + EDGE_STEP; z < maxZ; z += EDGE_STEP) {
			this.edgePoints.add(this.jittered(minX, y, z, EDGE_JITTER));
			this.edgePoints.add(this.jittered(maxX, y, z, EDGE_JITTER));
		}
		for (int x = minX + INTERIOR_STEP; x < maxX; x += INTERIOR_STEP) {
			for (int z = minZ + INTERIOR_STEP; z < maxZ; z += INTERIOR_STEP) {
				this.interiorPoints.add(this.jittered(x, y, z, INTERIOR_JITTER));
			}
		}

		// Different guards start at different points so they spread around the perimeter.
		int guardSeed = Math.floorMod(this.guard.getId(), 1 << 16);
		if (!this.edgePoints.isEmpty()) {
			Collections.rotate(this.edgePoints, guardSeed % this.edgePoints.size());
		}
		if (!this.interiorPoints.isEmpty()) {
			Collections.rotate(this.interiorPoints, guardSeed % this.interiorPoints.size());
		}
		this.pointIndex = 0;
	}

	private BlockPos jittered(int x, int y, int z, int jitter) {
		int offsetX = this.guard.getRandom().nextIntBetweenInclusive(-jitter, jitter);
		int offsetZ = this.guard.getRandom().nextIntBetweenInclusive(-jitter, jitter);
		return new BlockPos(x + offsetX, y, z + offsetZ);
	}
}
