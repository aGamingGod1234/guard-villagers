package com.guardvillagers.entity.ai;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EntitySpatialIndexTest {
	@Test
	void matchesExhaustiveQueriesWhileEntitiesMoveResizeAndDisappear() {
		EntitySpatialIndex<Object> index = new EntitySpatialIndex<>();
		IdentityHashMap<Object, double[]> bounds = new IdentityHashMap<>();
		Random random = new Random(73);
		for (int i = 0; i < 512; i++) bounds.put(new Object(), box(random));
		for (int tick = 0; tick < 30; tick++) {
			index.beginRefresh();
			for (var entry : bounds.entrySet()) put(index, entry.getKey(), entry.getValue());
			index.endRefresh();
			for (int step = 0; step < 40; step++) {
				Object moving = bounds.keySet().iterator().next();
				double[] replacement = box(random);
				bounds.put(moving, replacement);
				put(index, moving, replacement);
				double[] query = box(random);
				var actual = new ArrayList<Object>();
				index.query(query[0], query[1], query[2], query[3], query[4], query[5], e -> e != moving, actual);
				var expected = new HashSet<Object>();
				bounds.forEach((e, b) -> {
					if (e != moving && b[0] < query[3] && b[3] > query[0] && b[1] < query[4] && b[4] > query[1]
							&& b[2] < query[5] && b[5] > query[2]) expected.add(e);
				});
				assertEquals(expected, new HashSet<>(actual));
				assertEquals(expected.size(), actual.size(), "no duplicates across bucket boundaries");
				index.remove(moving);
				bounds.remove(moving);
				Object added = new Object();
				double[] addedBox = box(random);
				bounds.put(added, addedBox);
				put(index, added, addedBox);
			}
		}
	}

	@Test
	void handlesNegativeCoordinatesLargeBoundsTouchingFacesAndRefreshRemoval() {
		EntitySpatialIndex<Object> index = new EntitySpatialIndex<>();
		Object a = new Object(), b = new Object();
		index.beginRefresh();
		index.update(a, -100, -20, -100, 100, 20, 100);
		index.update(b, -8, 0, -8, -4, 2, -4);
		index.endRefresh();
		var found = new ArrayList<Object>();
		index.query(99, 0, 99, 101, 2, 101, e -> true, found);
		assertEquals(java.util.List.of(a), found);
		found.clear();
		index.query(-4, 0, -4, 0, 2, 0, e -> e == b, found);
		assertTrue(found.isEmpty(), "touching faces do not overlap");
		index.beginRefresh();
		index.update(b, -8, 0, -8, -4, 2, -4);
		index.endRefresh();
		assertFalse(index.contains(a));
		assertTrue(index.contains(b));
		found.clear();
		index.query(-30000000, -1000, -30000000, 30000000, 1000, 30000000, e -> true, found);
		assertEquals(java.util.List.of(b), found);
	}

	private static double[] box(Random r) {
		double x = r.nextDouble() * 120 - 60, y = r.nextDouble() * 20 - 10, z = r.nextDouble() * 120 - 60;
		return new double[]{x, y, z, x + r.nextDouble() * 15, y + r.nextDouble() * 5, z + r.nextDouble() * 15};
	}

	private static void put(EntitySpatialIndex<Object> index, Object e, double[] b) {
		index.update(e, b[0], b[1], b[2], b[3], b[4], b[5]);
	}
}
