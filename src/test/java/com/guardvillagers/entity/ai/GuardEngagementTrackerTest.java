package com.guardvillagers.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuardEngagementTrackerTest {
	private static final UUID TARGET = new UUID(9L, 9L);

	@Test
	void rankIsNumberOfCloserEngagers() {
		GuardEngagementTracker tracker = new GuardEngagementTracker(30, 64);
		UUID near = new UUID(0L, 1L);
		UUID mid = new UUID(0L, 2L);
		UUID far = new UUID(0L, 3L);
		tracker.report(TARGET, far, 100.0D, 0L);
		tracker.report(TARGET, near, 1.0D, 0L);
		assertEquals(1, tracker.report(TARGET, mid, 10.0D, 0L));
		assertEquals(0, tracker.report(TARGET, near, 1.0D, 1L));
		assertEquals(2, tracker.report(TARGET, far, 100.0D, 1L));
		assertEquals(2, tracker.count(TARGET, near, 1L));
	}

	@Test
	void ineligibleEngagersDoNotConsumeFrontlineSlots() {
		GuardEngagementTracker tracker = new GuardEngagementTracker(30, 64);
		UUID mobile = new UUID(0L, 9L);
		for (int i = 1; i <= 5; i++) {
			tracker.report(TARGET, new UUID(0L, i), 4.0D, false, 0L);
		}
		assertEquals(0, tracker.report(TARGET, mobile, 12.25D, true, 0L));
		assertEquals(5, tracker.count(TARGET, mobile, 0L));
		tracker.report(TARGET, new UUID(0L, 1L), 1.0D, true, 1L);
		assertEquals(1, tracker.report(TARGET, mobile, 12.25D, true, 1L));
		tracker.report(TARGET, new UUID(0L, 1L), 4.0D, false, 2L);
		assertEquals(0, tracker.report(TARGET, mobile, 12.25D, true, 2L));
	}

	@Test
	void staleEngagementsExpire() {
		GuardEngagementTracker tracker = new GuardEngagementTracker(30, 64);
		tracker.report(TARGET, new UUID(0L, 1L), 1.0D, 0L);
		tracker.report(TARGET, new UUID(0L, 2L), 2.0D, 0L);
		assertEquals(0, tracker.count(TARGET, null, 100L));
		assertEquals(0, tracker.report(TARGET, new UUID(0L, 3L), 50.0D, 100L));
	}

	@Test
	void releaseAndCapacityBoundTheMap() {
		GuardEngagementTracker tracker = new GuardEngagementTracker(1000, 16);
		UUID guard = new UUID(0L, 1L);
		for (int i = 0; i < 200; i++) {
			tracker.report(new UUID(1L, i), guard, 1.0D, i);
		}
		assertTrue(tracker.targetCount() <= 16);
		tracker.release(new UUID(1L, 199L), guard);
		assertEquals(0, tracker.count(new UUID(1L, 199L), null, 199L));
	}

	@Test
	void cachedCountTracksReportsReleasesExpiryAndClockReset() {
		GuardEngagementTracker tracker = new GuardEngagementTracker(3, 64);
		UUID a = new UUID(0, 1);
		UUID b = new UUID(0, 2);
		tracker.report(TARGET, a, 1, 0);
		assertEquals(1, tracker.count(TARGET, null, 0));
		tracker.report(TARGET, b, 2, 0);
		assertEquals(2, tracker.count(TARGET, null, 0));
		tracker.release(TARGET, a);
		assertEquals(1, tracker.count(TARGET, null, 0));
		assertEquals(1, tracker.count(TARGET, null, 3));
		assertEquals(0, tracker.count(TARGET, null, 4));
		tracker.report(TARGET, b, 2, 4);
		assertEquals(1, tracker.count(TARGET, null, 4));
		assertEquals(0, tracker.count(TARGET, b, 4));
		assertEquals(0, tracker.count(TARGET, null, 0));
	}

	@Test
	void cachedCountsMatchNaiveRosterUnderInterleavedOperations() {
		GuardEngagementTracker tracker = new GuardEngagementTracker(30, 64);
		java.util.Map<UUID, Long> naive = new java.util.HashMap<>();
		java.util.Random random = new java.util.Random(42);
		for (long tick = 0; tick < 300; tick++) {
			for (int operation = 0; operation < 100; operation++) {
				UUID guard = new UUID(0, random.nextInt(512));
				if (random.nextInt(4) == 0) {
					tracker.release(TARGET, guard);
					naive.remove(guard);
				} else {
					tracker.report(TARGET, guard, random.nextDouble(), tick);
					naive.put(guard, tick);
				}
				int expected = 0;
				for (var entry : naive.entrySet()) {
					if (!entry.getKey().equals(guard) && tick - entry.getValue() <= 30) expected++;
				}
				assertEquals(expected, tracker.count(TARGET, guard, tick));
			}
		}
	}
}
