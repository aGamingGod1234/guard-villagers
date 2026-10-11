package com.guardvillagers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GuardSecurityLimitsTest {
	@Test
	void zeroDefaultAllowsHundredsAndConfiguredCapIsRespected() {
		assertEquals(0, GuardGameRules.DEFAULT_MAX_PERSONAL_GUARDS);
		assertEquals(Integer.MAX_VALUE, GuardSecurityLimits.remainingPersonalGuardSlots(1000, 0));
		assertEquals(16, GuardSecurityLimits.remainingPersonalGuardSlots(-5, 16));
		assertEquals(1, GuardSecurityLimits.remainingPersonalGuardSlots(15, 16));
		assertEquals(0, GuardSecurityLimits.remainingPersonalGuardSlots(16, 16));
		assertEquals(0, GuardSecurityLimits.remainingPersonalGuardSlots(1000, 16));
		assertEquals(700, GuardSecurityLimits.remainingPersonalGuardSlots(300, 1000));
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
