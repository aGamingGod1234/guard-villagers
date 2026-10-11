package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.navigation.GuardGrounding;
import com.guardvillagers.navigation.GuardNavigation;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/** Regression tests for grounding, route reuse, recovery and exact follow slots. */
public class GuardNavigationRegressionGameTest {
	private static GuardEntity stationaryGuard(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		guard.setNoAi(true);
		guard.setOnGround(true);
		return guard;
	}

	private static void assertExactPath(GameTestHelper helper, Path path, BlockPos target) {
		helper.assertTrue(path != null && path.canReach() && path.getEndNode() != null
				&& path.getEndNode().asBlockPos().equals(target), "path must reach the exact requested block");
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void unavailablePathDoesNotBecomeUnreachableBackoff(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		GuardNavigation navigation = guard.getGuardNavigation();
		BlockPos target = guard.blockPosition().offset(6, 0, 0);
		// A newly spawned or jumping guard can receive its first request before it lands.
		guard.setOnGround(false);
		helper.assertFalse(navigation.startMovingToStatic(target, 1.0D, 0), "airborne pathfinding should be unavailable");
		helper.assertFalse(navigation.isLastTargetUnreachable(), "unavailable navigation must not mark the target unreachable");
		guard.setOnGround(true);
		helper.assertTrue(navigation.startMovingToStatic(target, 1.0D, 0), "landing must allow an immediate retry");
		assertExactPath(helper, navigation.getPath(), target);

		// Actual unreachable terrain must still retain the existing retry backoff.
		navigation.stop();
		helper.setBlock(new BlockPos(CENTER + 6, 3, CENTER), Blocks.STONE);
		BlockPos raised = helper.absolutePos(new BlockPos(CENTER + 6, 4, CENTER));
		navigation.startMovingToStatic(raised, 1.0D, 0);
		helper.assertTrue(navigation.isLastTargetUnreachable(), "an isolated high platform must remain unreachable");
		Path partial = navigation.getPath();
		helper.assertTrue(partial != null && !partial.canReach(), "fixture must yield a partial path");
		partial.setNextNodeIndex(partial.getNodeCount());
		helper.assertFalse(navigation.startMovingToStatic(raised, 1.0D, 0), "an unchanged unreachable target must back off");
		helper.assertTrue(navigation.getPath() == partial, "backoff must retain the completed partial path");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void stricterReachReplacesActiveRouteImmediately(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		GuardNavigation navigation = guard.getGuardNavigation();
		BlockPos target = guard.blockPosition().offset(6, 0, 0);
		helper.assertTrue(navigation.startMovingToStatic(target, 1.0D, 1), "loose route should start");
		Path loose = navigation.getPath();
		helper.assertTrue(loose.getEndNode().asBlockPos().distManhattan(target) == 1,
				"fixture must stop beside the target at reach 1");
		helper.assertTrue(navigation.startMovingToStatic(target, 1.0D, 0), "strict request should start in the same tick");
		assertExactPath(helper, navigation.getPath(), target);
		helper.assertTrue(navigation.getPath() != loose, "strict request must replace the loose active path");
		navigation.stop();
		helper.assertTrue(navigation.startMovingToStatic(target.above(4), 1.0D, 1), "air request should project onto ground");
		helper.assertTrue(navigation.getPath().getTarget().equals(target), "fixture must normalize the target height");
		navigation.startMovingToStatic(target, 1.0D, 0);
		assertExactPath(helper, navigation.getPath(), target);
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void recoveryResumesLatestExactRequest(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		GuardNavigation navigation = guard.getGuardNavigation();
		BlockPos target = guard.blockPosition().offset(6, 0, 0);
		navigation.beginRecoveryMove(guard.blockPosition().offset(0, 0, 3), 1.0D);
		Path escape = navigation.getPath();
		helper.assertTrue(escape != null && !escape.isDone(), "escape should start");
		navigation.startMovingToStatic(target, 1.0D, 1);
		navigation.startMovingToStatic(target, 1.0D, 0);
		helper.assertTrue(navigation.getPath() == escape, "pending requests must leave the escape active");
		escape.setNextNodeIndex(escape.getNodeCount());
		navigation.tick();
		assertExactPath(helper, navigation.getPath(), target);
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void crowdRecoveryPreservesSuspendedExactRequest(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		GuardEntity neighbour = GuardTestSupport.swordsman(helper, CENTER + 0.8, 1, CENTER + 0.5);
		neighbour.setNoAi(true);
		neighbour.setOnGround(true);
		GuardNavigation navigation = guard.getGuardNavigation();
		BlockPos target = guard.blockPosition().offset(6, 0, 0);
		helper.assertTrue(navigation.startMovingToStatic(target, 1.0D, 0), "exact request should start");
		// Tick navigation without moving the mob to reproduce a crowd stall deterministically.
		for (int tick = 0; tick < 41; tick++) {
			navigation.tick();
		}
		Path sidestep = navigation.getPath();
		helper.assertTrue(sidestep != null && !sidestep.getTarget().equals(target), "crowd stall should take a sidestep");
		sidestep.setNextNodeIndex(sidestep.getNodeCount());
		navigation.tick();
		assertExactPath(helper, navigation.getPath(), target);
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void standaloneEscapeDoesNotResumeCancelledOrCompletedRequests(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		GuardNavigation navigation = guard.getGuardNavigation();
		BlockPos oldTarget = guard.blockPosition().offset(6, 0, 0);
		for (boolean cancelled : new boolean[] {true, false}) {
			navigation.startMovingToStatic(oldTarget, 1.0D, 0);
			if (cancelled) {
				navigation.stop();
			} else {
				navigation.getPath().setNextNodeIndex(navigation.getPath().getNodeCount());
			}
			navigation.beginRecoveryMove(guard.blockPosition().offset(0, 0, 3), 1.0D);
			Path escape = navigation.getPath();
			helper.assertTrue(escape != null && !escape.isDone(), "escape should start");
			escape.setNextNodeIndex(escape.getNodeCount());
			navigation.tick();
			helper.assertTrue(navigation.isDone(), "escape must not resurrect the old goal destination");
		}
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void completedCachedRouteCanRestartWithFreshCursor(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		guard.setSquadId(UUID.randomUUID());
		GuardNavigation navigation = guard.getGuardNavigation();
		BlockPos target = guard.blockPosition().offset(6, 0, 0);
		Path original = navigation.createPath(target, 0);
		helper.assertTrue(navigation.moveTo(original, 1.0D), "original path should start");
		original.setNextNodeIndex(original.getNodeCount());
		Path rejoined = navigation.createPath(target, 0);
		helper.assertTrue(rejoined != original && rejoined.sameAs(original) && !rejoined.isDone(),
				"cache fixture must supply matching nodes with a fresh cursor");
		helper.assertTrue(navigation.moveTo(rejoined, 1.0D), "completed route must accept a usable new cursor");
		helper.assertTrue(navigation.getPath() == rejoined, "new cursor must be installed");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void dryWaterBorderIsAValidExitButHazardsAreNot(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		GuardTestSupport.fill(helper, CENTER - 4, 0, CENTER - 4, CENTER + 4, 0, CENTER + 4, Blocks.WATER);
		helper.setBlock(new BlockPos(CENTER + 1, 0, CENTER), Blocks.STONE);
		BlockPos bank = helper.absolutePos(new BlockPos(CENTER + 1, 1, CENTER));
		helper.assertTrue(WalkNodeEvaluator.getPathTypeStatic(guard, bank) == PathType.WATER_BORDER,
				"fixture must be a narrow dry water border");
		helper.assertTrue(GuardGrounding.isStandable(guard, bank), "dry water border must be standable");
		helper.assertTrue(bank.equals(guard.getGuardNavigation().findNearbyDryExit()), "search must find the narrow bank");
		guard.setPathfindingMalus(PathType.WATER_BORDER, -1.0F);
		helper.assertFalse(GuardGrounding.isStandable(guard, bank), "negative malus must be respected");
		guard.setPathfindingMalus(PathType.WATER_BORDER, 8.0F);
		helper.setBlock(new BlockPos(CENTER + 1, 0, CENTER), Blocks.MAGMA_BLOCK);
		helper.assertFalse(GuardGrounding.isStandable(guard, bank), "damaging floor must remain excluded");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void groundingPrefersNearbyStreetOverRoof(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		GuardTestSupport.fill(helper, CENTER + 3, 1, CENTER, CENTER + 3, 4, CENTER, Blocks.STONE);
		BlockPos wall = helper.absolutePos(new BlockPos(CENTER + 3, 1, CENTER));
		BlockPos found = GuardGrounding.findStandableNear(guard, wall.getX(), wall.getZ(), wall.getY(), 6, 3, 1);
		helper.assertTrue(found != null && found.getY() == wall.getY() && !found.equals(wall),
				"street beside the wall must win over its supported roof");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void groundingNeverLoadsMissingChunks(GameTestHelper helper) {
		GuardEntity guard = stationaryGuard(helper);
		var level = helper.getLevel();
		int chunkX = 60000;
		int chunkZ = 60000;
		BlockPos interior = new BlockPos((chunkX << 4) + 8, guard.getBlockY(), (chunkZ << 4) + 8);
		helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "fixture must start unloaded");
		helper.assertFalse(GuardGrounding.isStandable(guard, interior), "unloaded saved land must be rejected");
		helper.assertTrue(GuardGrounding.findStandable(guard, interior.getX(), interior.getZ(), interior.getY(), 1, 1) == null,
				"unloaded column must be rejected");
		helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "grounding must not load the candidate");
		// Only fixture preparation may load a chunk. The neighbouring FULL chunk must stay absent.
		var chunk = level.getChunk(chunkX, chunkZ);
		BlockPos edge = new BlockPos((chunkX << 4) + 15, guard.getBlockY(), interior.getZ());
		chunk.setBlockState(edge.below(), Blocks.STONE.defaultBlockState(), 16);
		chunk.setBlockState(edge, Blocks.AIR.defaultBlockState(), 16);
		chunk.setBlockState(edge.above(), Blocks.AIR.defaultBlockState(), 16);
		helper.assertTrue(level.getChunkSource().getChunkNow(chunkX + 1, chunkZ) == null, "border neighbour must be unloaded");
		helper.assertFalse(GuardGrounding.isStandable(guard, edge), "classification needs the missing neighbour");
		helper.assertTrue(GuardGrounding.findStandable(guard, edge.getX(), edge.getZ(), edge.getY(), 1, 1) == null,
				"column scan must also reject the incomplete neighbourhood");
		helper.assertTrue(level.getChunkSource().getChunkNow(chunkX + 1, chunkZ) == null,
				"border classification must not load its neighbour");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 250)
	public void airborneOwnerKeepsGroundedFormationAndTracksHorizontalMovement(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 3.5, 1, CENTER + 0.5, owner);
		guard.setFollowOverride(true);
		owner.setNoGravity(true);
		Vec3 hover = helper.absoluteVec(new Vec3(CENTER + 3.5, 5, CENTER + 0.5));
		helper.runAfterDelay(30, () -> {
			owner.teleportTo(hover.x, hover.y, hover.z);
			owner.setOnGround(false);
		});
		int[] stable = {0};
		GuardTestSupport.onEachTick(helper, () -> {
			Vec3 exact = guard.getResolvedSlotPosition();
			if (owner.getY() == hover.y && exact != null && guard.distanceToSqr(exact) < 0.3D * 0.3D
					&& Math.abs(exact.y - (hover.y - 4)) < 0.01D
					&& Math.abs(Math.hypot(exact.x - hover.x, exact.z - hover.z) - 2.0D) < 0.01D) {
				stable[0]++;
			} else {
				stable[0] = 0;
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(stable[0] >= 20, "guard must settle in its grounded ring around the moving airborne owner"));
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 250)
	public void displacedFollowerReturnsToExactSlot(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 3.5, 1, CENTER + 0.5, owner);
		guard.setFollowOverride(true);
		Vec3[] exact = {null};
		Vec3[] displaced = {null};
		long[] displacedAt = {-1L};
		int[] settledTicks = {0};
		int[] returnedTicks = {0};
		boolean[] resumed = {false};
		GuardTestSupport.onEachTick(helper, () -> {
			if (displacedAt[0] < 0L) {
				Vec3 slotPosition = guard.getResolvedSlotPosition();
				boolean settled = slotPosition != null && guard.canFollowOwnerFormation()
						// Initial rest uses the goal's departure radius; return accuracy is asserted below.
						&& guard.distanceToSqr(slotPosition) <= 0.75D * 0.75D && guard.getNavigation().isDone()
						&& guard.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4D;
				settledTicks[0] = settled ? settledTicks[0] + 1 : 0;
				if (settledTicks[0] < 10) {
					return;
				}
				exact[0] = slotPosition;
				// Displace a genuinely settled follower into the old block-centred dead zone.
				BlockPos slot = BlockPos.containing(exact[0]);
				BlockPos neighbour = slot.offset(exact[0].x < slot.getX() + 0.5D ? 1 : -1, 0, 0);
				displaced[0] = Vec3.atBottomCenterOf(neighbour);
				guard.teleportTo(displaced[0].x, displaced[0].y, displaced[0].z);
				guard.setDeltaMovement(Vec3.ZERO);
				guard.setOnGround(true);
				displacedAt[0] = guard.level().getGameTime();
				return;
			}
			long elapsed = guard.level().getGameTime() - displacedAt[0];
			resumed[0] |= guard.getNavigation().isInProgress()
					|| guard.distanceToSqr(displaced[0]) > 0.05D * 0.05D
					&& guard.distanceToSqr(exact[0]) < displaced[0].distanceToSqr(exact[0]);
			helper.assertTrue(elapsed <= 12L || resumed[0], "displaced follower did not resume within 12 server ticks");
			returnedTicks[0] = guard.distanceToSqr(exact[0]) <= 0.25D * 0.25D ? returnedTicks[0] + 1 : 0;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(displacedAt[0] >= 0L, "waiting for the follower to settle before displacement: position="
					+ guard.position() + " slot=" + guard.getResolvedSlotPosition() + " navigationDone=" + guard.getNavigation().isDone()
					+ " velocity=" + guard.getDeltaMovement() + " following=" + guard.canFollowOwnerFormation());
			helper.assertTrue(resumed[0], "settled guard must leave a neighbouring slot");
			helper.assertTrue(returnedTicks[0] >= 20, "follower must finish at its exact ring position");
		});
	}
}
