package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.commander.CommanderService;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OrderSource;
import com.guardvillagers.command.order.Scope;
import com.guardvillagers.command.order.TargetRef;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import com.guardvillagers.entity.ai.GuardAiIntent;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Regressions exercised through the server commander tick and the guard's real AI. */
public class GuardCommanderRegressionGameTest {
	private static GuardEntity guard(GameTestHelper helper, ServerPlayer owner, double x, double z) {
		GuardEntity guard = GuardTestSupport.guard(helper, x, 1, z, GuardRole.BOWMAN);
		guard.setOwnerUuid(owner.getUUID());
		guard.setStaying(false);
		return guard;
	}

	private static void plan(ServerPlayer owner) {
		try {
			CommanderService.statusLine(owner);
			var contexts = CommanderService.class.getDeclaredField("CONTEXTS");
			contexts.setAccessible(true);
			Object context = ((Map<?, ?>) contexts.get(null)).get(owner.getUUID());
			var field = context.getClass().getDeclaredField("brain");
			field.setAccessible(true);
			Object brain = field.get(context);
			var request = brain.getClass().getDeclaredMethod("requestPlan");
			request.setAccessible(true);
			request.invoke(brain);
			CommanderService.tick(owner.level().getServer());
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(error);
		}
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 100)
	public void woundedRecoveryInterruptsCombatUntilHalfHealth(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24.5, 1, 24.5);
		GuardEntity guard = guard(helper, owner, 34.5, 24.5);
		var enemy = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new Vec3(43.5, 1, 24.5)));
		enemy.setNoAi(true);
		guard.setTarget(enemy);
		guard.setMainTarget(enemy);
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(guard.onGround() && guard.getTarget() == enemy, "setup: guard must be grounded and fighting before it is wounded");
			guard.setHealth(guard.getMaxHealth() * 0.27F);
			plan(owner);
		});
		helper.runAfterDelay(13, () -> {
			helper.assertTrue(guard.isCommanderRecovering(), "27% health never entered commander recovery");
			helper.assertTrue(guard.isAiIntent(GuardAiIntent.RALLY), "recovery did not take precedence over combat");
			helper.assertTrue(guard.getTarget() == null, "wounded guard kept its active combat target");
			guard.setHealth(guard.getMaxHealth() * 0.45F);
			plan(owner);
		});
		helper.runAfterDelay(35, () -> {
			helper.assertTrue(guard.isCommanderRecovering(), "recovery ended before the 50% exit threshold");
			helper.assertTrue(guard.distanceToSqr(owner) < 80, "guard failed to move toward its recovery post: distance="
					+ guard.distanceTo(owner) + " rally=" + guard.getRallyPoint() + " debug=" + guard.getDebugSnapshot(3));
			guard.setHealth(guard.getMaxHealth() * 0.6F);
			plan(owner);
			helper.assertFalse(guard.isCommanderRecovering(), "healed guard stayed in recovery");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void foreignRallyReplacesAutomaticRecovery(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24.5, 1, 24.5);
		GuardEntity guard = guard(helper, owner, 36.5, 24.5);
		guard.setHealth(guard.getMaxHealth() * 0.27F);
		plan(owner);
		BlockPos foreign = helper.absolutePos(new BlockPos(35, 1, 35));
		guard.rallyTo(foreign, 100);
		long generation = guard.getRallyGeneration();
		plan(owner);
		helper.assertTrue(guard.getRallyPoint().orElseThrow().equals(foreign), "automatic recovery overwrote the whistle/bell rally");
		helper.assertTrue(guard.getRallyGeneration() == generation, "commander replaced a foreign rally");
		helper.assertFalse(guard.isCommanderRecovering(), "player rally failed to end recovery");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void reinforcementAddsFreshFighters(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24.5, 1, 24.5);
		var enemy = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new Vec3(44.5, 1, 24.5)));
		enemy.setNoAi(true);
		GuardEntity first = guard(helper, owner, 42.5, 23.5);
		GuardEntity second = guard(helper, owner, 42.5, 25.5);
		GuardEntity reserve = guard(helper, owner, 28.5, 23.5);
		GuardEntity otherReserve = guard(helper, owner, 28.5, 25.5);
		for (GuardEntity guard : List.of(first, second, reserve, otherReserve)) {
			guard.setNoAi(true);
			guard.setHome(owner.blockPosition(), 32);
		}
		for (GuardEntity guard : List.of(first, second)) {
			guard.setHealth(guard.getMaxHealth() * 0.55F);
			guard.setTarget(enemy);
		}
		plan(owner);
		var assignments = CommanderService.lastAssignments(owner.getUUID());
		helper.assertTrue(enemy.getUUID().equals(assignments.get(reserve.getUUID())), "first reserve was not sent");
		helper.assertTrue(enemy.getUUID().equals(assignments.get(otherReserve.getUUID())), "second reserve was not sent");
		helper.assertFalse(assignments.containsKey(first.getUUID()) || assignments.containsKey(second.getUUID()), "existing fighters counted as reinforcements");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void hiddenEnemyDoesNotBecomeAnAutomaticDirective(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24.5, 1, 24.5);
		GuardEntity guard = guard(helper, owner, 23.5, 24.5);
		guard.setNoAi(true);
		GuardTestSupport.fill(helper, 38, 1, 22, 42, 6, 26, Blocks.STONE);
		GuardTestSupport.fill(helper, 39, 1, 23, 41, 4, 25, Blocks.AIR);
		var enemy = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new Vec3(40.5, 1, 24.5)));
		enemy.setNoAi(true);
		helper.assertFalse(owner.hasLineOfSight(enemy) || guard.hasLineOfSight(enemy), "setup: sealed enemy is visible");
		plan(owner);
		helper.assertTrue(CommanderService.lastAssignments(owner.getUUID()).isEmpty(), "hidden enemy was assigned through terrain");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void thirdHomeUsesTheFourthScanBox(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 3.5, 1, 3.5);
		GuardEntity first = guard(helper, owner, 35.5, 5.5);
		GuardEntity second = guard(helper, owner, 5.5, 35.5);
		GuardEntity third = guard(helper, owner, 35.5, 35.5);
		for (GuardEntity guard : List.of(first, second, third)) {
			guard.setNoAi(true);
			guard.setHome(guard.blockPosition(), 8);
		}
		for (Vec3 point : List.of(new Vec3(37.5, 1, 7.5), new Vec3(7.5, 1, 37.5), new Vec3(37.5, 1, 37.5))) {
			var enemy = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, point));
			enemy.setNoAi(true);
		}
		plan(owner);
		helper.assertTrue(CommanderService.lastAssignments(owner.getUUID()).size() == 3,
				"one of the three distant homes never received its scan box");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 100)
	public void narrowerFollowStopsBroaderAttackPressing(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24.5, 1, 24.5);
		GuardEntity alpha = guard(helper, owner, 20.5, 21.5);
		GuardEntity bravo = guard(helper, owner, 20.5, 27.5);
		alpha.setGroupIndex(0); alpha.setGroupName("Alpha");
		bravo.setGroupIndex(1); bravo.setGroupName("Bravo");
		var target = helper.spawn(EntityType.COW, new Vec3(39.5, 1, 24.5));
		target.setNoAi(true);
		owner.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
		CommanderService.submit(owner, List.of(Order.attack(Scope.ALL, TargetRef.MY_TARGET)), OrderSource.VOICE);
		CommanderService.submit(owner, List.of(Order.of(Action.FOLLOW, Scope.group("Alpha"))), OrderSource.VOICE);
		plan(owner);
		helper.runAfterDelay(3, () -> {
			helper.assertTrue(alpha.getTarget() != target && !target.getUUID().equals(alpha.getMainTargetUuid()), "broad attack overrode Alpha follow");
			helper.assertTrue(bravo.getTarget() == target || target.getUUID().equals(bravo.getMainTargetUuid()), "Bravo lost its standing attack");
			helper.succeed();
		});
	}

	@SuppressWarnings("unchecked")
	private static Int2ObjectMap<Raid> raidMap(Raids raids) {
		try {
			var map = Raids.class.getDeclaredField("raidMap");
			map.setAccessible(true);
			return (Int2ObjectMap<Raid>) map.get(raids);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(error);
		}
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void raidResponseStaysLocalAndRespectsManualChanges(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24.5, 1, 24.5);
		GuardEntity local = guard(helper, owner, 20.5, 24.5);
		GuardEntity manual = guard(helper, owner, 21.5, 24.5);
		GuardEntity unrelated = guard(helper, owner, 22.5, 24.5);
		for (GuardEntity guard : List.of(local, manual, unrelated)) {
			guard.setNoAi(true);
			guard.setBehavior(GuardBehavior.PERIMETER);
			guard.setHome(local.blockPosition(), 24);
		}
		unrelated.setHome(local.blockPosition().offset(300, 0, 0), 24);
		Raid raid = new Raid(local.blockPosition(), helper.getLevel().getDifficulty());
		int raidId = -Math.abs(owner.getUUID().hashCode());
		var raids = raidMap(helper.getLevel().getRaids());
		raids.put(raidId, raid);
		Vec3 original = owner.position();
		try {
			owner.setPos(original.add(150, 0, 0));
			plan(owner);
			helper.assertTrue(local.getBehavior() == GuardBehavior.DEFENSIVE, "raid at a distant home was ignored");
			helper.assertTrue(local.getRallyPoint().orElseThrow().equals(raid.getCenter()), "patrol rotation replaced the raid post");
			helper.assertTrue(unrelated.getBehavior() == GuardBehavior.PERIMETER
					&& unrelated.getRallyPoint().map(post -> post.distSqr(unrelated.getHome().orElseThrow()) <= 24 * 24).orElse(true)
					&& !GuardHierarchy.state(helper.getLevel().getServer()).getOrCreate(owner.getUUID()).raidBehaviors().containsKey(unrelated.getUUID()),
					"unrelated garrison was retasked");
			manual.setBehavior(GuardBehavior.CROWD_CONTROL);
			plan(owner);
			helper.assertTrue(manual.getBehavior() == GuardBehavior.CROWD_CONTROL, "active raid erased a player choice");
			// Cycling back to the raid's value still counts as an explicit player choice.
			manual.setBehavior(GuardBehavior.DEFENSIVE);
			CommanderService.setAutoCommand(helper.getLevel().getServer(), owner.getUUID(), false);
			helper.assertTrue(local.getBehavior() == GuardBehavior.PERIMETER, "auto off failed to restore raid behavior");
			helper.assertTrue(local.getRallyPoint().isEmpty(), "auto off left a commander rally active");
			helper.assertTrue(manual.getBehavior() == GuardBehavior.DEFENSIVE, "auto off erased the latest player choice");
		} finally {
			raids.remove(raidId);
			owner.setPos(original);
		}
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void raidCleanupRunsWithoutLocalGuards(GameTestHelper helper) {
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24.5, 1, 24.5);
		var server = helper.getLevel().getServer();
		var remoteLevel = server.getLevel(Level.NETHER);
		GuardEntity remote = GuardVillagersMod.GUARD_ENTITY_TYPE.create(remoteLevel, EntitySpawnReason.COMMAND);
		remote.setPos(0.5, 100, 0.5);
		remoteLevel.addFreshEntity(remote);
		remote.setOwnerUuid(owner.getUUID());
		remote.setNoAi(true);
		remote.setCommanderRaidBehavior(GuardBehavior.OFFENSIVE);
		try {
			GuardHierarchy.state(server).getOrCreate(owner.getUUID()).recordRaidBehavior(remote.getUUID(),
					GuardBehavior.PERIMETER, GuardBehavior.OFFENSIVE, remoteLevel.dimension().identifier().toString(), remote.blockPosition());
			plan(owner);
			helper.assertTrue(remote.getBehavior() == GuardBehavior.PERIMETER, "empty current-dimension roster blocked remote raid cleanup");
		} finally {
			remote.discard();
		}
		helper.succeed();
	}
}
