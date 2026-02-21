package com.guardvillagers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.math.random.Random;

public final class GuardPlayerUpgrades {
	public static final int MAX_ARMOR_LEVEL = 4;
	public static final int MAX_WEAPON_LEVEL = 2;
	private static final Codec<Integer> ARMOR_LEVEL_CODEC = Codec.intRange(0, MAX_ARMOR_LEVEL);
	private static final Codec<Integer> WEAPON_LEVEL_CODEC = Codec.intRange(0, MAX_WEAPON_LEVEL);

	public static final Codec<GuardPlayerUpgrades> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		ARMOR_LEVEL_CODEC.optionalFieldOf("armor_level", 0).forGetter(GuardPlayerUpgrades::getArmorLevel),
		WEAPON_LEVEL_CODEC.optionalFieldOf("weapon_level", 0).forGetter(GuardPlayerUpgrades::getWeaponLevel),
		Codec.BOOL.optionalFieldOf("healing_upgrade", false).forGetter(GuardPlayerUpgrades::hasHealingUpgrade)
	).apply(instance, GuardPlayerUpgrades::new));

	private int armorLevel;
	private int weaponLevel;
	private boolean healingUpgrade;
	private transient Runnable dirtyCallback = () -> {};

	public GuardPlayerUpgrades() {
		this(0, 0, false);
	}

	private GuardPlayerUpgrades(int armorLevel, int weaponLevel, boolean healingUpgrade) {
		this.armorLevel = Math.max(0, Math.min(MAX_ARMOR_LEVEL, armorLevel));
		this.weaponLevel = Math.max(0, Math.min(MAX_WEAPON_LEVEL, weaponLevel));
		this.healingUpgrade = healingUpgrade;
	}

	public GuardPlayerUpgrades copy() {
		return new GuardPlayerUpgrades(this.armorLevel, this.weaponLevel, this.healingUpgrade);
	}

	public void setDirtyCallback(Runnable dirtyCallback) {
		this.dirtyCallback = dirtyCallback == null ? () -> {} : dirtyCallback;
	}

	public int getArmorLevel() {
		return this.armorLevel;
	}

	public int getWeaponLevel() {
		return this.weaponLevel;
	}

	public boolean hasHealingUpgrade() {
		return this.healingUpgrade;
	}

	public boolean upgradeArmor() {
		if (this.armorLevel >= MAX_ARMOR_LEVEL) {
			return false;
		}
		this.armorLevel++;
		this.dirtyCallback.run();
		return true;
	}

	public boolean upgradeWeapon() {
		if (this.weaponLevel >= MAX_WEAPON_LEVEL) {
			return false;
		}
		this.weaponLevel++;
		this.dirtyCallback.run();
		return true;
	}

	public boolean unlockHealingUpgrade() {
		if (this.healingUpgrade) {
			return false;
		}
		this.healingUpgrade = true;
		this.dirtyCallback.run();
		return true;
	}

	public int getGuardCost() {
		return 1 + this.armorLevel + this.weaponLevel + (this.healingUpgrade ? 1 : 0);
	}

	public int getArmorUpgradeCost() {
		return 4 + this.armorLevel * 4;
	}

	public int getWeaponUpgradeCost() {
		return 4 + this.weaponLevel * 4;
	}

	public int getHealingUpgradeCost() {
		return 16;
	}

	public float getHealingPerCycle() {
		return this.healingUpgrade ? 2.0F : 1.0F;
	}

	public ArmorDistribution getArmorDistribution() {
		return switch (this.armorLevel) {
			case 1 -> new ArmorDistribution(70, 20, 8, 2);
			case 2 -> new ArmorDistribution(45, 35, 15, 5);
			case 3 -> new ArmorDistribution(20, 45, 25, 10);
			case 4 -> new ArmorDistribution(5, 45, 35, 15);
			default -> new ArmorDistribution(100, 0, 0, 0);
		};
	}

	public ArmorTier rollArmorTier(Random random) {
		int roll = random.nextInt(100);
		ArmorDistribution distribution = this.getArmorDistribution();
		if (roll < distribution.leather()) {
			return ArmorTier.LEATHER;
		}
		if (roll < distribution.leather() + distribution.iron()) {
			return ArmorTier.IRON;
		}
		if (roll < distribution.leather() + distribution.iron() + distribution.gold()) {
			return ArmorTier.GOLD;
		}
		return ArmorTier.DIAMOND;
	}

	public Text toArmorPercentText() {
		ArmorDistribution d = this.getArmorDistribution();
		return Text.literal("Armor odds L:" + d.leather() + "% I:" + d.iron() + "% G:" + d.gold() + "% D:" + d.diamond() + "%");
	}

	public enum ArmorTier {
		LEATHER,
		IRON,
		GOLD,
		DIAMOND
	}

	public record ArmorDistribution(int leather, int iron, int gold, int diamond) {
	}
}
