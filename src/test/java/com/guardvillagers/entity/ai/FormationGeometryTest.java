package com.guardvillagers.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FormationGeometryTest {
	private static final double FIRST = GuardMovementSlotResolver.DEFAULT_FIRST_RADIUS;
	private static final double STEP = GuardMovementSlotResolver.DEFAULT_RING_STEP;
	private static final double ARC = GuardMovementSlotResolver.DEFAULT_ARC_SPACING;

	@Test
	void slotsForLargeGroupsNeverOverlap() {
		for (int count : new int[] {1, 8, 32, 64, 128, 300, 1000}) {
			double[][] slots = new double[count][2];
			for (int i = 0; i < count; i++) {
				FormationGeometry.slotOffset(i, false, FIRST, STEP, ARC, 0.7D, slots[i]);
			}
			double minDistance = Double.MAX_VALUE;
			for (int i = 0; i < count; i++) {
				for (int j = i + 1; j < count; j++) {
					double dx = slots[i][0] - slots[j][0];
					double dz = slots[i][1] - slots[j][1];
					minDistance = Math.min(minDistance, Math.sqrt(dx * dx + dz * dz));
				}
			}
			if (count > 1) {
				// Guards are 0.6 wide; 1.2 leaves a clear body width between neighbours.
				assertTrue(minDistance >= 1.2D, count + " guards, min spacing " + minDistance);
			}
		}
	}

	@Test
	void sixtyFourGuardsFitWithinEightBlocks() {
		double[] out = new double[2];
		FormationGeometry.slotOffset(63, false, FIRST, STEP, ARC, 0.0D, out);
		assertTrue(Math.hypot(out[0], out[1]) <= 8.0D);
	}

	@Test
	void firstSlotIsOnFirstRingOrCenter() {
		double[] out = new double[2];
		FormationGeometry.slotOffset(0, true, FIRST, STEP, ARC, 0.0D, out);
		assertEquals(0.0D, Math.hypot(out[0], out[1]), 1.0E-9D);
		FormationGeometry.slotOffset(0, false, FIRST, STEP, ARC, 0.0D, out);
		assertEquals(FIRST, Math.hypot(out[0], out[1]), 1.0E-9D);
	}
}
