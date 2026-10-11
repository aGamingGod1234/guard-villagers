package com.guardvillagers.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class HuntClusterTest {
	@Test
	void expandsTransitiveChainPastInitialRadius() {
		List<Integer> pack = List.of(0, 6, 12, 18, 24, 30, 43);
		assertEquals(List.of(0, 6, 12, 18, 24, 30), HuntCluster.collect(0,
				pack.stream().filter(x -> x <= 24).toList(),
				x -> pack.stream().filter(y -> Math.abs(x - y) <= 6).toList(), Comparator.naturalOrder(), 96));
	}

	@Test
	void cappedBreadthLayerPrefersNearestAndAlwaysKeepsHit() {
		assertEquals(List.of(99, 1, 2), HuntCluster.collect(99, List.of(8, 2, 1, 7),
				x -> List.of(), Comparator.naturalOrder(), 3));
	}

	@Test
	void cyclesAndDuplicateSeedsAreVisitedOnce() {
		AtomicInteger calls = new AtomicInteger();
		assertEquals(List.of(0, 1, 2), HuntCluster.collect(0, List.of(0, 1, 1), x -> {
			calls.incrementAndGet();
			return List.of(0, 1, 2);
		}, Comparator.naturalOrder(), 96));
		assertEquals(3, calls.get());
	}

	@Test
	void longChainStopsAtCap() {
		AtomicInteger calls = new AtomicInteger();
		var result = HuntCluster.collect(0, List.of(), x -> {
			calls.incrementAndGet();
			return List.of(x + 1);
		}, Comparator.naturalOrder(), 96);
		assertEquals(IntStream.range(0, 96).boxed().toList(), result);
		assertTrue(calls.get() <= 96);
	}

	@Test
	void nonpositiveAndSingleCapsDoNotExpand() {
		assertEquals(List.of(), HuntCluster.collect(0, List.of(1), x -> List.of(2), Comparator.naturalOrder(), 0));
		assertEquals(List.of(0), HuntCluster.collect(0, List.of(1), x -> {
			throw new AssertionError("expanded a full hunt");
		}, Comparator.naturalOrder(), 1));
	}
}
