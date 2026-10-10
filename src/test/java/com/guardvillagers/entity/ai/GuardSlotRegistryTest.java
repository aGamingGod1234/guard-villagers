package com.guardvillagers.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuardSlotRegistryTest {
	private static List<UUID> ids(int count) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(new UUID(0L, i));
		}
		return ids;
	}

	@Test
	void sixtyFourGuardsGetDenseUniqueIndices() {
		GuardSlotRegistry<String> registry = new GuardSlotRegistry<>(60, 16);
		List<UUID> guards = ids(64);
		for (UUID guard : guards) {
			registry.indexOf("owner", guard, 0L, 0L);
		}
		Set<Integer> seen = new HashSet<>();
		for (UUID guard : guards) {
			seen.add(registry.indexOf("owner", guard, 0L, 1L));
		}
		assertEquals(64, seen.size());
		for (int i = 0; i < 64; i++) {
			assertTrue(seen.contains(i));
		}
	}

	@Test
	void sortKeyKeepsGroupsContiguous() {
		GuardSlotRegistry<String> registry = new GuardSlotRegistry<>(60, 16);
		UUID a = new UUID(0L, 9L);
		UUID b = new UUID(0L, 1L);
		registry.indexOf("k", a, 1L, 0L);
		registry.indexOf("k", b, 2L, 0L);
		assertEquals(0, registry.indexOf("k", a, 1L, 1L));
		assertEquals(1, registry.indexOf("k", b, 2L, 1L));
	}

	@Test
	void membersWhoStopAskingExpireAndIndicesCompact() {
		GuardSlotRegistry<String> registry = new GuardSlotRegistry<>(20, 16);
		List<UUID> guards = ids(3);
		for (UUID guard : guards) {
			registry.indexOf("k", guard, 0L, 0L);
		}
		// Only guard 2 keeps asking.
		assertEquals(0, registry.indexOf("k", guards.get(2), 0L, 50L));
		assertEquals(1, registry.size("k", 50L));
	}

	@Test
	void releaseRemovesImmediately() {
		GuardSlotRegistry<String> registry = new GuardSlotRegistry<>(60, 16);
		List<UUID> guards = ids(2);
		registry.indexOf("k", guards.get(0), 0L, 0L);
		registry.indexOf("k", guards.get(1), 0L, 0L);
		registry.release("k", guards.get(0));
		assertEquals(0, registry.indexOf("k", guards.get(1), 0L, 1L));
	}

	@Test
	void rosterCountIsBounded() {
		GuardSlotRegistry<Integer> registry = new GuardSlotRegistry<>(1000, 8);
		UUID guard = new UUID(1L, 1L);
		for (int anchor = 0; anchor < 100; anchor++) {
			registry.indexOf(anchor, guard, 0L, anchor);
		}
		assertTrue(registry.rosterCount() <= 8);
	}

	@Test
	void unusedRostersAreSwept() {
		GuardSlotRegistry<Integer> registry = new GuardSlotRegistry<>(20, 1000);
		UUID guard = new UUID(1L, 1L);
		for (int anchor = 0; anchor < 50; anchor++) {
			registry.indexOf(anchor, guard, 0L, 0L);
		}
		registry.indexOf(-1, guard, 0L, 100L);
		assertEquals(1, registry.rosterCount());
	}
}
