package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

final class GuardTacticsStateTest {
	@Test
	void addingOwnersPreservesAllSavedConfigurationsAcrossReloads() {
		GuardTacticsState state = new GuardTacticsState();
		for (int i = 0; i < 2_050; i++) {
			GuardTacticsState.PlayerTactics tactics = state.getOrCreate(new UUID(0, i));
			tactics.addGroup();
			tactics.setGroupName(0, "Owner " + i);
			tactics.setRowColumnZone(0, 1, 7);
		}
		GuardTacticsState restored = GuardTacticsState.CODEC.parse(NbtOps.INSTANCE,
				GuardTacticsState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
		assertEquals(2_050, restored.trackedPlayerCount());
		for (int i = 0; i < 2_050; i++) {
			GuardTacticsState.PlayerTactics tactics = restored.getOrDefault(new UUID(0, i));
			assertEquals("Owner " + i, tactics.getGroupName(0));
			assertEquals(7, tactics.getRowColumnZone(0, 1));
		}
	}

	@Test
	void namesStayUniqueAfterRenameAddCycleAndNormalization() {
		GuardTacticsState.PlayerTactics tactics = new GuardTacticsState.PlayerTactics();
		tactics.addGroup();
		assertTrue(tactics.setGroupName(0, "Beta"));
		tactics.addGroup();
		assertEquals("Gamma", tactics.getGroupName(1));
		assertFalse(tactics.setGroupName(1, " beta "));
		assertFalse(tactics.setGroupName(1, " unassigned "));
		String longPrefix = "x".repeat(24);
		assertTrue(tactics.setGroupName(0, longPrefix + " first"));
		assertFalse(tactics.setGroupName(1, longPrefix + " second"));
		tactics.ensureGroupCount(GuardTacticsState.PlayerTactics.MAX_GROUPS);
		for (int row = 0; row < tactics.groupCount(); row++) {
			tactics.cycleGroupName(row);
		}
		Set<String> names = new HashSet<>();
		for (int row = 0; row < tactics.groupCount(); row++) {
			assertTrue(names.add(tactics.getGroupName(row).toLowerCase(Locale.ROOT)));
		}
	}
}
