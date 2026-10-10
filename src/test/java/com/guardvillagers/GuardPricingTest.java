package com.guardvillagers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class GuardPricingTest {
	@Test
	void hirePriceGrowsWithLevel() {
		assertEquals(4, GuardHirePricing.getHirePrice(0));
		assertEquals(4, GuardHirePricing.getHirePrice(1));
		assertEquals(10, GuardHirePricing.getHirePrice(4));
	}

	@Test
	void reputationScalesShopPriceBetweenLimits() {
		assertEquals(6, GuardHirePricing.applyReputationModifier(4, 0.0D));
		assertEquals(5, GuardHirePricing.applyReputationModifier(4, 0.5D));
		assertEquals(3, GuardHirePricing.applyReputationModifier(4, 1.0D));
	}

	@Test
	void reputationPriceIsClampedAndNeverFree() {
		assertEquals(GuardHirePricing.applyReputationModifier(4, 1.0D), GuardHirePricing.applyReputationModifier(4, 7.0D));
		assertEquals(GuardHirePricing.applyReputationModifier(4, 0.0D), GuardHirePricing.applyReputationModifier(4, Double.NaN));
		assertEquals(1, GuardHirePricing.applyReputationModifier(0, 1.0D));
		assertEquals(1, GuardHirePricing.applyReputationModifier(-50, 0.0D));
	}

	@Test
	void upgradeCostsAreBoundedAndMaxLevelsStop() {
		GuardPlayerUpgrades upgrades = new GuardPlayerUpgrades();
		for (int i = 0; i < 20; i++) {
			upgrades.upgradeArmor();
			upgrades.upgradeWeapon();
			upgrades.unlockHealingUpgrade();
		}
		assertEquals(GuardPlayerUpgrades.MAX_ARMOR_LEVEL, upgrades.getArmorLevel());
		assertEquals(GuardPlayerUpgrades.MAX_WEAPON_LEVEL, upgrades.getWeaponLevel());
		assertEquals(GuardPlayerUpgrades.MAX_SUPPORT_LEVEL, upgrades.getSupportLevel());
		assertEquals(64, upgrades.getArmorUpgradeCost());
		assertEquals(64, upgrades.getWeaponUpgradeCost());
		assertEquals(0, upgrades.getHealingUpgradeCost());
		assertEquals(GuardPlayerUpgrades.MAX_HIRE_LEVEL, upgrades.getHireLevel());
	}

	@Test
	void armorDistributionsSumToOneHundred() {
		GuardPlayerUpgrades upgrades = new GuardPlayerUpgrades();
		for (int level = 0; level <= GuardPlayerUpgrades.MAX_ARMOR_LEVEL; level++) {
			GuardPlayerUpgrades.ArmorDistribution d = upgrades.getArmorDistribution();
			assertEquals(100, d.leather() + d.iron() + d.gold() + d.diamond(), "armor level " + level);
			upgrades.upgradeArmor();
		}
	}
}
