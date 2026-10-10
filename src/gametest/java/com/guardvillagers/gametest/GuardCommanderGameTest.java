package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.commander.CommanderCallouts;
import com.guardvillagers.command.commander.CommanderService;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.command.hierarchy.GuardRank;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Chain of command, text orders through {@code /guards say}, and the commander's own decisions. */
public class GuardCommanderGameTest {

	private static GuardEntity owned(GameTestHelper helper, ServerPlayer owner, double x, double z, GuardRole role, int xp) {
		GuardEntity guard = GuardTestSupport.guard(helper, x, 1, z, role);
		guard.setOwnerUuid(owner.getUUID());
		guard.setStaying(false);
		guard.setExperience(xp);
		return guard;
	}

	private static void group(GuardEntity guard, int index, String name) {
		guard.setGroupIndex(index);
		guard.setGroupName(name);
	}

	/** A zombie that stands still and never burns: a fixed threat for the commander to react to. */
	private static Zombie statue(GameTestHelper helper, double x, double z) {
		Zombie zombie = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new Vec3(x, 1, z)));
		zombie.setNoAi(true);
		return zombie;
	}

	/** Floor plus a 6-high wall round the arena, so missed arrows never land in a neighbouring test. */
	private static void walledFloor(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		int max = GuardTestSupport.SIZE - 1;
		GuardTestSupport.fill(helper, 0, 1, 0, max, 6, 0, Blocks.STONE);
		GuardTestSupport.fill(helper, 0, 1, max, max, 6, max, Blocks.STONE);
		GuardTestSupport.fill(helper, 0, 1, 0, 0, 6, max, Blocks.STONE);
		GuardTestSupport.fill(helper, max, 1, 0, max, 6, max, Blocks.STONE);
	}

	private static boolean calloutContains(ServerPlayer owner, String text) {
		for (String line : CommanderCallouts.recent(owner.getUUID())) {
			if (line.toLowerCase(java.util.Locale.ROOT).contains(text)) {
				return true;
			}
		}
		return false;
	}

	// ---- hierarchy ----

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void commanderIsMostSeniorGuard(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity rookie = owned(helper, owner, CENTER - 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 0);
		GuardEntity sergeant = owned(helper, owner, CENTER - 1.5, CENTER + 0.5, GuardRole.SWORDSMAN, 240);
		GuardEntity veteran = owned(helper, owner, CENTER + 2.5, CENTER + 0.5, GuardRole.BOWMAN, 600);
		helper.succeedWhen(() -> {
			helper.assertTrue(veteran.getRank() == GuardRank.COMMANDER, "600 XP guard is " + veteran.getRank());
			helper.assertTrue(rookie.getRank() == GuardRank.SOLDIER && sergeant.getRank() == GuardRank.SOLDIER,
					"others should be soldiers: " + rookie.getRank() + ", " + sergeant.getRank());
			helper.assertTrue(veteran.isSquadLeader(), "commander carries the squad-leader flag");
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 120)
	public void commanderSuccessionOnDeath(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity commander = owned(helper, owner, CENTER + 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 1000);
		GuardEntity alphaCaptain = owned(helper, owner, CENTER - 3.5, CENTER + 2.5, GuardRole.SWORDSMAN, 600);
		GuardEntity alphaSoldier = owned(helper, owner, CENTER - 3.5, CENTER - 2.5, GuardRole.SWORDSMAN, 0);
		GuardEntity betaCaptain = owned(helper, owner, CENTER + 6.5, CENTER + 6.5, GuardRole.BOWMAN, 480);
		GuardEntity betaSoldier = owned(helper, owner, CENTER + 6.5, CENTER + 8.5, GuardRole.BOWMAN, 0);
		group(alphaCaptain, 0, "Alpha");
		group(alphaSoldier, 0, "Alpha");
		group(betaCaptain, 1, "Beta");
		group(betaSoldier, 1, "Beta");
		int[] killedAt = {-1};
		helper.runAfterDelay(20, () -> {
			helper.assertTrue(commander.getRank() == GuardRank.COMMANDER, "setup: commander not elected");
			helper.assertTrue(alphaCaptain.getRank() == GuardRank.CAPTAIN, "setup: alpha captain not elected");
			commander.kill(helper.getLevel());
			killedAt[0] = (int) helper.getTick();
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(killedAt[0] >= 0, "waiting for the kill");
			helper.assertTrue(alphaCaptain.getRank() == GuardRank.COMMANDER, "senior captain not promoted, rank " + alphaCaptain.getRank());
			helper.assertTrue(helper.getTick() - killedAt[0] <= 5, "succession took more than 5 ticks");
			helper.assertTrue(alphaSoldier.getRank() == GuardRank.CAPTAIN, "alpha did not get a new captain in the same pass");
			helper.assertTrue(calloutContains(owner, "has fallen"), "no succession callout: " + CommanderCallouts.recent(owner.getUUID()));
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 300)
	public void appointedCommanderIsPinned(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity rookie = owned(helper, owner, CENTER - 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 0);
		GuardEntity veteran = owned(helper, owner, CENTER + 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 1100);
		owned(helper, owner, CENTER + 0.5, CENTER + 3.5, GuardRole.BOWMAN, 500);
		helper.runAfterDelay(10, () -> GuardHierarchy.appointCommander(helper.getLevel().getServer(), owner.getUUID(), rookie));
		helper.onEachTick(() -> {
			if (helper.getTick() > 12 && rookie.getRank() != GuardRank.COMMANDER) {
				helper.fail("appointed commander lost its rank at tick " + helper.getTick() + "; veteran is " + veteran.getRank());
			}
			if (helper.getTick() % 50 == 0) {
				GuardHierarchy.requestElection(owner.getUUID());
			}
		});
		helper.runAfterDelay(240, helper::succeed);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void captainPerGroup(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity commander = owned(helper, owner, CENTER - 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 900);
		GuardEntity alpha = owned(helper, owner, CENTER - 3.5, CENTER + 3.5, GuardRole.SWORDSMAN, 100);
		GuardEntity beta = owned(helper, owner, CENTER + 3.5, CENTER + 0.5, GuardRole.BOWMAN, 300);
		GuardEntity betaSoldier = owned(helper, owner, CENTER + 3.5, CENTER + 3.5, GuardRole.BOWMAN, 0);
		group(commander, 0, "Alpha");
		group(alpha, 0, "Alpha");
		group(beta, 1, "Beta");
		group(betaSoldier, 1, "Beta");
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(commander.getRank() == GuardRank.COMMANDER, "commander " + commander.getRank());
			helper.assertTrue(alpha.getRank() == GuardRank.CAPTAIN && alpha.isSquadLeader(), "alpha captain " + alpha.getRank());
			helper.assertTrue(beta.getRank() == GuardRank.CAPTAIN && beta.isSquadLeader(), "beta captain " + beta.getRank());
			helper.assertFalse(betaSoldier.isSquadLeader(), "beta soldier marked leader");
			// The whistle's sneak-use rallies leaders; it must now reach the captains.
			owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GuardVillagersMod.GUARD_WHISTLE));
			owner.setShiftKeyDown(true);
			GuardVillagersMod.GUARD_WHISTLE.use(helper.getLevel(), owner, InteractionHand.MAIN_HAND);
			owner.setShiftKeyDown(false);
			helper.assertTrue(alpha.getRallyPoint().isPresent() && beta.getRallyPoint().isPresent(), "whistle rally missed the captains");
			helper.assertTrue(betaSoldier.getRallyPoint().isEmpty(), "whistle rally reached a soldier");
			helper.succeed();
		});
	}

	/** Pins for guards that are not loaded neither put a pinned captain in command nor block a new appointment. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void stalePinsDoNotBlockAppointments(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity veteran = owned(helper, owner, CENTER - 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 900);
		GuardEntity sergeant = owned(helper, owner, CENTER + 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 300);
		GuardEntity rookie = owned(helper, owner, CENTER + 0.5, CENTER + 3.5, GuardRole.SWORDSMAN, 0);
		group(veteran, 0, "Alpha");
		group(rookie, 0, "Alpha");
		var server = helper.getLevel().getServer();
		// Before the first election: the veteran is a pinned captain, and three pins belong to guards not loaded.
		var command = GuardHierarchy.state(server).getOrCreate(owner.getUUID());
		command.pinCaptain(veteran.getUUID());
		for (int i = 0; i < 3; i++) {
			command.pinCaptain(UUID.randomUUID());
		}
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(sergeant.getRank() == GuardRank.COMMANDER, "an unpinned guard should command, sergeant is "
					+ sergeant.getRank() + ", pinned veteran is " + veteran.getRank());
			helper.assertTrue(veteran.getRank() == GuardRank.CAPTAIN, "pinned captain is " + veteran.getRank());
			for (int i = 0; i < 64; i++) {
				command.pinCaptain(UUID.randomUUID());
			}
			helper.assertTrue(command.pinnedCaptains().size() == 64, "setup: pins not full");
			helper.assertTrue(GuardHierarchy.appointCaptain(server, owner.getUUID(), rookie) == GuardHierarchy.AppointResult.APPOINTED,
					"appointment refused");
			helper.assertTrue(GuardHierarchy.isPinned(server, owner.getUUID(), rookie.getUUID()),
					"appointment reported but not pinned when the pin list was full");
			helper.succeed();
		});
	}

	// ---- text orders ----

	private record Squad(ServerPlayer owner, List<GuardEntity> guards, GuardEntity stranger) {
	}

	private static Squad squad(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		ServerPlayer other = GuardTestSupport.survivalPlayer(helper, CENTER + 10.5, 1, CENTER + 10.5);
		List<GuardEntity> guards = new ArrayList<>();
		guards.add(owned(helper, owner, CENTER - 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 200));
		guards.add(owned(helper, owner, CENTER - 3.5, CENTER + 2.5, GuardRole.BOWMAN, 0));
		GuardEntity stranger = owned(helper, other, CENTER + 8.5, CENTER + 8.5, GuardRole.SWORDSMAN, 0);
		return new Squad(owner, guards, stranger);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void sayFollowMe(GameTestHelper helper) {
		Squad squad = squad(helper);
		squad.guards().forEach(guard -> guard.setStaying(true));
		squad.stranger().setStaying(true);
		helper.runAfterDelay(5, () -> {
			GuardTestSupport.runCommand(squad.owner(), "guards say follow me");
			for (GuardEntity guard : squad.guards()) {
				helper.assertTrue(guard.hasFollowOverride() && !guard.isStaying(), "owned guard not following");
			}
			helper.assertTrue(squad.stranger().isStaying() && !squad.stranger().hasFollowOverride(), "someone else's guard obeyed");
			helper.assertFalse(CommanderService.activeOrders(helper.getLevel().getServer(), squad.owner().getUUID()).isEmpty(),
					"follow order not recorded");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void sayHold(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			GuardTestSupport.runCommand(squad.owner(), "guards say hold the line");
			squad.guards().forEach(guard -> helper.assertTrue(guard.isHoldingPosition(), "owned guard not holding"));
			helper.assertFalse(squad.stranger().isHoldingPosition(), "someone else's guard obeyed");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void sayStay(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			GuardTestSupport.runCommand(squad.owner(), "guards say stay here");
			squad.guards().forEach(guard -> helper.assertTrue(guard.isStaying(), "owned guard not staying"));
			helper.assertFalse(squad.stranger().isStaying(), "someone else's guard obeyed");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void sayGroupScope(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		List<GuardEntity> alpha = List.of(
				owned(helper, owner, CENTER - 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 0),
				owned(helper, owner, CENTER - 3.5, CENTER + 2.5, GuardRole.BOWMAN, 0));
		List<GuardEntity> beta = List.of(
				owned(helper, owner, CENTER + 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 0),
				owned(helper, owner, CENTER + 3.5, CENTER + 2.5, GuardRole.BOWMAN, 0));
		alpha.forEach(guard -> group(guard, 0, "Alpha"));
		beta.forEach(guard -> group(guard, 1, "Beta"));
		helper.runAfterDelay(5, () -> {
			GuardTestSupport.runCommand(owner, "guards say alpha hold the line");
			alpha.forEach(guard -> helper.assertTrue(guard.isHoldingPosition(), "alpha guard not holding"));
			beta.forEach(guard -> helper.assertFalse(guard.isHoldingPosition(), "beta guard holding"));
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 260)
	public void sayCommanderComeHere(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 12.5, 1, CENTER + 0.5);
		GuardEntity commander = owned(helper, owner, CENTER - 4.5, CENTER + 0.5, GuardRole.SWORDSMAN, 800);
		GuardEntity soldierA = owned(helper, owner, CENTER - 4.5, CENTER + 3.5, GuardRole.SWORDSMAN, 0);
		GuardEntity soldierB = owned(helper, owner, CENTER - 4.5, CENTER - 2.5, GuardRole.BOWMAN, 0);
		List<GuardEntity> all = List.of(commander, soldierA, soldierB);
		all.forEach(guard -> guard.setStaying(true));
		Vec3[] start = new Vec3[2];
		helper.runAfterDelay(10, () -> {
			start[0] = soldierA.position();
			start[1] = soldierB.position();
			GuardTestSupport.runCommand(owner, "guards say commander come here");
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(start[0] != null, "waiting for the order");
			helper.assertTrue(commander.distanceTo(owner) <= 3.0F, "commander is " + commander.distanceTo(owner) + " blocks from the player");
			helper.assertTrue(soldierA.position().distanceTo(start[0]) < 1.5D && soldierB.position().distanceTo(start[1]) < 1.5D,
					"other guards moved");
			helper.assertTrue(calloutContains(owner, "on my way"), "no acknowledgement: " + CommanderCallouts.recent(owner.getUUID()));
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 120)
	public void sayAttackMyTarget(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER - 10.5, 1, CENTER + 0.5);
		Husk husk = GuardTestSupport.noSunBurn(helper.spawn(EntityType.HUSK, new Vec3(CENTER + 14.5, 1, CENTER + 0.5)));
		husk.setNoAi(true);
		List<GuardEntity> guards = List.of(
				owned(helper, owner, CENTER - 14.5, CENTER + 3.5, GuardRole.SWORDSMAN, 0),
				owned(helper, owner, CENTER - 14.5, CENTER - 2.5, GuardRole.SWORDSMAN, 0));
		guards.forEach(guard -> guard.setStaying(true));
		helper.runAfterDelay(10, () -> {
			owner.lookAt(EntityAnchorArgument.Anchor.EYES, husk.getEyePosition());
			GuardTestSupport.runCommand(owner, "guards say attack my target");
		});
		helper.succeedWhen(() -> {
			for (GuardEntity guard : guards) {
				helper.assertTrue(guard.getTarget() == husk || husk.getUUID().equals(guard.getMainTargetUuid()),
						"guard not on the husk, target=" + guard.getTarget());
			}
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 400)
	public void sayFormLine(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER - 8.5, 1, CENTER + 0.5);
		owner.setYRot(-90.0F); // facing east (+X)
		List<GuardEntity> swords = List.of(
				owned(helper, owner, CENTER - 12.5, CENTER + 3.5, GuardRole.SWORDSMAN, 0),
				owned(helper, owner, CENTER - 12.5, CENTER - 3.5, GuardRole.SWORDSMAN, 0));
		List<GuardEntity> bows = List.of(
				owned(helper, owner, CENTER - 11.5, CENTER + 5.5, GuardRole.BOWMAN, 0),
				owned(helper, owner, CENTER - 11.5, CENTER - 5.5, GuardRole.BOWMAN, 0));
		helper.runAfterDelay(10, () -> GuardTestSupport.runCommand(owner, "guards say form a line"));
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getTick() > 20, "waiting");
			double ownerX = owner.getX();
			for (GuardEntity guard : swords) {
				double ahead = guard.getX() - ownerX;
				helper.assertTrue(Math.abs(ahead - 5.0D) <= 0.8D, "swordsman " + ahead + " blocks ahead, expected 5");
			}
			for (GuardEntity guard : bows) {
				double ahead = guard.getX() - ownerX;
				helper.assertTrue(Math.abs(ahead - 3.0D) <= 0.8D, "bowman " + ahead + " blocks ahead, expected 3");
			}
			List<GuardEntity> all = new ArrayList<>(swords);
			all.addAll(bows);
			for (int i = 0; i < all.size(); i++) {
				for (int j = i + 1; j < all.size(); j++) {
					helper.assertTrue(all.get(i).distanceTo(all.get(j)) >= 1.2F, "two guards share a post");
				}
			}
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 1400)
	public void voiceOrderExpires(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 6.5, 1, CENTER + 0.5);
		GuardEntity commander = owned(helper, owner, CENTER - 6.5, CENTER + 0.5, GuardRole.SWORDSMAN, 500);
		owned(helper, owner, CENTER - 6.5, CENTER + 3.5, GuardRole.SWORDSMAN, 0);
		var server = helper.getLevel().getServer();
		helper.runAfterDelay(10, () -> {
			GuardTestSupport.runCommand(owner, "guards say commander come here");
			helper.assertFalse(CommanderService.activeOrders(server, owner.getUUID()).isEmpty(), "come-here order not stored");
		});
		helper.runAfterDelay(600, () -> helper.assertFalse(CommanderService.activeOrders(server, owner.getUUID()).isEmpty(),
				"order expired too early"));
		helper.runAfterDelay(10 + 1200 + 45, () -> {
			helper.assertTrue(CommanderService.activeOrders(server, owner.getUUID()).isEmpty(), "order still active after 60 s");
			helper.assertTrue(commander.getRallyPoint().isEmpty(), "commander still reporting after the order expired");
			helper.succeed();
		});
	}

	/** No Simple Voice Chat in the test server: voice status says so and text orders still work. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void noSvcStillWorks(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(com.guardvillagers.voice.VoiceRuntime.status(squad.owner().getUUID())
					== com.guardvillagers.voice.VoicePipeline.Status.SVC_NOT_INSTALLED, "voice status should report SVC missing");
			helper.assertTrue(GuardTestSupport.runCommand(squad.owner(), "guards voice status") == 0, "status command failed");
			GuardTestSupport.runCommand(squad.owner(), "guards say hold");
			squad.guards().forEach(guard -> helper.assertTrue(guard.isHoldingPosition(), "text order ignored without SVC"));
			helper.succeed();
		});
	}

	// ---- order lifecycle ----

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void typedOrderEndsRetreatSuspension(GameTestHelper helper) {
		Squad squad = squad(helper);
		helper.runAfterDelay(5, () -> {
			GuardTestSupport.runCommand(squad.owner(), "guards say fall back");
			squad.guards().forEach(guard -> helper.assertTrue(guard.isCombatSuspended(), "retreat did not suspend combat"));
			// The typed command replaces the voice retreat without running its undo.
			GuardTestSupport.runCommand(squad.owner(), "guards follow");
		});
		helper.runAfterDelay(10, () -> {
			squad.guards().forEach(guard -> helper.assertFalse(guard.isCombatSuspended(),
					"guard still will not fight after its retreat was replaced"));
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 300)
	public void retreatSuspensionIsCapped(GameTestHelper helper) {
		Squad squad = squad(helper);
		var server = helper.getLevel().getServer();
		helper.runAfterDelay(5, () -> GuardTestSupport.runCommand(squad.owner(), "guards say fall back"));
		helper.runAfterDelay(5 + com.guardvillagers.command.order.OrderExecutor.RETREAT_SUSPEND_TICKS + 5, () -> {
			helper.assertFalse(CommanderService.activeOrders(server, squad.owner().getUUID()).isEmpty(),
					"setup: the retreat order itself lasts longer than the suspension");
			squad.guards().forEach(guard -> helper.assertFalse(guard.isCombatSuspended(), "suspension outlived its cap"));
			helper.succeed();
		});
	}

	/** Owner logs out mid-retreat, comes back and types an order: nothing stays suspended or pinned. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void relogThenTypedOrderClearsSavedOrders(GameTestHelper helper) {
		Squad squad = squad(helper);
		var server = helper.getLevel().getServer();
		UUID owner = squad.owner().getUUID();
		helper.runAfterDelay(5, () -> {
			GuardTestSupport.runCommand(squad.owner(), "guards say fall back");
			CommanderService.forget(server, owner);
			squad.guards().forEach(guard -> helper.assertFalse(guard.isCombatSuspended(), "logout left guards suspended"));
			helper.assertFalse(GuardHierarchy.state(server).peek(owner).orders().isEmpty(), "setup: retreat not saved");
			GuardTestSupport.runCommand(squad.owner(), "guards stay");
			helper.assertTrue(GuardHierarchy.state(server).peek(owner).orders().isEmpty(),
					"typed order after a relog left the saved voice orders in place");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void typedGroupOrderClearsEveryoneOrder(GameTestHelper helper) {
		Squad squad = squad(helper);
		squad.guards().forEach(guard -> group(guard, 0, "Alpha"));
		var server = helper.getLevel().getServer();
		helper.runAfterDelay(5, () -> {
			GuardTestSupport.runCommand(squad.owner(), "guards say hold");
			GuardTestSupport.runCommand(squad.owner(), "guards follow Alpha");
			helper.assertTrue(CommanderService.activeOrders(server, squad.owner().getUUID()).isEmpty(),
					"the everyone-hold still covers Alpha after a typed Alpha order");
			helper.succeed();
		});
	}

	/** An order that reaches nobody must not wipe the standing order it would have replaced. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void unreachableOrderKeepsStandingOrder(GameTestHelper helper) {
		Squad squad = squad(helper);
		var server = helper.getLevel().getServer();
		helper.runAfterDelay(5, () -> {
			GuardTestSupport.runCommand(squad.owner(), "guards say hold");
			squad.owner().setXRot(-90.0F); // looking at the sky: no target
			GuardTestSupport.runCommand(squad.owner(), "guards say attack my target");
			var active = CommanderService.activeOrders(server, squad.owner().getUUID());
			helper.assertTrue(active.size() == 1 && active.getFirst().order().action() == com.guardvillagers.command.order.Action.HOLD,
					"hold order lost to an attack with no target: " + active);
			squad.guards().forEach(guard -> helper.assertTrue(guard.isHoldingPosition(), "guard stopped holding"));
			helper.assertTrue(calloutContains(squad.owner(), "don't see a target"), "no refusal: " + CommanderCallouts.recent(squad.owner().getUUID()));
			helper.succeed();
		});
	}

	/** An expiring "everyone form a line" must not reset a group that has its own newer formation order. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void expiringEveryoneOrderSparesNewerGroupOrder(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity blue = owned(helper, owner, CENTER - 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 0);
		GuardEntity red = owned(helper, owner, CENTER + 3.5, CENTER + 0.5, GuardRole.SWORDSMAN, 0);
		group(blue, 0, "Blue");
		group(red, 1, "Red");
		helper.runAfterDelay(5, () -> {
			blue.setFormationType(com.guardvillagers.entity.FormationType.SQUARE);
			red.setFormationType(com.guardvillagers.entity.FormationType.LINE);
			var everyone = new com.guardvillagers.command.order.ActiveOrder(1L, com.guardvillagers.command.order.Order.form(
					com.guardvillagers.command.order.Scope.ALL, com.guardvillagers.entity.FormationType.LINE),
					com.guardvillagers.command.order.OrderSource.VOICE, 0L, 10L);
			var blueSquare = new com.guardvillagers.command.order.ActiveOrder(2L, com.guardvillagers.command.order.Order.form(
					com.guardvillagers.command.order.Scope.group("Blue"), com.guardvillagers.entity.FormationType.SQUARE),
					com.guardvillagers.command.order.OrderSource.VOICE, 5L, 5_000L);
			com.guardvillagers.command.order.OrderExecutor.undo(everyone, List.of(blue, red), null, true, List.of(blueSquare));
			helper.assertTrue(blue.getFormationType() == com.guardvillagers.entity.FormationType.SQUARE, "Blue's square was reset");
			helper.assertTrue(red.getFormationType() == com.guardvillagers.entity.FormationType.FOLLOW, "Red's line was not undone");
			helper.succeed();
		});
	}

	/** Owner leaves mid-raid: what the raid response changed goes back, what the player changed since stays. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void raidBehaviorsRestoredWhenOwnerLeaves(GameTestHelper helper) {
		Squad squad = squad(helper);
		var server = helper.getLevel().getServer();
		UUID owner = squad.owner().getUUID();
		GuardEntity raided = squad.guards().get(0);
		GuardEntity retasked = squad.guards().get(1);
		helper.runAfterDelay(5, () -> {
			var command = GuardHierarchy.state(server).getOrCreate(owner);
			raided.setBehavior(com.guardvillagers.entity.GuardBehavior.OFFENSIVE);
			command.recordRaidBehavior(raided.getUUID(), com.guardvillagers.entity.GuardBehavior.PERIMETER,
					com.guardvillagers.entity.GuardBehavior.OFFENSIVE);
			// The raid set DEFENSIVE, then the player picked CROWD_CONTROL.
			retasked.setBehavior(com.guardvillagers.entity.GuardBehavior.CROWD_CONTROL);
			command.recordRaidBehavior(retasked.getUUID(), com.guardvillagers.entity.GuardBehavior.PERIMETER,
					com.guardvillagers.entity.GuardBehavior.DEFENSIVE);
			CommanderService.forget(server, owner);
			helper.assertTrue(raided.getBehavior() == com.guardvillagers.entity.GuardBehavior.PERIMETER,
					"raid behavior not restored: " + raided.getBehavior());
			helper.assertTrue(retasked.getBehavior() == com.guardvillagers.entity.GuardBehavior.CROWD_CONTROL,
					"player's own change was overwritten: " + retasked.getBehavior());
			helper.assertTrue(GuardHierarchy.state(server).peek(owner).raidBehaviors().isEmpty(), "entries not cleared");
			helper.succeed();
		});
	}

	// ---- commander intuition ----

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 200)
	public void autoAssignsArchersToThreat(GameTestHelper helper) {
		walledFloor(helper);
		// Owner in the middle of the arena: the commander's 24-block owner scan then stays inside this test.
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER - 0.5, 1, CENTER + 0.5);
		List<GuardEntity> guards = List.of(
				owned(helper, owner, CENTER - 1.5, CENTER + 1.5, GuardRole.BOWMAN, 0),
				owned(helper, owner, CENTER - 1.5, CENTER - 0.5, GuardRole.BOWMAN, 0),
				owned(helper, owner, CENTER - 2.5, CENTER + 2.5, GuardRole.SWORDSMAN, 300),
				owned(helper, owner, CENTER - 2.5, CENTER - 1.5, GuardRole.SWORDSMAN, 0));
		Zombie[] zombie = new Zombie[1];
		int[] spawnedAt = {-1};
		// Owner idle; once the squad has gathered, a zombie appears 23 blocks east: beyond every guard's own 20-block
		// scan, inside the commander's 24-block owner box and leash.
		helper.runAfterDelay(60, () -> {
			zombie[0] = statue(helper, CENTER + 22.5, CENTER + 0.5);
			spawnedAt[0] = (int) helper.getTick();
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(zombie[0] != null, "waiting for the zombie");
			boolean targeted = false;
			for (GuardEntity guard : guards) {
				targeted |= zombie[0].getUUID().equals(guard.getMainTargetUuid()) || !zombie[0].isAlive();
			}
			helper.assertTrue(targeted, "no guard sent at the zombie");
			helper.assertTrue(helper.getTick() - spawnedAt[0] <= 60, "took longer than 60 ticks");
			helper.assertTrue(calloutContains(owner, "east"), "callout does not mention east: " + CommanderCallouts.recent(owner.getUUID()));
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 400)
	public void woundedGuardPulledBack(GameTestHelper helper) {
		walledFloor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER - 0.5, 1, CENTER + 0.5);
		GuardEntity guard = owned(helper, owner, CENTER - 1.5, CENTER + 1.5, GuardRole.BOWMAN, 0);
		guard.setHealth(guard.getMaxHealth() * 0.2F);
		UUID[] zombie = new UUID[1];
		helper.runAfterDelay(40, () -> zombie[0] = statue(helper, CENTER + 22.5, CENTER + 0.5).getUUID());
		boolean[] healed = {false};
		helper.onEachTick(() -> {
			if (zombie[0] == null) {
				return;
			}
			boolean assigned = zombie[0].equals(CommanderService.lastAssignments(owner.getUUID()).get(guard.getUUID()));
			if (!healed[0]) {
				helper.assertTrue(guard.getHealth() < guard.getMaxHealth() * 0.5F, "setup: guard healed by itself");
				if (assigned) {
					helper.fail("wounded guard was sent at the zombie at " + guard.getHealth() + " hp");
				}
				if (helper.getTick() >= 160) {
					helper.assertTrue(CommanderService.isWounded(owner.getUUID(), guard.getUUID()), "guard not marked wounded");
					guard.setHealth(guard.getMaxHealth());
					healed[0] = true;
				}
			} else if (assigned) {
				helper.succeed();
			}
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 300)
	public void ordersOutrankCommander(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER - 4.5, 1, CENTER + 0.5);
		List<GuardEntity> guards = List.of(
				owned(helper, owner, CENTER - 6.5, CENTER + 1.5, GuardRole.SWORDSMAN, 0),
				owned(helper, owner, CENTER - 6.5, CENTER - 0.5, GuardRole.SWORDSMAN, 0));
		Vec3[] start = new Vec3[2];
		helper.runAfterDelay(20, () -> {
			GuardTestSupport.runCommand(owner, "guards say hold");
			start[0] = guards.get(0).position();
			start[1] = guards.get(1).position();
			statue(helper, CENTER + 8.5, CENTER + 0.5);
		});
		helper.onEachTick(() -> {
			if (start[0] == null) {
				return;
			}
			for (int i = 0; i < 2; i++) {
				if (guards.get(i).position().distanceTo(start[i]) > 1.0D) {
					helper.fail("held guard moved toward the threat");
				}
				if (CommanderService.lastAssignments(owner.getUUID()).containsKey(guards.get(i).getUUID())) {
					helper.fail("commander assigned a held guard");
				}
			}
		});
		helper.runAfterDelay(260, helper::succeed);
	}

	/** 16 guards and 16 zombies: the commander layer stays far below a millisecond per tick. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 500)
	public void commanderPerfSmoke(GameTestHelper helper) {
		walledFloor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		for (int i = 0; i < 16; i++) {
			owned(helper, owner, CENTER - 4.5 + (i % 4) * 2, CENTER - 4.5 + (i / 4) * 2,
					i % 2 == 0 ? GuardRole.SWORDSMAN : GuardRole.BOWMAN, i * 40);
		}
		for (int i = 0; i < 16; i++) {
			double angle = i * Math.PI / 8.0D;
			statue(helper, CENTER + 0.5 + Math.cos(angle) * 14, CENTER + 0.5 + Math.sin(angle) * 14);
		}
		helper.runAfterDelay(20, CommanderService::resetTiming);
		helper.runAfterDelay(420, () -> {
			double meanMs = CommanderService.meanNanosPerTick() / 1.0e6D;
			GuardVillagersMod.LOGGER.info("[gametest] commander mean {} ms/tick", String.format(java.util.Locale.ROOT, "%.4f", meanMs));
			// Covers every owner in every concurrently running test, so the bound is generous.
			helper.assertTrue(meanMs < 2.0D, "commander layer took " + meanMs + " ms per tick");
			helper.succeed();
		});
	}
}
