package com.guardvillagers.entity.ai;

/**
 * Concentric-ring slot layout. Ring {@code r} sits at {@code firstRadius + r * ringStep} and holds
 * as many slots as fit with at least {@code arcSpacing} between neighbours, so slot positions never
 * overlap no matter how many guards share the anchor.
 */
public final class FormationGeometry {
	private FormationGeometry() {
	}

	public static int ringCapacity(int ring, double firstRadius, double ringStep, double arcSpacing) {
		double radius = firstRadius + ring * ringStep;
		return Math.max(1, (int) Math.floor(2.0D * Math.PI * radius / arcSpacing));
	}

	/**
	 * Writes the (x, z) offset of slot {@code index} into {@code out}.
	 *
	 * @param allowCenter slot 0 is the anchor itself
	 * @param rotation    rotation of the whole layout in radians
	 */
	public static void slotOffset(int index, boolean allowCenter, double firstRadius, double ringStep,
			double arcSpacing, double rotation, double[] out) {
		int remaining = Math.max(0, index);
		if (allowCenter) {
			if (remaining == 0) {
				out[0] = 0.0D;
				out[1] = 0.0D;
				return;
			}
			remaining--;
		}
		int ring = 0;
		int capacity = ringCapacity(ring, firstRadius, ringStep, arcSpacing);
		while (remaining >= capacity) {
			remaining -= capacity;
			ring++;
			capacity = ringCapacity(ring, firstRadius, ringStep, arcSpacing);
		}
		double radius = firstRadius + ring * ringStep;
		// Offset alternate rings by half a slot so rings do not line up into spokes.
		double ringTwist = (ring % 2 == 1) ? Math.PI / capacity : 0.0D;
		double angle = rotation + ringTwist + (2.0D * Math.PI * remaining / capacity);
		out[0] = Math.cos(angle) * radius;
		out[1] = Math.sin(angle) * radius;
	}
}
