package com.guardvillagers.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TargetScoringTest {
	// Source priorities mirror GuardAiController.AlertReason.
	private static final int HOSTILE_SCAN = 70;
	private static final int CURRENT_TARGET = 78;
	private static final int ALLY_BROADCAST = 80;
	private static final int OWNER_ATTACK = 88;
	private static final int OWNER_DAMAGED = 90;
	private static final int DIRECT_DAMAGE = 95;
	private static final int URGENT_PEEL = 100;
	private static final int MONSTER = 40;
	private static final int RANGED_MONSTER = 45;
	private static final double FAR = 30.0D * 30.0D;

	@Test
	void urgentCandidateBeatsEverythingElse() {
		int urgent = TargetScoring.score(true, false, URGENT_PEEL, MONSTER, false, FAR, 9);
		int ownerDirectedClose = TargetScoring.score(false, true, OWNER_ATTACK, 100, true, 1.0D, 0);
		assertTrue(urgent > ownerDirectedClose);
	}

	@Test
	void ownerDirectedTargetBeatsCurrentScanTargetEvenWhenFarther() {
		int current = TargetScoring.score(false, false, CURRENT_TARGET, MONSTER, true, 16.0D, 0);
		int ownerOrder = TargetScoring.score(false, true, OWNER_ATTACK, RANGED_MONSTER, false, FAR, 0);
		assertTrue(ownerOrder > current, "owner order " + ownerOrder + " vs current " + current);
	}

	@Test
	void ownerDamagedAlertBeatsAMeleeEngagedTarget() {
		int engaged = TargetScoring.score(false, false, CURRENT_TARGET, MONSTER, true, 1.0D, 0);
		int ownerAttacker = TargetScoring.score(false, true, OWNER_DAMAGED, 20, false, FAR, 0);
		assertTrue(ownerAttacker > engaged, "owner attacker " + ownerAttacker + " vs engaged " + engaged);
	}

	@Test
	void currentTargetIsNotDroppedForACloserScannedHostile() {
		int current = TargetScoring.score(false, false, CURRENT_TARGET, MONSTER, true, 15.0D * 15.0D, 0);
		int closerScan = TargetScoring.score(false, false, HOSTILE_SCAN, MONSTER, false, 1.0D, 0);
		int allyCall = TargetScoring.score(false, false, ALLY_BROADCAST, MONSTER, false, 1.0D, 0);
		assertTrue(current > closerScan);
		assertTrue(current > allyCall);
	}

	@Test
	void meleeEngagedTargetBeatsADistantDirectAttacker() {
		int engaged = TargetScoring.score(false, false, CURRENT_TARGET, MONSTER, true, 2.0D, 0);
		int distantArcher = TargetScoring.score(false, false, DIRECT_DAMAGE, RANGED_MONSTER, false, 15.0D * 15.0D, 0);
		assertTrue(engaged > distantArcher);
	}

	@Test
	void overcommittedTargetLosesToAnEquallyGoodFreeOne() {
		int crowded = TargetScoring.score(false, false, HOSTILE_SCAN, MONSTER, false, 25.0D, 7);
		int free = TargetScoring.score(false, false, HOSTILE_SCAN, MONSTER, false, 36.0D, 0);
		assertTrue(free > crowded);
		assertEquals(0, TargetScoring.overcommitPenalty(TargetScoring.FREE_ENGAGEMENT_SLOTS));
		assertEquals(TargetScoring.MAX_OVERCOMMIT_PENALTY, TargetScoring.overcommitPenalty(100));
	}

	@Test
	void distancePenaltyIsBoundedAndHandlesBadInput() {
		assertEquals(0, TargetScoring.distancePenalty(0.0D));
		assertEquals(0, TargetScoring.distancePenalty(Double.NaN));
		assertEquals(TargetScoring.MAX_DISTANCE_PENALTY, TargetScoring.distancePenalty(1.0E9D));
	}

	@Test
	void peelCapMatchesDesign() {
		assertEquals(1, TargetScoring.peelCap(1));
		assertEquals(1, TargetScoring.peelCap(6));
		assertEquals(2, TargetScoring.peelCap(8));
		assertEquals(9, TargetScoring.peelCap(32));
		assertEquals(10, TargetScoring.peelCap(64));
	}
}
