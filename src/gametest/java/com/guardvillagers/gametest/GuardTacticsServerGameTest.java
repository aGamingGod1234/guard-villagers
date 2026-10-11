package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.*;

import com.guardvillagers.GuardTacticsManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.tactics.GuardTacticsMenus;
import com.guardvillagers.tactics.GuardTacticsCommands;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public class GuardTacticsServerGameTest {
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void tacticsActionsArePacedInOrderAndRetainOwnershipChecks(GameTestHelper helper) {
		floor(helper);
		var player = survivalPlayer(helper, CENTER, 1, CENTER);
		var other = survivalPlayer(helper, CENTER + 6, 1, CENTER);
		var owned = ownedGuard(helper, CENTER + 2, 1, CENTER, player);
		var foreign = ownedGuard(helper, CENTER + 4, 1, CENTER, other);
		var state = GuardTacticsManager.getState(helper.getLevel().getServer());
		for (int i = 0; i < 4; i++) {
			player.connection.handleChatCommand(new ServerboundChatCommandPacket("guards count"));
		}
		for (int i = 0; i < 14; i++) {
			helper.assertTrue(GuardTacticsCommands.enqueue(player, "guards groups add"), "add queued");
		}
		helper.assertTrue(GuardTacticsCommands.enqueue(player, "guards groups assignmany 14 " + owned.getUUID() + " " + foreign.getUUID()), "assignment queued");
		helper.assertTrue(GuardTacticsCommands.enqueue(player, "guards groups rename 14 Vanguard"), "rename queued");
		helper.assertFalse(GuardTacticsCommands.enqueue(player, "guards reputation 1"), "unrelated command rejected");
		helper.assertValueEqual(state.getOrDefault(player.getUUID()).groupCount(), 0, "receiving a burst does not execute it immediately");
		helper.runAfterDelay(20, () -> {
			helper.assertTrue(player.connection.isAcceptingMessages(), "ordinary command traffic plus tactics burst does not disconnect the player");
			helper.assertValueEqual(state.getOrDefault(player.getUUID()).groupCount(), 14, "all groups created across server ticks");
			helper.assertValueEqual(owned.getGroupIndex(), 13, "dependent assignment follows group creation");
			helper.assertValueEqual(owned.getGroupName(), "Vanguard", "rename follows assignment");
			helper.assertValueEqual(foreign.getGroupIndex(), -1, "other owner's guard unchanged");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void renamedGroupIsAuthoritativeWhenUnloadedGuardReturns(GameTestHelper helper) {
		floor(helper);
		var player = survivalPlayer(helper, CENTER, 1, CENTER);
		var level = helper.getLevel();
		var loaded = ownedGuard(helper, CENTER + 2, 1, CENTER, player);
		var unloaded = ownedGuard(helper, CENTER + 4, 1, CENTER, player);
		helper.assertValueEqual(runCommand(player, "guards groups add"), 1, "add Alpha");
		for (var guard : new GuardEntity[]{loaded, unloaded}) {
			helper.assertValueEqual(runCommand(player, "guards groups assign " + guard.getUUID() + " 1"), 1, "assign Alpha");
		}
		var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		unloaded.saveWithoutId(output);
		unloaded.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
		helper.assertValueEqual(runCommand(player, "guards groups rename 1 Vanguard"), 1, "rename while unloaded");
		var returned = GuardVillagersMod.GUARD_ENTITY_TYPE.create(level, EntitySpawnReason.LOAD);
		returned.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
		helper.assertTrue(level.addFreshEntity(returned), "reloaded guard added to level");
		helper.assertValueEqual(returned.getGroupName(), "Vanguard", "name reconciled by entity load event");
		helper.assertValueEqual(returned.getGroupIndex(), 0, "row preserved");
		helper.assertValueEqual(runCommand(player, "guards follow Vanguard"), 2, "follow reaches both guards");
		helper.assertTrue(loaded.hasFollowOverride() && returned.hasFollowOverride(), "both guards follow");
		helper.assertValueEqual(runCommand(player, "guards stay Vanguard"), 2, "stay reaches both guards");
		helper.assertTrue(loaded.isStaying() && returned.isStaying(), "both guards stay");
		helper.assertValueEqual(runCommand(player, "guards follow Alpha"), 0, "old name no longer targets guards");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void rosterReportsActualHoldState(GameTestHelper helper) {
		floor(helper);
		var player = survivalPlayer(helper, CENTER, 1, CENTER);
		var guard = ownedGuard(helper, CENTER + 2, 1, CENTER, player);
		helper.assertValueEqual(runCommand(player, "guards hold"), 1, "hold command");
		var holding = GuardTacticsMenus.buildRoster(player);
		var summary = holding.guards().stream().filter(row -> row.guardUuid().equals(guard.getUUID())).findFirst().orElseThrow();
		helper.assertTrue(summary.holding(), "roster includes active hold");
		helper.assertFalse(summary.staying() || summary.following(), "hold is distinct from stay/follow");
		guard.setHoldPosition(false);
		var free = GuardTacticsMenus.buildRoster(player);
		helper.assertFalse(free.guards().getFirst().holding(), "roster clears expired hold");
		helper.assertTrue(holding.contentHash() != free.contentHash(), "hold change triggers roster resend");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void duplicateNamesCannotBroadenGroupOrders(GameTestHelper helper) {
		floor(helper);
		var player = survivalPlayer(helper, CENTER, 1, CENTER);
		var first = ownedGuard(helper, CENTER + 2, 1, CENTER, player);
		var second = ownedGuard(helper, CENTER + 4, 1, CENTER, player);
		helper.assertValueEqual(runCommand(player, "guards groups add"), 1, "add Alpha");
		helper.assertValueEqual(runCommand(player, "guards groups rename 1 Beta"), 1, "rename first row");
		helper.assertValueEqual(runCommand(player, "guards groups add"), 2, "add collision-free second row");
		helper.assertValueEqual(runCommand(player, "guards groups assign " + first.getUUID() + " 1"), 1, "assign first");
		helper.assertValueEqual(runCommand(player, "guards groups assign " + second.getUUID() + " 2"), 1, "assign second");
		helper.assertValueEqual(runCommand(player, "guards groups rename 2 bETA"), 0, "duplicate rename rejected");
		helper.assertValueEqual(runCommand(player, "guards groups rename 2 Unassigned"), 0, "reserved name rejected");
		var tactics = GuardTacticsManager.getState(helper.getLevel().getServer()).getOrDefault(player.getUUID());
		helper.assertFalse(tactics.getGroupName(0).equalsIgnoreCase(tactics.getGroupName(1)), "rows retain unique names");
		helper.assertValueEqual(runCommand(player, "guards follow Beta"), 1, "follow targets exactly first row");
		helper.assertTrue(first.hasFollowOverride(), "first row follows");
		helper.assertFalse(second.hasFollowOverride(), "second row remains independent");
		// Even a stale display-name cache cannot broaden a row-addressed order.
		second.setGroupName(first.getGroupName());
		helper.assertValueEqual(runCommand(player, "guards groups stay 2"), 1, "row order targets only second row");
		helper.assertTrue(second.isStaying(), "second row stays");
		helper.assertTrue(first.hasFollowOverride(), "first row keeps following");
		helper.assertValueEqual(runCommand(player, "guards groups follow 3"), 0, "unknown row rejected");
		var unassigned = ownedGuard(helper, CENTER + 6, 1, CENTER, player);
		helper.assertValueEqual(runCommand(player, "guards groups stay 0"), 1, "explicit unassigned scope");
		helper.assertTrue(unassigned.isStaying(), "unassigned guard stays");
		helper.succeed();
	}
}
