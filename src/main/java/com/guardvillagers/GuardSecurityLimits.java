package com.guardvillagers;

public final class GuardSecurityLimits {
	public static final int MAX_REPUTATION_PLAYERS = 4096;
	public static final int MAX_UPGRADE_PLAYERS = 2048;
	public static final int MAX_TACTICS_PLAYERS = 2048;
	public static final int MAX_VILLAGES = 1024;
	public static final int MAX_PERSONAL_GUARDS_PER_PLAYER = 16;
	public static final double MAX_CLIENT_DEBUG_RANGE = 256.0D;
	public static final int MAX_CLIENT_DEBUG_SNAPSHOTS = 256;
	public static final int MAX_CLIENT_DEBUG_PATH_NODES = 8192;

	private GuardSecurityLimits() {
	}

	public static int remainingPersonalGuardSlots(int ownedGuardCount) {
		return Math.max(0, MAX_PERSONAL_GUARDS_PER_PLAYER - Math.max(0, ownedGuardCount));
	}

	public static double sanitizeClientDebugRange(boolean enabled, double requestedRange) {
		if (!enabled || !Double.isFinite(requestedRange)) {
			return 0.0D;
		}
		return Math.max(1.0D, Math.min(requestedRange, MAX_CLIENT_DEBUG_RANGE));
	}
}
