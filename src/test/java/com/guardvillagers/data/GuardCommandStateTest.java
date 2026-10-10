package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.guardvillagers.entity.GuardBehavior;
import com.mojang.serialization.JsonOps;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuardCommandStateTest {
	@Test
	void peekNeitherCreatesNorReordersRows() {
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
		assertNull(state.peek(first), "oldest written row is evicted even though it was just read");
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
}
