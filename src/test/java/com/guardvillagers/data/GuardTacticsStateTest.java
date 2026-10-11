package com.guardvillagers.data;

import com.guardvillagers.GuardSecurityLimits;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardTacticsStateTest {
	@Test
	void reachingOwnerLimitPreservesExistingSettingsAndRejectsCreation() {
		var state = new GuardTacticsState();
		UUID first = new UUID(0, 0);
		for (int i = 0; i < GuardSecurityLimits.MAX_TACTICS_PLAYERS; i++) {
			state.getOrCreate(new UUID(0, i)).addGroup();
		}
		state.getOrCreate(first).setGroupName(0, "Veterans");
		state.getOrCreate(first).setZoneColor(3, 4, 7);
		UUID newcomer = UUID.randomUUID();
		assertFalse(state.canCreate(newcomer));
		assertThrows(IllegalStateException.class, () -> state.getOrCreate(newcomer));
		assertTrue(state.canCreate(first));
		var loaded = GuardTacticsState.CODEC.parse(JsonOps.INSTANCE,
				GuardTacticsState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()).getOrThrow();
		assertEquals(GuardSecurityLimits.MAX_TACTICS_PLAYERS, loaded.trackedPlayerCount());
		assertEquals("Veterans", loaded.getOrDefault(first).getGroupName(0));
		assertEquals(7, loaded.getOrDefault(first).getZoneColor(3, 4));
		assertEquals(0, loaded.getOrDefault(newcomer).groupCount());
	}

	@Test
	void loadingMoreThanCreationLimitNeverDiscardsSavedOwners() {
		JsonObject players = new JsonObject();
		for (int i = 0; i <= GuardSecurityLimits.MAX_TACTICS_PLAYERS; i++) {
			players.add(new UUID(0, i).toString(), JsonParser.parseString("{\"groups\":[\"Veterans\"]}"));
		}
		JsonObject saved = new JsonObject();
		saved.add("players", players);
		var state = GuardTacticsState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
		var reloaded = GuardTacticsState.CODEC.parse(JsonOps.INSTANCE,
				GuardTacticsState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()).getOrThrow();
		assertEquals(players.size(), reloaded.trackedPlayerCount());
		for (int i = 0; i < players.size(); i++) {
			assertEquals("Veterans", reloaded.getOrDefault(new UUID(0, i)).getGroupName(0));
		}
	}

	@Test
	void namesStayUniqueAfterRenameAddTruncationAndCycling() {
		var tactics = new GuardTacticsState.PlayerTactics();
		tactics.addGroup();
		assertTrue(tactics.setGroupName(0, "Beta"));
		tactics.addGroup();
		assertFalse(tactics.getGroupName(0).equalsIgnoreCase(tactics.getGroupName(1)));
		assertFalse(tactics.setGroupName(1, "  bEtA  "));
		assertFalse(tactics.setGroupName(1, " Unassigned "));
		assertTrue(tactics.setGroupName(0, "x".repeat(24) + "a"));
		assertFalse(tactics.setGroupName(1, "X".repeat(24) + "b"));
		tactics.ensureGroupCount(64);
		for (int i = 0; i < 64; i++) {
			tactics.cycleGroupName(i);
		}
		assertUnique(tactics);
	}

	@Test
	void legacyCollisionsAreMigratedWithoutChangingRowIdentity() {
		var tactics = GuardTacticsState.PlayerTactics.CODEC.parse(JsonOps.INSTANCE,
				JsonParser.parseString("{\"groups\":[\"Alpha\",\"alpha\",\"Unassigned\",\"Alpha 2\"]}")).getOrThrow();
		assertEquals(4, tactics.groupCount());
		assertEquals("Alpha", tactics.getGroupName(0));
		assertEquals("Alpha 2", tactics.getGroupName(3));
		assertUnique(tactics);
		var copy = tactics.copy();
		for (int row = 0; row < 4; row++) {
			assertEquals(tactics.getGroupName(row), copy.getGroupName(row));
		}
	}

	private static void assertUnique(GuardTacticsState.PlayerTactics tactics) {
		var names = new HashSet<String>();
		names.add("unassigned");
		for (int row = 0; row < tactics.groupCount(); row++) {
			String name = tactics.getGroupName(row);
			assertTrue(name.length() <= 24);
			assertTrue(names.add(name.toLowerCase(Locale.ROOT)), name);
		}
	}
}
