package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.guardvillagers.entity.GuardBehavior;
import com.mojang.serialization.JsonOps;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import com.google.gson.JsonObject;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.ActiveOrder;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OrderSource;
import com.guardvillagers.command.order.Scope;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class GuardCommandStateTest {
	@Test
	void capacityPreservesExplicitChoices() {
		GuardCommandState state = new GuardCommandState();
		UUID first = new UUID(1, 0);
		assertNull(state.peek(first));
		assertEquals(0, state.trackedOwnerCount());
		state.getOrCreate(first).setVoiceConsent(true);
		for (int i = 1; i < GuardCommandState.MAX_OWNERS; i++) {
			state.getOrCreate(new UUID(1, i)).setVoiceConsent(true);
		}
		// Reading the oldest row must not refresh it: only writes count as use.
		assertNotNull(state.peek(first));
		state.getOrCreate(new UUID(2, 0)).setVoiceConsent(true);
		assertEquals(java.util.Optional.of(true), state.peek(first).voiceConsent());
		assertEquals(GuardCommandState.MAX_OWNERS + 1, state.trackedOwnerCount());
	}

	@Test
	void evictionDropsRowsWithoutPlayerChoicesFirst() {
		GuardCommandState state = new GuardCommandState();
		UUID chose = new UUID(3, 0);
		state.getOrCreate(chose).setAutoCommand(false);
		UUID derivedOnly = new UUID(3, 1);
		state.getOrCreate(derivedOnly).setCommander(new UUID(9, 9));
		for (int i = 2; i < GuardCommandState.MAX_OWNERS; i++) {
			state.getOrCreate(new UUID(3, i)).setVoiceConsent(false);
		}
		state.getOrCreate(new UUID(4, 0));
		assertNotNull(state.peek(chose), "the oldest row holds a player choice and survives");
		assertNull(state.peek(derivedOnly), "a row with only election results goes first");
		assertEquals(GuardCommandState.MAX_OWNERS, state.trackedOwnerCount());
	}

	@Test
	void raidBehaviorsKeepTheFirstOriginalAndSurviveSaving() {
		GuardCommandState state = new GuardCommandState();
		UUID owner = new UUID(5, 0);
		UUID guard = new UUID(5, 1);
		GuardCommandState.OwnerCommand command = state.getOrCreate(owner);
		command.recordRaidBehavior(guard, GuardBehavior.PERIMETER, GuardBehavior.DEFENSIVE);
		command.recordRaidBehavior(guard, GuardBehavior.DEFENSIVE, GuardBehavior.OFFENSIVE);
		var json = GuardCommandState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
		GuardCommandState loaded = GuardCommandState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(new GuardCommandState.RaidBehavior(GuardBehavior.PERIMETER, GuardBehavior.OFFENSIVE),
				loaded.peek(owner).raidBehaviors().get(guard));
	}
	@Test
	void recipientsAndPendingUnloadedCleanupSurviveSavingAndCopying() {
		var owner = new GuardCommandState.OwnerCommand();
		UUID guard = new UUID(8, 1);
		var active = new ActiveOrder(1, Order.of(Action.HOLD, Scope.group("Alpha")), OrderSource.VOICE, 10, ActiveOrder.NEVER)
				.withRecipients(Set.of(guard));
		owner.setOrders(List.of(active));
		owner.pinCaptain(2, guard);
		owner.setAutoCommand(false);
		owner.queueCleanup(active);
		owner.queueCleanup(active);
		var json = GuardCommandState.OwnerCommand.CODEC.encodeStart(JsonOps.INSTANCE, owner.copy()).getOrThrow();
		var loaded = GuardCommandState.OwnerCommand.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(List.of(active), loaded.orders());
		assertEquals(java.util.Map.of(2, guard), loaded.pinnedCaptainByGroup());
		assertEquals(Set.of(guard), loaded.pinnedCaptains());
		assertEquals(Optional.of(false), loaded.autoCommandChoice());
		assertEquals(List.of(active), loaded.pendingCleanup());
		loaded.setPendingCleanup(List.of());
		assertEquals(List.of(), loaded.pendingCleanup());
		assertEquals(List.of(active), owner.pendingCleanup());
	}

	@Test
	void legacyOrdersRemainReadableAndUnspecifiedAutoCommandRemainsUnset() {
		var owner = new GuardCommandState.OwnerCommand();
		var legacy = new ActiveOrder(1, Order.of(Action.HOLD, Scope.ALL), OrderSource.VOICE, 10, ActiveOrder.NEVER);
		owner.setOrders(List.of(legacy));
		var json = GuardCommandState.OwnerCommand.CODEC.encodeStart(JsonOps.INSTANCE, owner).getOrThrow();
		var loaded = GuardCommandState.OwnerCommand.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Optional.empty(), loaded.autoCommandChoice());
		assertEquals(List.of(legacy), loaded.orders());
		owner.setAutoCommand(true);
		json = GuardCommandState.OwnerCommand.CODEC.encodeStart(JsonOps.INSTANCE, owner).getOrThrow();
		loaded = GuardCommandState.OwnerCommand.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Optional.of(true), loaded.autoCommandChoice());
		assertEquals(Optional.empty(), GuardCommandState.OwnerCommand.CODEC.parse(JsonOps.INSTANCE, new JsonObject())
				.getOrThrow().autoCommandChoice());
	}

	@Test
	void raidSourceAndManualChoiceSurviveRepeatedAssignmentsAndSaving() {
		var owner = new GuardCommandState.OwnerCommand();
		UUID guard = new UUID(9, 1);
		BlockPos center = new BlockPos(10, 64, 20);
		owner.recordRaidBehavior(guard, GuardBehavior.PERIMETER, GuardBehavior.DEFENSIVE, "minecraft:overworld", center);
		owner.markRaidBehaviorManual(guard);
		owner.recordRaidBehavior(guard, GuardBehavior.DEFENSIVE, GuardBehavior.OFFENSIVE, "minecraft:the_nether", BlockPos.ZERO);
		var json = GuardCommandState.OwnerCommand.CODEC.encodeStart(JsonOps.INSTANCE, owner).getOrThrow();
		var loaded = GuardCommandState.OwnerCommand.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(new GuardCommandState.RaidBehavior(GuardBehavior.PERIMETER, GuardBehavior.OFFENSIVE,
				"minecraft:overworld", center, true), loaded.raidBehaviors().get(guard));
	}

	@Test
	void voiceOptOutAndLongOrderNamesSurviveSaving() {
		GuardCommandState state = new GuardCommandState();
		UUID owner = new UUID(10, 0);
		state.getOrCreate(owner).setVoiceConsent(false);
		for (int i = 1; i <= GuardCommandState.MAX_OWNERS; i++) {
			state.getOrCreate(new UUID(10, i)).setVoiceConsent(true);
		}
		String name = "Long Guard Name ".repeat(8).strip();
		var book = new com.guardvillagers.command.order.OrderBook(() -> 0L);
		book.issue(com.guardvillagers.command.order.Order.of(com.guardvillagers.command.order.Action.FOLLOW,
				com.guardvillagers.command.order.Scope.guard(name)), com.guardvillagers.command.order.OrderSource.VOICE);
		state.getOrCreate(owner).setOrders(book.active());
		var nbt = GuardCommandState.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, state).getOrThrow();
		var loaded = GuardCommandState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt).getOrThrow();
		assertEquals(java.util.Optional.of(false), loaded.peek(owner).voiceConsent());
		assertEquals(name, loaded.peek(owner).orders().getFirst().order().scope().name());
	}

	@Test
	void captainReplacementIsGroupSpecificAndPersistent() {
		var command = new GuardCommandState.OwnerCommand();
		UUID commander = UUID.randomUUID(), oldCaptain = UUID.randomUUID(), replacement = UUID.randomUUID();
		command.setPinnedCommander(commander);
		command.pinCaptain(0, oldCaptain);
		command.pinCaptain(0, replacement);
		var json = GuardCommandState.OwnerCommand.CODEC.encodeStart(JsonOps.INSTANCE, command).getOrThrow();
		var loaded = GuardCommandState.OwnerCommand.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(commander, loaded.pinnedCommander());
		assertEquals(java.util.Set.of(replacement), loaded.pinnedCaptains());
		assertEquals(java.util.Map.of(0, replacement), loaded.pinnedCaptainByGroup());
		loaded.unpinCaptain(replacement);
		assertEquals(commander, loaded.pinnedCommander());
		assertEquals(java.util.Map.of(), loaded.pinnedCaptainByGroup());
	}

}
