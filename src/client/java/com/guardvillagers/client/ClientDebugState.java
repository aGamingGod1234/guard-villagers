package com.guardvillagers.client;

import com.guardvillagers.GuardSecurityLimits;

public final class ClientDebugState {
	private static volatile boolean enabled;
	private static volatile double range;

	private ClientDebugState() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static double getRange() {
		return range;
	}

	public static void update(boolean enabled, double range) {
		double sanitizedRange = GuardSecurityLimits.sanitizeClientDebugRange(enabled, range);
		ClientDebugState.enabled = enabled && sanitizedRange > 0.0D;
		ClientDebugState.range = sanitizedRange;
	}

	public static void reset() {
		enabled = false;
		range = 0.0D;
	}
}
