package com.guardvillagers.tactics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardTacticsCommandsTest {
	@Test
	void permitsOnlyTacticsUiActions() {
		for (String command : new String[]{"guards groups add", "guards groups rename 1 Vanguard", "guards groups follow 1", "guards groups stay 0",
				"guards groups assignmany 2 00000000-0000-0000-0000-000000000001", "guards follow",
				"guards stay", "guards appoint captain 00000000-0000-0000-0000-000000000001"}) {
			assertTrue(GuardTacticsCommands.isAllowedCommand(command), command);
		}
		for (String command : new String[]{"", "stop", "/guards follow", "guards say attack", "guards reputation 1",
				"guards follow Vanguard", "guards groups follow 65", "guards groups rename 0 Invalid", "guards groups rename 1 " + "x".repeat(25),
				"guards groups", "guards groups delete 1", "guards groups add extra", "execute run guards follow",
				"guards follow\nstop", "guards follow " + "x".repeat(256)}) {
			assertFalse(GuardTacticsCommands.isAllowedCommand(command), command);
		}
		assertFalse(GuardTacticsCommands.isAllowedCommand(null));
	}
}
