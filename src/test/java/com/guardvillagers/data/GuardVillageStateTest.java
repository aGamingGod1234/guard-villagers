package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

final class GuardVillageStateTest {
	@Test
	void unloadedMembershipSurvivesSaveAndVillageRetirement() {
		GuardVillageState state = new GuardVillageState();
		UUID guard = UUID.randomUUID();
		UUID squad = UUID.randomUUID();
		state.getOrCreate("village", 2, 1L);
		state.recordNaturalGuard(guard, squad);
		state.retireStale(1_000L, Set.of(), 10L);
		assertEquals(0, state.trackedVillageCount());

		var encoded = GuardVillageState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
		GuardVillageState reloaded = GuardVillageState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		assertEquals(1, reloaded.countNaturalGuards(squad));
		reloaded.recordNaturalGuard(guard, squad);
		assertEquals(1, reloaded.countNaturalGuards(squad), "reloading an entity must not count it twice");
		reloaded.removeNaturalGuard(guard);
		assertEquals(0, reloaded.countNaturalGuards(squad), "death or hiring releases the village slot");
	}

	@Test
	void squadChangesMoveMembershipAndUnchangedRefreshDoesNotDirtyState() {
		GuardVillageState state = new GuardVillageState();
		UUID guard = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		state.recordNaturalGuard(guard, first);
		state.setDirty(false);
		state.recordNaturalGuard(guard, first);
		assertFalse(state.isDirty());
		state.recordNaturalGuard(guard, second);
		assertTrue(state.isDirty());
		assertEquals(0, state.countNaturalGuards(first));
		assertEquals(1, state.countNaturalGuards(second));
	}

	@Test
	void legacyVillageDataWithoutMembershipStillLoads() {
		GuardVillageState original = new GuardVillageState();
		original.getOrCreate("village", 3, 7L);
		CompoundTag encoded = (CompoundTag) GuardVillageState.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
		encoded.remove("natural_guards");
		GuardVillageState reloaded = GuardVillageState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		assertEquals(3, reloaded.getMaxGuardCount("village"));
		assertEquals(0, reloaded.countNaturalGuards(UUID.randomUUID()));
	}
}
