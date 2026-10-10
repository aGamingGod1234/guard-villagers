package com.guardvillagers.command.hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuardSeniorityTest {
	/** The order ElectLeaderGoal used before the shared comparator: level, then XP, then UUID. */
	private static final Comparator<GuardSeniority.Key> LEGACY = Comparator
			.comparingInt(GuardSeniority.Key::level)
			.thenComparingInt(GuardSeniority.Key::experience)
			.thenComparing(GuardSeniority.Key::uuid);

	@Test
	void matchesLegacyLeaderOrderForVillageGuards() {
		Random random = new Random(42);
		List<GuardSeniority.Key> keys = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			int xp = random.nextInt(1300);
			// Village guards were never hired, so every hire tick is 0.
			keys.add(new GuardSeniority.Key(Math.min(10, 1 + xp / 120), xp, 0L, new UUID(random.nextLong(), random.nextLong())));
		}
		List<GuardSeniority.Key> shared = new ArrayList<>(keys);
		List<GuardSeniority.Key> legacy = new ArrayList<>(keys);
		shared.sort(GuardSeniority.KEY_ORDER);
		legacy.sort(LEGACY);
		assertEquals(legacy, shared);
	}

	@Test
	void levelThenExperienceThenLongerServiceThenUuid() {
		UUID low = new UUID(0, 1);
		UUID high = new UUID(0, 2);
		GuardSeniority.Key veteran = new GuardSeniority.Key(3, 300, 100L, low);
		GuardSeniority.Key recruit = new GuardSeniority.Key(3, 300, 5000L, high);
		GuardSeniority.Key stronger = new GuardSeniority.Key(4, 360, 9000L, low);
		GuardSeniority.Key moreXp = new GuardSeniority.Key(3, 301, 9000L, low);
		List<GuardSeniority.Key> keys = new ArrayList<>(List.of(veteran, recruit, stronger, moreXp));
		Collections.shuffle(keys, new Random(7));
		keys.sort(GuardSeniority.KEY_ORDER);
		assertEquals(List.of(recruit, veteran, moreXp, stronger), keys);
		assertTrue(GuardSeniority.KEY_ORDER.compare(new GuardSeniority.Key(1, 0, 0L, high), new GuardSeniority.Key(1, 0, 0L, low)) > 0);
	}
}
