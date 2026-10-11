package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.hierarchy.GuardRank;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardBehaviorExecutor;
import com.guardvillagers.entity.goal.RaidTacticsGoal;
import com.guardvillagers.entity.goal.ReturnToLandGoal;
import com.guardvillagers.navigation.GuardGrounding;
import com.guardvillagers.village.GuardBellRally;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Regressions from review reports 17, 18 and A09. */
public class GuardBehaviorRegressionGameTest {
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 180)
	public void displacedHoldReturnsToExactBlock(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		guard.setHoldPosition(true);
		BlockPos post = guard.getHoldOrigin();
		helper.runAfterDelay(10, () -> guard.teleportTo(post.getX() + 1.5D, post.getY(), post.getZ() + 0.5D));
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(guard.blockPosition().equals(post), "guard stopped beside hold post: " + guard.blockPosition());
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 220)
	public void holdUnderRoofDoesNotTriggerWatchdog(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, 22, 4, 22, 26, 4, 26, Blocks.STONE);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		guard.setHoldPosition(true);
		BlockPos post = guard.getHoldOrigin();
		GuardTestSupport.onEachTick(helper, () -> helper.assertTrue(guard.blockPosition().equals(post), "watchdog moved held guard"));
		helper.runAfterDelay(200, helper::succeed);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 300)
	public void settledCrowdPostUnderRoofDoesNotTriggerWatchdog(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, 19, 4, 19, 29, 4, 29, Blocks.STONE);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		guard.setHome(guard.blockPosition(), 24);
		guard.setBehavior(GuardBehavior.CROWD_CONTROL);
		Vec3[] settled = {null};
		GuardTestSupport.onEachTick(helper, () -> {
			if (helper.getTick() < 80) return;
			if (settled[0] == null) settled[0] = guard.position();
			helper.assertTrue(guard.distanceToSqr(settled[0]) < 1.0D,
					"watchdog moved settled crowd guard: " + guard.position());
		});
		helper.runAfterDelay(280, helper::succeed);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 340)
	public void whistleTemporarilyOverridesHoldAndRestoresPost(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		var owner = GuardTestSupport.survivalPlayer(helper, 34.5, 1, 24.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24.5, 1, 24.5, owner);
		guard.setRank(GuardRank.CAPTAIN);
		guard.setHoldPosition(true);
		BlockPos post = guard.getHoldOrigin();
		owner.setShiftKeyDown(true);
		owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GuardVillagersMod.GUARD_WHISTLE));
		GuardVillagersMod.GUARD_WHISTLE.use(helper.getLevel(), owner, InteractionHand.MAIN_HAND);
		helper.assertTrue(guard.getRallyPoint().isPresent(), "whistle did not rally captain");
		helper.assertTrue(post.equals(guard.getHoldOrigin()), "whistle erased hold post");
		helper.runAfterDelay(80, () -> helper.assertTrue(guard.blockPosition().distSqr(post) > 9.0D, "hold prevented temporary rally"));
		helper.runAfterDelay(320, () -> {
			helper.assertTrue(guard.isHoldingPosition() && guard.blockPosition().equals(post), "guard did not resume hold post");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 280)
	public void whistlePreservesStayAndNewHoldSupersedesRally(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		var owner = GuardTestSupport.survivalPlayer(helper, 34.5, 1, 24.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24.5, 1, 24.5, owner);
		guard.setRank(GuardRank.CAPTAIN);
		guard.setStaying(true);
		BlockPos post = guard.getStayOrigin();
		owner.setShiftKeyDown(true);
		GuardVillagersMod.GUARD_WHISTLE.use(helper.getLevel(), owner, InteractionHand.MAIN_HAND);
		helper.assertTrue(guard.isStaying() && post.equals(guard.getStayOrigin()), "whistle erased stay post");
		helper.runAfterDelay(250, () -> {
			helper.assertTrue(guard.getRallyPoint().isEmpty() && guard.isStaying() && post.equals(guard.getStayOrigin()), "stay did not survive expiry");
			guard.rallyTo(owner.blockPosition(), 240);
			guard.setHoldPosition(true);
			helper.assertTrue(guard.getRallyPoint().isEmpty() && guard.isHoldingPosition(), "new hold did not supersede rally");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void whistleEntityInteractionReachesItemHandler(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		var owner = GuardTestSupport.survivalPlayer(helper, 22.5, 1, 24.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24.5, 1, 24.5, owner);
		owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GuardVillagersMod.GUARD_WHISTLE));
		owner.interactOn(guard, InteractionHand.MAIN_HAND, Vec3.ZERO);
		helper.assertTrue(guard.getHome().filter(guard.blockPosition()::equals).isPresent(), "whistle home click was consumed by mob");
		int radius = guard.getPatrolRadius();
		owner.setShiftKeyDown(true);
		owner.interactOn(guard, InteractionHand.MAIN_HAND, Vec3.ZERO);
		helper.assertTrue(guard.getPatrolRadius() == radius + 8, "whistle radius click was consumed by mob");
		var stranger = GuardTestSupport.survivalPlayer(helper, 26.5, 1, 24.5);
		stranger.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GuardVillagersMod.GUARD_WHISTLE));
		stranger.setShiftKeyDown(true);
		stranger.interactOn(guard, InteractionHand.MAIN_HAND, Vec3.ZERO);
		helper.assertTrue(guard.getPatrolRadius() == radius + 8, "stranger changed guard patrol radius");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void unauthorizedBellCannotConsumeAnotherOwnersCooldown(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		var alice = GuardTestSupport.survivalPlayer(helper, 20.5, 1, 24.5);
		var bob = GuardTestSupport.survivalPlayer(helper, 28.5, 1, 24.5);
		var a = GuardTestSupport.ownedGuard(helper, 22.5, 1, 24.5, alice);
		var b = GuardTestSupport.ownedGuard(helper, 26.5, 1, 24.5, bob);
		BlockPos bell = helper.absolutePos(new BlockPos(24, 1, 24));
		a.setHome(bell, 24);
		b.setHome(bell, 24);
		helper.setBlock(new BlockPos(24, 1, 24), Blocks.BELL);
		var bellBlock = (net.minecraft.world.level.block.BellBlock) Blocks.BELL;
		helper.assertTrue(bellBlock.attemptToRing(bob, helper.getLevel(), bell, null), "bell did not ring");
		helper.assertTrue(a.getRallyPoint().isEmpty(), "unauthorized ring rallied Alice's guard");
		helper.assertTrue(b.getRallyPoint().isPresent(), "authorized ring did not rally Bob's guard");
		bellBlock.attemptToRing(alice, helper.getLevel(), bell, null);
		helper.assertTrue(a.getRallyPoint().isPresent(), "Bob consumed Alice's rally allowance");
		helper.assertTrue(GuardBellRally.onBellRung(helper.getLevel(), bell, null) == 0, "redstone bypassed recipient cooldown");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void groundingPrefersAdjacentStreetToRoof(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, 24, 1, 24, 24, 4, 24, Blocks.STONE);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 20.5, 1, 24.5);
		BlockPos requested = helper.absolutePos(new BlockPos(24, 1, 24));
		BlockPos result = GuardGrounding.findStandableNear(guard, requested.getX(), requested.getZ(), requested.getY(), 6, 6, 1);
		helper.assertTrue(result != null && result.getY() == requested.getY(), "roof selected before street: " + result);
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 220)
	public void returnsToLandFromShallowPuddle(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		// Signs retain a single water cell while allowing dry, same-height walking destinations.
		GuardTestSupport.fill(helper, 23, 1, 23, 25, 1, 25, Blocks.OAK_SIGN);
		helper.setBlock(new BlockPos(24, 1, 24), Blocks.WATER);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		helper.runAfterDelay(160, () -> {
			helper.assertFalse(guard.isInWater(), "guard stopped in water beside dry exit");
			helper.assertTrue(guard.onGround(), "guard has not arrived on dry ground");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void landSearchSkipsUnreachableDryRoom(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, 18, 1, 18, 30, 1, 30, Blocks.WATER);
		GuardTestSupport.fill(helper, 21, 1, 23, 23, 3, 25, Blocks.STONE);
		GuardTestSupport.fill(helper, 22, 1, 24, 22, 2, 24, Blocks.AIR);
		GuardTestSupport.fill(helper, 27, 1, 23, 29, 1, 25, Blocks.STONE);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		guard.setNoAi(true);
		guard.setOnGround(true);
		ReturnToLandGoal goal = new ReturnToLandGoal(guard, 1.0D);
		helper.runAfterDelay(5, () -> {
			goal.start();
			goal.tick();
			BlockPos destination = (BlockPos) field(goal, "destination");
			helper.assertTrue(destination != null && !destination.equals(helper.absolutePos(new BlockPos(22, 1, 24))),
					"sealed nearest dry room suppressed reachable exit");
			var path = guard.getNavigation().getPath();
			helper.assertTrue(path != null && path.canReach() && path.getEndNode().asBlockPos().equals(destination),
					"land route did not reach exact dry destination");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void emptyLandSearchHonorsDeadline(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, 12, 1, 12, 36, 3, 36, Blocks.WATER);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		guard.setNoAi(true);
		guard.setOnGround(true);
		ReturnToLandGoal goal = new ReturnToLandGoal(guard, 1.0D);
		long[] deadline = {0L};
		helper.runAfterDelay(5, () -> {
			goal.start();
			goal.tick();
			helper.assertTrue(field(goal, "destination") == null, "test unexpectedly found land");
			deadline[0] = (long) field(goal, "nextRetarget");
		});
		helper.runAfterDelay(7, () -> {
			goal.tick();
			helper.assertTrue((long) field(goal, "nextRetarget") == deadline[0], "empty search bypassed throttle");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 180)
	public void seekAirSkipsSealedPocketForOpenOutlet(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, 19, 1, 19, 29, 4, 29, Blocks.STONE);
		GuardTestSupport.fill(helper, 20, 1, 20, 28, 3, 28, Blocks.WATER);
		GuardTestSupport.fill(helper, 21, 1, 23, 23, 3, 25, Blocks.STONE);
		GuardTestSupport.fill(helper, 22, 1, 24, 22, 2, 24, Blocks.AIR);
		GuardTestSupport.fill(helper, 25, 4, 23, 26, 4, 25, Blocks.AIR);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		guard.setAirSupply(50);
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.isAlive() && guard.getHealth() == guard.getMaxHealth(), "guard drowned seeking outlet at " + GuardTestSupport.relative(helper, guard));
			helper.assertFalse(guard.isUnderWater(), "guard did not escape sealed pocket trap: " + GuardTestSupport.relative(helper, guard));
			helper.assertTrue(guard.getAirSupply() > 60, "air has not recovered");
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void retreatSkipsUnreachableClericForHome(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, 25, 5, 21, 31, 5, 27, Blocks.STONE);
		var cleric = helper.spawn(EntityType.VILLAGER, new Vec3(28.5, 6, 24.5));
		cleric.setNoAi(true);
		cleric.setVillagerData(cleric.getVillagerData().withProfession(helper.getLevel().registryAccess(), VillagerProfession.CLERIC));
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		BlockPos home = helper.absolutePos(new BlockPos(16, 1, 24));
		guard.setHome(home, 24);
		guard.setNoAi(true);
		guard.setOnGround(true);
		helper.runAfterDelay(5, () -> {
			BlockPos refuge = guard.findSafeRetreatPoint(helper.getLevel());
			helper.assertTrue(refuge.distSqr(home) < 16 && refuge.getY() == home.getY(), "unreachable cleric suppressed home: " + refuge);
			var path = guard.getNavigation().createPath(refuge, 0);
			helper.assertTrue(path != null && path.canReach(), "fallback refuge is not reachable");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void raidApproachesNearbyHiddenRaiderAndThrottlesEmptySearch(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardTestSupport.fill(helper, 25, 1, 22, 25, 3, 26, Blocks.STONE);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 24.5, 1, 24.5);
		guard.setNoAi(true);
		guard.setOnGround(true);
		Object controller = field(guard, "aiController");
		setField(controller, "behaviorExecutor", GuardBehaviorExecutor.RAID_OFFENSIVE);
		var raider = helper.spawn(EntityType.PILLAGER, new Vec3(27.5, 1, 24.5));
		raider.setNoAi(true);
		RaidTacticsGoal goal = new RaidTacticsGoal(guard, 1.0D);
		goal.start();
		setField(goal, "raid", new Raid(guard.blockPosition(), Difficulty.NORMAL));
		long[] deadline = {0L};
		helper.runAfterDelay(5, () -> {
			helper.assertFalse(guard.hasLineOfSight(raider), "test wall does not block sight");
			goal.tick();
			helper.assertFalse(guard.getNavigation().isDone(), "nearby hidden raider stopped offensive approach");
			raider.discard();
			goal.tick();
			helper.assertTrue(field(goal, "seekTarget") == null, "dead raider was not cleared");
			deadline[0] = (long) field(goal, "nextRaiderSearch");
		});
		helper.runAfterDelay(7, () -> {
			goal.tick();
			helper.assertTrue((long) field(goal, "nextRaiderSearch") == deadline[0], "empty raider search bypassed throttle");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void anchorSlotDoesNotAcceptAdjacentPathEndpoint(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.swordsman(helper, 27.5, 1, 25.5);
		guard.setNoAi(true);
		guard.setOnGround(true);
		BlockPos slot = helper.absolutePos(new BlockPos(26, 1, 25));
		Vec3 exact = helper.absoluteVec(new Vec3(26.0094D, 1, 25.8121D));
		helper.runAfterDelay(5, () -> {
			try {
				Class<?> type = Class.forName("com.guardvillagers.entity.goal.AnchorHold");
				var constructor = type.getDeclaredConstructor();
				constructor.setAccessible(true);
				var hold = constructor.newInstance();
				var tick = type.getDeclaredMethod("tick", GuardEntity.class, BlockPos.class, Vec3.class, double.class);
				tick.setAccessible(true);
				tick.invoke(hold, guard, slot, exact, 1.0D);
				var path = guard.getNavigation().getPath();
				helper.assertTrue(path != null && path.canReach() && path.getEndNode().asBlockPos().equals(slot),
						"anchor path accepted adjacent block outside final-step range");
				helper.succeed();
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	private static Object field(Object target, String name) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
