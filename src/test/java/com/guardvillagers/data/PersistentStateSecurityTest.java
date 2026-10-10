package com.guardvillagers.data;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PersistentStateSecurityTest {
	@Test
	void reputationReadsDoNotCreateRowsButMutationsDo() {
		GuardReputationState state = new GuardReputationState();
		UUID playerId = UUID.randomUUID();

		assertEquals(GuardReputationState.DEFAULT_REPUTATION, state.get(playerId));
		assertEquals(0, state.trackedPlayerCount());

		state.add(playerId, 0.1D);
		assertEquals(1, state.trackedPlayerCount());
	}

	@Test
	void upgradeDefaultViewDoesNotCreateRowsOrPersistMutations() {
		GuardUpgradeState state = new GuardUpgradeState();
		UUID playerId = UUID.randomUUID();

		state.getOrDefault(playerId).upgradeArmor();
		assertEquals(0, state.trackedPlayerCount());

		state.getOrCreate(playerId).upgradeArmor();
		assertEquals(1, state.trackedPlayerCount());
		assertEquals(1, state.getOrDefault(playerId).getArmorLevel());
	}

	@Test
	void tacticsDefaultViewDoesNotCreateRows() {
		GuardTacticsState state = new GuardTacticsState();
		UUID playerId = UUID.randomUUID();

		assertEquals(0, state.getOrDefault(playerId).groupCount());
		assertEquals(0, state.trackedPlayerCount());

		state.getOrCreate(playerId).addGroup();
		assertEquals(1, state.trackedPlayerCount());
		assertEquals(1, state.getOrDefault(playerId).groupCount());
	}

	@Test
	void villageStateRetiresStaleUnobservedVillages() {
		GuardVillageState state = new GuardVillageState();

		state.getOrCreate("minecraft:overworld|old", 2, 100L);
		state.getOrCreate("minecraft:overworld|current", 2, 200L);
		state.retireStale(1_000L, Set.of("minecraft:overworld|current"), 500L);

		assertEquals(1, state.trackedVillageCount());
		assertEquals(2, state.getMaxGuardCount("minecraft:overworld|current"));
	}

	@Test
	void groupCountAndNamesAreBounded() {
		GuardTacticsState.PlayerTactics tactics = new GuardTacticsState().getOrCreate(UUID.randomUUID());
		for (int i = 0; i < GuardTacticsState.PlayerTactics.MAX_GROUPS + 10; i++) {
			tactics.addGroup();
		}
		assertEquals(GuardTacticsState.PlayerTactics.MAX_GROUPS, tactics.groupCount());
		assertEquals(-1, tactics.addGroup());

		tactics.setGroupName(Integer.MAX_VALUE, "ignored");
		tactics.setGroupName(-1, "ignored");
		assertEquals(GuardTacticsState.PlayerTactics.MAX_GROUPS, tactics.groupCount());

		tactics.setGroupName(0, "  " + "x".repeat(100) + "  ");
		assertEquals(24, tactics.getGroupName(0).length());
	}

	@Test
	void debugRowsAreDroppedWhenBackToDefaults() {
		GuardDebugState state = new GuardDebugState();
		UUID playerId = UUID.randomUUID();
		state.setEnabled(playerId, true);
		assertEquals(1, state.trackedPlayerCount());
		state.setEnabled(playerId, false);
		assertEquals(0, state.trackedPlayerCount());

		state.setRange(playerId, Double.NaN);
		assertEquals(-1.0D, state.getRange(playerId));
		assertEquals(0, state.trackedPlayerCount());
	}

	@Test
	void reputationIsClampedAndCapped() {
		GuardReputationState state = new GuardReputationState();
		UUID playerId = UUID.randomUUID();
		state.add(playerId, 5.0D);
		assertEquals(1.0D, state.get(playerId));
		state.add(playerId, -9.0D);
		assertEquals(0.0D, state.get(playerId));
		for (int i = 0; i < com.guardvillagers.GuardSecurityLimits.MAX_REPUTATION_PLAYERS + 5; i++) {
			state.set(UUID.randomUUID(), 0.7D);
		}
		assertEquals(com.guardvillagers.GuardSecurityLimits.MAX_REPUTATION_PLAYERS, state.trackedPlayerCount());
	}
}
