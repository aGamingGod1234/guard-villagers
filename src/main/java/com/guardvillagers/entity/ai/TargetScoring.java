package com.guardvillagers.entity.ai;

/**
 * Pure target scoring used by {@link GuardAiController}. Kept free of Minecraft types so the
 * ordering rules can be unit tested.
 *
 * <p>Ordering rules the weights are chosen to satisfy:
 * <ul>
 * <li>An urgent (peel / close self-defense) candidate always wins.</li>
 * <li>An owner-directed alert (owner hit, owner attacked something, whistle order) beats any
 * non-urgent current target, including one the guard is trading melee blows with.</li>
 * <li>The current target beats an equal or lower-priority new candidate, so guards do not flip
 * between targets every tick.</li>
 * <li>A target the guard is already trading melee blows with beats a distant attacker.</li>
 * <li>Targets that already have many allied guards on them lose to equally good free targets.</li>
 * </ul>
 */
public final class TargetScoring {
	public static final int URGENT_BONUS = 1000;
	public static final int SOURCE_WEIGHT = 3;
	public static final int STICKY_BONUS = 15;
	public static final int MELEE_ENGAGED_BONUS = 40;
	public static final int OWNER_DIRECTED_BONUS = 80;
	public static final double MELEE_ENGAGED_DISTANCE_SQUARED = 9.0D;
	public static final double DISTANCE_PENALTY_PER_BLOCK = 0.5D;
	public static final int MAX_DISTANCE_PENALTY = 12;
	public static final int FREE_ENGAGEMENT_SLOTS = 3;
	public static final int OVERCOMMIT_PENALTY_PER_GUARD = 6;
	public static final int MAX_OVERCOMMIT_PENALTY = 30;

	private TargetScoring() {
	}

	/**
	 * @param urgent         candidate came from an urgent source (peel)
	 * @param ownerDirected  candidate was named by the owner (owner hurt / owner attacked / order)
	 * @param sourcePriority highest alert-source priority that nominated the candidate
	 * @param threat         threat rating of the entity (see GuardAiController#scoreThreat)
	 * @param sticky         candidate is the guard's current target
	 * @param distanceSq     squared distance from guard to candidate
	 * @param otherEngaged   number of other guards currently engaging the candidate
	 */
	public static int score(boolean urgent, boolean ownerDirected, int sourcePriority, int threat, boolean sticky,
			double distanceSq, int otherEngaged) {
		int score = sourcePriority * SOURCE_WEIGHT + threat;
		if (urgent) {
			score += URGENT_BONUS;
		}
		if (ownerDirected) {
			score += OWNER_DIRECTED_BONUS;
		}
		if (sticky) {
			score += STICKY_BONUS;
			if (distanceSq <= MELEE_ENGAGED_DISTANCE_SQUARED) {
				score += MELEE_ENGAGED_BONUS;
			}
		}
		score -= distancePenalty(distanceSq);
		score -= overcommitPenalty(otherEngaged);
		return score;
	}

	public static int distancePenalty(double distanceSq) {
		if (!(distanceSq > 0.0D)) {
			return 0;
		}
		return (int) Math.min(MAX_DISTANCE_PENALTY, Math.sqrt(distanceSq) * DISTANCE_PENALTY_PER_BLOCK);
	}

	public static int overcommitPenalty(int otherEngaged) {
		int excess = otherEngaged - FREE_ENGAGEMENT_SLOTS;
		if (excess <= 0) {
			return 0;
		}
		return Math.min(MAX_OVERCOMMIT_PENALTY, excess * OVERCOMMIT_PENALTY_PER_GUARD);
	}

	/** Peel cap from the original design: max(1, min(10, floor(groupSize * 0.30))). */
	public static int peelCap(int groupSize) {
		return Math.max(1, Math.min(10, groupSize * 30 / 100));
	}
}
