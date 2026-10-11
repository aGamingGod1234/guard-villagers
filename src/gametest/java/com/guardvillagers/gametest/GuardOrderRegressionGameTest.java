package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.commander.CommanderService;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.ActiveOrder;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OrderExecutor;
import com.guardvillagers.command.order.OrderSource;
import com.guardvillagers.command.order.Scope;
import com.guardvillagers.entity.FormationType;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.voice.VoiceConfig;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Regression coverage for the order lifecycle through the live command and commander entry points. */
public class GuardOrderRegressionGameTest {

	private record Squad(ServerPlayer owner, GuardEntity alpha, GuardEntity bravo) {
	}

	private static Squad squad(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity alpha = GuardTestSupport.ownedGuard(helper, CENTER - 5.5, 1, CENTER - 3.5, owner);
		GuardEntity bravo = GuardTestSupport.ownedGuard(helper, CENTER - 5.5, 1, CENTER + 4.5, owner);
		alpha.setGroupIndex(0);
		alpha.setGroupName("Alpha");
		bravo.setGroupIndex(1);
		bravo.setGroupName("Bravo");
		GuardHierarchy.appointCommander(helper.getLevel().getServer(), owner.getUUID(), alpha);
		CommanderService.setAutoCommand(helper.getLevel().getServer(), owner.getUUID(), false);
		return new Squad(owner, alpha, bravo);
	}

	private static void command(GameTestHelper helper, ServerPlayer owner, String command) {
		helper.assertTrue(GuardTestSupport.runCommand(owner, command) > 0, "command failed: " + command);
	}

	private static List<ActiveOrder> orders(Squad squad) {
		return CommanderService.activeOrders(squad.owner().level().getServer(), squad.owner().getUUID());
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void renamedGroupStillReleasesOriginalHold(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			command(helper, squad.owner(), "guards say Alpha hold the line");
			helper.assertTrue(squad.alpha().isHoldingPosition(), "setup: Alpha was not held");
			squad.alpha().setGroupName("Renamed");
			command(helper, squad.owner(), "guards say as you were");
			helper.assertFalse(squad.alpha().isHoldingPosition(), "renaming left the original recipient held");
			helper.assertTrue(orders(squad).isEmpty(), "cleared hold remained in book");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void typedGroupFollowSplitsRoleAttackRecipients(GameTestHelper helper) {
		Squad squad = squad(helper);
		var cow = helper.spawn(EntityType.COW, new Vec3(CENTER + 12.5, 1, CENTER + 0.5));
		cow.setNoAi(true);
		helper.runAfterDelay(5, () -> {
			squad.owner().lookAt(EntityAnchorArgument.Anchor.EYES, cow.getEyePosition());
			command(helper, squad.owner(), "guards say swordsmen attack my target");
		});
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(cow.getUUID().equals(squad.alpha().getMainTargetUuid())
					&& cow.getUUID().equals(squad.bravo().getMainTargetUuid()), "setup: both swordsmen need attack directives");
			command(helper, squad.owner(), "guards follow Alpha");
			helper.assertTrue(orders(squad).stream().anyMatch(order -> order.order().action() == Action.ATTACK_TARGET
					&& OrderExecutor.covers(order, squad.bravo(), squad.alpha())
					&& !OrderExecutor.covers(order, squad.alpha(), squad.alpha())), "role attack did not retain only Bravo");
		});
		helper.runAfterDelay(30, () -> {
			helper.assertFalse(cow.getUUID().equals(squad.alpha().getMainTargetUuid()), "role attack resumed on Alpha");
			helper.assertTrue(squad.alpha().hasFollowOverride(), "Alpha lost typed follow");
			helper.assertTrue(cow.getUUID().equals(squad.bravo().getMainTargetUuid()), "Bravo lost its unrelated attack");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void typedMovementCancelsFormationBeforeSettingState(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			for (String typed : List.of("follow", "stay", "hold")) {
				command(helper, squad.owner(), "guards say form a line");
				helper.assertTrue(squad.alpha().getRallyPoint().isPresent(), "setup: formation rally missing");
				command(helper, squad.owner(), "guards " + typed);
				for (GuardEntity guard : List.of(squad.alpha(), squad.bravo())) {
					helper.assertTrue(guard.getRallyPoint().isEmpty(), typed + " retained the formation rally");
					helper.assertTrue(guard.getFormationType() == FormationType.FOLLOW, typed + " retained formation type");
					if (typed.equals("follow")) helper.assertTrue(guard.hasFollowOverride() && !guard.isStaying(), "follow lost its state");
					if (typed.equals("stay")) helper.assertTrue(guard.isStaying(), "cleanup undid typed stay");
					if (typed.equals("hold")) helper.assertTrue(guard.isHoldingPosition(), "cleanup undid typed hold");
				}
				helper.assertTrue(orders(squad).isEmpty(), typed + " left discarded orders");
			}
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void statusPreservesCommanderFormationPost(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			command(helper, squad.owner(), "guards say form a line");
			BlockPos post = squad.alpha().getRallyPoint().orElseThrow();
			long generation = squad.alpha().getRallyGeneration();
			CommanderService.submit(squad.owner(), List.of(Order.of(Action.STATUS_REPORT, Scope.ALL)), OrderSource.VOICE);
			helper.assertTrue(squad.alpha().getRallyPoint().filter(post::equals).isPresent(), "status moved the formation post");
			helper.assertTrue(squad.alpha().getRallyGeneration() == generation, "status replaced the existing rally");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void oldReportingApproachCannotReplaceNewFormation(GameTestHelper helper) {
		Squad squad = squad(helper);
		BlockPos[] post = {null};
		long[] generation = {0L};
		helper.runAfterDelay(5, () -> {
			command(helper, squad.owner(), "guards say commander come here");
			helper.assertTrue(squad.alpha().getRallyPoint().isPresent(), "setup: commander did not approach");
			command(helper, squad.owner(), "guards say form a line");
			post[0] = squad.alpha().getRallyPoint().orElseThrow();
			generation[0] = squad.alpha().getRallyGeneration();
			GuardTestSupport.moveTo(helper, squad.owner(), CENTER + 8.5, 1, CENTER + 0.5);
		});
		helper.runAfterDelay(24, () -> {
			helper.assertTrue(squad.alpha().getRallyPoint().filter(post[0]::equals).isPresent(), "old approach followed the moved owner");
			helper.assertTrue(squad.alpha().getRallyGeneration() == generation[0], "old approach replaced formation generation");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void invalidAttackDoesNotReplaceHold(GameTestHelper helper) {
		Squad squad = squad(helper);
		var villager = helper.spawn(EntityType.VILLAGER, new Vec3(CENTER + 12.5, 1, CENTER + 0.5));
		villager.setNoAi(true);
		var stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(CENTER + 0.5, 1, CENTER + 12.5));
		helper.runAfterDelay(5, () -> {
			command(helper, squad.owner(), "guards say hold the line");
			long original = orders(squad).getFirst().id();
			for (LivingEntity invalid : List.of(villager, stand)) {
				squad.owner().lookAt(EntityAnchorArgument.Anchor.EYES, invalid.getEyePosition());
				command(helper, squad.owner(), "guards say attack my target");
				helper.assertTrue(orders(squad).size() == 1 && orders(squad).getFirst().id() == original,
						"invalid attack replaced hold for " + invalid.getType());
				helper.assertTrue(squad.alpha().isHoldingPosition() && squad.bravo().isHoldingPosition(), "invalid attack released hold");
			}
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void cancellingAttackWithdrawsPassiveAnimalDirective(GameTestHelper helper) {
		Squad squad = squad(helper);
		var cow = helper.spawn(EntityType.COW, new Vec3(CENTER + 12.5, 1, CENTER + 0.5));
		cow.setNoAi(true);
		helper.runAfterDelay(5, () -> {
			squad.owner().lookAt(EntityAnchorArgument.Anchor.EYES, cow.getEyePosition());
			command(helper, squad.owner(), "guards say attack my target");
		});
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(cow.getUUID().equals(squad.bravo().getMainTargetUuid()), "setup: attack directive missing");
			command(helper, squad.owner(), "guards say as you were");
		});
		helper.runAfterDelay(25, () -> {
			for (GuardEntity guard : List.of(squad.alpha(), squad.bravo())) {
				helper.assertFalse(cow.getUUID().equals(guard.getMainTargetUuid()), "cancelled attack directive survived");
				helper.assertTrue(guard.getTarget() != cow, "cancelled attack kept the passive target");
			}
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void restoredTimedFormationReleasesSavedState(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			command(helper, squad.owner(), "guards say form a line");
			var server = helper.getLevel().getServer();
			CommanderService.forget(server, squad.owner().getUUID());
			CommanderService.statusLine(squad.owner());
			CommanderService.tick(server);
			helper.assertTrue(orders(squad).isEmpty(), "restored timed order still locks the guards");
			for (GuardEntity guard : List.of(squad.alpha(), squad.bravo())) {
				helper.assertTrue(guard.getFormationType() == FormationType.FOLLOW, "restored formation type survived cleanup");
				helper.assertTrue(guard.getRallyPoint().isEmpty(), "restored formation rally survived cleanup");
			}
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void savedCleanupAppliesWhenRecipientReturns(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			var server = helper.getLevel().getServer();
			squad.bravo().setHoldPosition(true);
			ActiveOrder cleared = new ActiveOrder(900, Order.of(Action.HOLD, Scope.group("Former Name")),
					OrderSource.VOICE, 0L, ActiveOrder.NEVER).withRecipients(Set.of(squad.bravo().getUUID()));
			GuardHierarchy.state(server).getOrCreate(squad.owner().getUUID()).queueCleanup(cleared);
			CommanderService.statusLine(squad.owner());
			CommanderService.tick(server);
			helper.assertFalse(squad.bravo().isHoldingPosition(), "returning recipient kept its removed hold");
			helper.assertTrue(GuardHierarchy.state(server).getOrCreate(squad.owner().getUUID()).pendingCleanup().isEmpty(),
					"applied cleanup was not drained");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void entityLoadReleasesPendingHoldWhileOwnerIsOffline(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		var level = helper.getLevel();
		var server = level.getServer();
		UUID owner = UUID.randomUUID();
		GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(level, EntitySpawnReason.LOAD);
		helper.assertTrue(guard != null, "guard entity type did not create a guard");
		try {
			guard.setPos(helper.absoluteVec(new Vec3(CENTER + 0.5, 1, CENTER + 0.5)));
			guard.setOwnerUuid(owner);
			guard.setHoldPosition(true);
			var saved = GuardHierarchy.state(server).getOrCreate(owner);
			saved.queueCleanup(new ActiveOrder(901, Order.of(Action.HOLD, Scope.ALL),
					OrderSource.VOICE, 0L, ActiveOrder.NEVER).withRecipients(Set.of(guard.getUUID())));
			helper.assertTrue(server.getPlayerList().getPlayer(owner) == null, "setup: owner must be offline");
			helper.assertTrue(guard.isHoldingPosition() && saved.pendingCleanup().size() == 1,
					"setup: pending hold must exist before entity load");
			helper.assertTrue(level.addFreshEntity(guard), "guard was not added to the server level");
			helper.assertFalse(guard.isHoldingPosition(), "ENTITY_LOAD did not release offline owner's pending hold");
			helper.assertTrue(saved.pendingCleanup().isEmpty(), "ENTITY_LOAD did not drain the applied cleanup");
		} finally {
			guard.discard();
			CommanderService.forget(server, owner);
		}
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void lateGroupMemberIsNotAnOriginalOrderRecipient(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			command(helper, squad.owner(), "guards say Alpha hold the line");
			ActiveOrder hold = orders(squad).getFirst();
			squad.bravo().setGroupIndex(0);
			squad.bravo().setGroupName("Alpha");
			helper.assertTrue(OrderExecutor.covers(hold, squad.alpha(), squad.alpha()), "original recipient lost coverage");
			helper.assertFalse(OrderExecutor.covers(hold, squad.bravo(), squad.alpha()), "new group member acquired an old order lock");
			command(helper, squad.owner(), "guards say as you were");
			helper.assertFalse(squad.alpha().isHoldingPosition(), "original recipient did not release");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void commanderSettingsControlDefaultCadenceAndExpiry(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			var server = helper.getLevel().getServer();
			ServerPlayer fresh = GuardTestSupport.survivalPlayer(helper, CENTER + 10.5, 1, CENTER + 10.5);
			try {
				CommanderService.configure(new VoiceConfig.Commander(false, 7, 3));
				helper.assertFalse(CommanderService.autoCommand(server, fresh.getUUID()), "configured auto default ignored");
				helper.assertTrue(CommanderService.planIntervalTicks() == 7, "configured plan cadence ignored");
				command(helper, squad.owner(), "guards say form a line");
				ActiveOrder formation = orders(squad).getFirst();
				helper.assertTrue(formation.expiresAtTick() - formation.issuedAtTick() == 60L, "configured expiry ignored");
				CommanderService.setAutoCommand(server, fresh.getUUID(), true);
				helper.assertTrue(CommanderService.autoCommand(server, fresh.getUUID()), "explicit auto choice did not override default");
			} finally {
				// Settings are global: never leave a nondefault value across ticks of concurrent GameTests.
				CommanderService.configure(VoiceConfig.defaults().commander());
			}
			helper.succeed();
		});
	}
}
