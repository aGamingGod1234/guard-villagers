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
}
