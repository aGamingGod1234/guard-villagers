package com.guardvillagers.shop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardShopCooldownTest {
	@Test
	void everyTimestampCanBeTheFirstAction() {
		for (int start : new int[]{0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
			var cooldown = new GuardShopScreenHandler.ActionCooldown();
			assertTrue(cooldown.tryAction(start));
			assertFalse(cooldown.tryAction(start));
			assertFalse(cooldown.tryAction(start + 3));
			assertTrue(cooldown.tryAction(start + 4));
			assertFalse(cooldown.tryAction(start + 4));
		}
	}

	@Test
	void rejectedActionsDoNotExtendCooldownAcrossSignedWrap() {
		var cooldown = new GuardShopScreenHandler.ActionCooldown();
		assertTrue(cooldown.tryAction(Integer.MAX_VALUE - 1));
		assertFalse(cooldown.tryAction(Integer.MAX_VALUE));
		assertFalse(cooldown.tryAction(Integer.MIN_VALUE));
		assertFalse(cooldown.tryAction(Integer.MIN_VALUE + 1));
		assertTrue(cooldown.tryAction(Integer.MIN_VALUE + 2));
	}
}
