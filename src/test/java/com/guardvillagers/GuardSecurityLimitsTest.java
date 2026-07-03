package com.guardvillagers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GuardSecurityLimitsTest {
	@Test
	void remainingPersonalGuardSlotsNeverExceedsCapOrGoesNegative() {
		assertEquals(GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER, GuardSecurityLimits.remainingPersonalGuardSlots(-5));
		assertEquals(1, GuardSecurityLimits.remainingPersonalGuardSlots(GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER - 1));
		assertEquals(0, GuardSecurityLimits.remainingPersonalGuardSlots(GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER));
		assertEquals(0, GuardSecurityLimits.remainingPersonalGuardSlots(GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER + 20));
	}

	@Test
	void clientDebugRangeRejectsNonFiniteAndCapsLargeValues() {
		assertEquals(0.0D, GuardSecurityLimits.sanitizeClientDebugRange(false, 64.0D));
		assertEquals(0.0D, GuardSecurityLimits.sanitizeClientDebugRange(true, Double.POSITIVE_INFINITY));
		assertEquals(0.0D, GuardSecurityLimits.sanitizeClientDebugRange(true, Double.NaN));
		assertEquals(1.0D, GuardSecurityLimits.sanitizeClientDebugRange(true, -5.0D));
		assertEquals(GuardSecurityLimits.MAX_CLIENT_DEBUG_RANGE, GuardSecurityLimits.sanitizeClientDebugRange(true, GuardSecurityLimits.MAX_CLIENT_DEBUG_RANGE * 4.0D));
	}
}
