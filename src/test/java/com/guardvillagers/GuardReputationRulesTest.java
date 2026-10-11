package com.guardvillagers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class GuardReputationRulesTest {
	@Test
	void parsesValidOperatorInput() {
		assertEquals(0.0D, GuardReputationManager.parseReputationInput("0"));
		assertEquals(0.5D, GuardReputationManager.parseReputationInput("0.5"));
		assertEquals(0.75D, GuardReputationManager.parseReputationInput(" 0.75 "));
		assertEquals(1.0D, GuardReputationManager.parseReputationInput("1.00"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "-0.1", "1.01", "1.5", "0.123", ".5", "1.", "NaN", "Infinity", "1e-1", "0x1", "abc"})
	void rejectsInvalidOperatorInput(String input) {
		assertNull(GuardReputationManager.parseReputationInput(input));
	}

	@Test
	void rejectsNullInput() {
		assertNull(GuardReputationManager.parseReputationInput(null));
	}

	@Test
	void formatsWithTwoDecimals() {
		assertEquals("0.50", GuardReputationManager.formatReputation(0.5D));
		assertEquals("0.01", GuardReputationManager.formatReputation(0.005D));
		assertEquals("1.00", GuardReputationManager.formatReputation(1.0D));
	}

	@Test
	void legacyDeltasKeepTheirProportions() {
		assertEquals(0.0025D, GuardReputationManager.legacyDeltaToNormalized(1), 1e-12);
		assertEquals(-0.02D, GuardReputationManager.legacyDeltaToNormalized(-8), 1e-12);
	}

	@Test
	void firstLightHitFromANonHostilePlayerIsForgiven() {
		assertTrue(GuardReputationManager.isForgivableGuardHit(null, 1_000L, 0.5D, 2.0F));
		long afterGrace = 1_000L + GuardReputationManager.GUARD_HIT_GRACE_TICKS;
		assertTrue(GuardReputationManager.isForgivableGuardHit(1_000L, afterGrace, 0.5D, 2.0F));
	}

	@Test
	void repeatedHeavyOrHostileHitsAreNotForgiven() {
		assertFalse(GuardReputationManager.isForgivableGuardHit(1_000L, 1_050L, 0.5D, 1.0F), "second hit inside the window");
		assertFalse(GuardReputationManager.isForgivableGuardHit(null, 1_000L, 0.5D, GuardReputationManager.GUARD_HIT_GRACE_MAX_DAMAGE + 0.5F), "heavy hit");
		assertFalse(GuardReputationManager.isForgivableGuardHit(null, 1_000L, GuardReputationManager.HOSTILE_THRESHOLD - 0.01D, 1.0F), "already hostile");
	}

	@Test
	void hitTimestampInTheFutureDoesNotBlockGrace() {
		assertTrue(GuardReputationManager.isForgivableGuardHit(5_000L, 1_000L, 0.5D, 1.0F));
	}
}
