package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;
import static com.guardvillagers.gametest.GuardTestSupport.SIZE;

import com.guardvillagers.entity.GuardEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Pathing while following the owner: terrain, water, narrow bridges and large squads. */
public class GuardMovementGameTest {

	/**
	 * Two-layer ground (walk surface at y=2) with a 2-wide, 1-deep ditch across the whole arena and a
	 * 1-block ledge on the far side where the owner stands. Plain vanilla walking handles both.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 400)
	public void followsOwnerOverDitchAndOntoLedge(GameTestHelper helper) {
		GuardTestSupport.fill(helper, 0, 0, 0, SIZE - 1, 1, SIZE - 1, Blocks.STONE);
		GuardTestSupport.fill(helper, CENTER, 1, 0, CENTER + 1, 1, SIZE - 1, Blocks.AIR);
		GuardTestSupport.fill(helper, CENTER + 8, 2, 0, SIZE - 1, 2, SIZE - 1, Blocks.STONE);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 14.5, 3, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER - 12.5, 2, CENTER + 0.5, owner);
		guard.setFollowOverride(true);

		helper.succeedWhen(() -> {
			helper.assertTrue(guard.distanceTo(owner) <= 5.0F, "guard is " + guard.distanceTo(owner) + " blocks from owner at "
					+ GuardTestSupport.relative(helper, guard));
			helper.assertTrue(helper.relativeVec(guard.position()).y >= 2.9D, "guard is not up on the ledge");
		});
	}

	/**
	 * A guard standing next to a 3-deep 5x5 water pit (surface flush with the ground) gets knocked to
	 * the bottom. It must surface, climb back out and stay alive.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 440)
	public void climbsOutOfWaterPitWithoutDrowning(GameTestHelper helper) {
		GuardTestSupport.fill(helper, 0, 0, 0, SIZE - 1, 3, SIZE - 1, Blocks.STONE);
		GuardTestSupport.fill(helper, CENTER - 2, 1, CENTER - 2, CENTER + 2, 3, CENTER + 2, Blocks.WATER);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER - 4.5, 4, CENTER + 0.5);
		boolean[] dunked = {false};
		helper.runAfterDelay(40, () -> {
			var bottom = helper.absoluteVec(new net.minecraft.world.phys.Vec3(CENTER + 0.5, 1, CENTER + 0.5));
			guard.teleportTo(bottom.x, bottom.y, bottom.z);
			dunked[0] = true;
		});

		helper.onEachTick(() -> {
			if (!guard.isAlive()) {
				helper.fail("guard drowned in the pit");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(dunked[0], "waiting to knock the guard in");
			helper.assertFalse(guard.isInWater(), "guard still in water at " + GuardTestSupport.relative(helper, guard)
					+ " air=" + guard.getAirSupply() + " lastLand=" + guard.getLastLandPos());
			helper.assertTrue(guard.onGround() && helper.relativeVec(guard.position()).y >= 3.9D, "guard not standing on dry ground");
		});
	}

	/** A guard dropped into deep water with nowhere it has stood before must at least keep its head up. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 420)
	public void spawnedInDeepWaterDoesNotDrown(GameTestHelper helper) {
		GuardTestSupport.fill(helper, 0, 0, 0, SIZE - 1, 3, SIZE - 1, Blocks.STONE);
		GuardTestSupport.fill(helper, CENTER - 2, 1, CENTER - 2, CENTER + 2, 3, CENTER + 2, Blocks.WATER);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		helper.onEachTick(() -> {
			if (!guard.isAlive() || guard.getHealth() < guard.getMaxHealth()) {
				helper.fail("guard took drowning damage, air=" + guard.getAirSupply());
			}
		});
		helper.runAfterDelay(400, helper::succeed);
	}

	/**
	 * Same pit, but the owner waits on the far side, so the follow goal (which zeroes the water path
	 * penalty) wants to go straight through. The guard must cross or skirt it and survive.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 500)
	public void followsOwnerPastWaterPitWithoutDrowning(GameTestHelper helper) {
		GuardTestSupport.fill(helper, 0, 0, 0, SIZE - 1, 3, SIZE - 1, Blocks.STONE);
		GuardTestSupport.fill(helper, CENTER - 3, 1, 0, CENTER + 3, 3, SIZE - 1, Blocks.WATER);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 14.5, 4, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER - 12.5, 4, CENTER + 0.5, owner);
		guard.setFollowOverride(true);

		helper.onEachTick(() -> {
			if (!guard.isAlive()) {
				helper.fail("guard drowned following its owner across water");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.distanceTo(owner) <= 5.0F, "guard is " + guard.distanceTo(owner) + " blocks from owner at "
					+ GuardTestSupport.relative(helper, guard) + " inWater=" + guard.isInWater());
			helper.assertFalse(guard.isInWater(), "guard still in water");
		});
	}

	/**
	 * Two platforms at walk height y=6 joined by a 17-block, 1-wide bridge over a 5-block drop. The
	 * guard must cross to the owner without ever falling off.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 500)
	public void crossesOneWideBridgeWithoutFalling(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, CENTER - 15, 5, CENTER - 3, CENTER - 9, 5, CENTER + 3, Blocks.STONE);
		GuardTestSupport.fill(helper, CENTER - 8, 5, CENTER, CENTER + 8, 5, CENTER, Blocks.STONE);
		GuardTestSupport.fill(helper, CENTER + 9, 5, CENTER - 3, CENTER + 15, 5, CENTER + 3, Blocks.STONE);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 12.5, 6, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER - 12.5, 6, CENTER + 0.5, owner);
		guard.setFollowOverride(true);

		helper.onEachTick(() -> {
			double y = helper.relativeVec(guard.position()).y;
			if (y < 5.0D) {
				helper.fail("guard fell off the bridge at " + GuardTestSupport.relative(helper, guard));
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(guard.distanceTo(owner) <= 4.0F,
				"guard is " + guard.distanceTo(owner) + " blocks from owner at " + GuardTestSupport.relative(helper, guard)));
	}

	/**
	 * 32 guards owned by one player start in a grid across the arena; the owner stands in a corner.
	 * Once they have caught up (all within 10 blocks) they must stay spread out: no block holds more
	 * than two guards and they occupy at least 20 distinct blocks, held for 2 seconds straight.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 700)
	public void thirtyTwoGuardsFollowWithoutClumping(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 40.5, 1, 8.5);
		List<GuardEntity> guards = new ArrayList<>();
		for (int i = 0; i < 32; i++) {
			int gx = 6 + (i % 8) * 3;
			int gz = 26 + (i / 8) * 3;
			GuardEntity guard = GuardTestSupport.ownedGuard(helper, gx + 0.5, 1, gz + 0.5, owner);
			guard.setFollowOverride(true);
			guards.add(guard);
		}
		int[] stableTicks = {0};
		String[] lastProblem = {"never evaluated"};

		helper.onEachTick(() -> {
			String problem = clumpProblem(guards, owner);
			if (problem == null) {
				stableTicks[0]++;
			} else {
				stableTicks[0] = 0;
				lastProblem[0] = problem;
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(stableTicks[0] >= 40, "squad not settled and spread: " + lastProblem[0]));
	}

	private static String clumpProblem(List<GuardEntity> guards, ServerPlayer owner) {
		Map<BlockPos, Integer> perBlock = new HashMap<>();
		double farthest = 0.0D;
		for (GuardEntity guard : guards) {
			if (!guard.isAlive()) {
				return "a guard died";
			}
			farthest = Math.max(farthest, GuardTestSupport.horizontalDistance(guard, owner));
			perBlock.merge(guard.blockPosition(), 1, Integer::sum);
		}
		int worst = perBlock.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		if (farthest > 10.0D) {
			return String.format("farthest guard %.1f blocks from owner", farthest);
		}
		if (worst > 2) {
			return worst + " guards stacked in one block (" + perBlock.size() + " distinct blocks)";
		}
		if (perBlock.size() < 20) {
			return "only " + perBlock.size() + " distinct blocks occupied";
		}
		return null;
	}
}
