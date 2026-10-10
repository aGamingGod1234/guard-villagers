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
}
