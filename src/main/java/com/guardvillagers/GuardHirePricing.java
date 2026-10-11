package com.guardvillagers;

public final class GuardHirePricing {
	public static final int BASE_PRICE = 4;
	public static final int PRICE_PER_LEVEL = 2;
	public static final double MAX_REPUTATION_MULTIPLIER = 1.5D;
	public static final double MIN_REPUTATION_MULTIPLIER = 0.75D;

	private GuardHirePricing() {
	}

	public static int getHirePrice(int level) {
		int normalizedLevel = Math.max(1, level);
		return BASE_PRICE + (normalizedLevel - 1) * PRICE_PER_LEVEL;
	}

	/**
	 * Shop price scaling: reputation 0.00 pays 1.5x, 0.50 pays 1.125x and 1.00 pays 0.75x. Never below one block.
	 */
	public static int applyReputationModifier(int basePrice, double reputation) {
		double normalizedReputation = Double.isFinite(reputation) ? Math.max(0.0D, Math.min(1.0D, reputation)) : 0.0D;
		double modifier = MAX_REPUTATION_MULTIPLIER - normalizedReputation * (MAX_REPUTATION_MULTIPLIER - MIN_REPUTATION_MULTIPLIER);
		return Math.max(1, (int) Math.round(Math.max(0, basePrice) * modifier));
	}
}
