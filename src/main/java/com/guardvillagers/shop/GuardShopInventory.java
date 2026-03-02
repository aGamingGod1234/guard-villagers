package com.guardvillagers.shop;

import com.guardvillagers.GuardPlayerUpgrades;
import com.guardvillagers.GuardVillagersMod;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public class GuardShopInventory extends SimpleInventory {
	public static final int SLOT_INFO = 4;
	public static final int SLOT_BUY_GUARD = 10;
	public static final int SLOT_UPGRADE_ARMOR = 12;
	public static final int SLOT_UPGRADE_WEAPON = 14;
	public static final int SLOT_UPGRADE_HEAL = 16;
	public static final int SLOT_STATUS = 22;

	private final ServerPlayerEntity player;

	public GuardShopInventory(ServerPlayerEntity player) {
		super(27);
		this.player = player;
		this.refresh();
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return this.player == player;
	}

	@Override
	public boolean isValid(int slot, ItemStack stack) {
		return false;
	}

	public boolean handleClick(int slot) {
		switch (slot) {
			case SLOT_BUY_GUARD -> this.buyGuard();
			case SLOT_UPGRADE_ARMOR -> this.upgradeArmor();
			case SLOT_UPGRADE_WEAPON -> this.upgradeWeapon();
			case SLOT_UPGRADE_HEAL -> this.upgradeHealing();
			default -> {
				return false;
			}
		}
		return true;
	}

	private void buyGuard() {
		int cost = GuardVillagersMod.getAdjustedGuardCost(this.player);
		switch (GuardVillagersMod.purchaseGuard(this.player)) {
			case SUCCESS -> this.player.sendMessage(Text.literal("Guard hired."), true);
			case NOT_TRUSTED -> this.player.sendMessage(Text.literal("Village trust is too low to hire guards."), true);
			case INSUFFICIENT_FUNDS -> this.player.sendMessage(Text.literal("Need " + cost + " emerald block(s) to hire a guard."), true);
			case SPAWN_FAILED -> this.player.sendMessage(Text.literal("Could not find space to spawn a guard. Move to open ground."), true);
			case INTERNAL_ERROR -> this.player.sendMessage(Text.literal("Guard purchase failed due to an internal error. Check logs."), true);
		}
	}

	private void upgradeArmor() {
		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		if (upgrades.getArmorLevel() >= GuardPlayerUpgrades.MAX_ARMOR_LEVEL) {
			this.player.sendMessage(Text.literal("Armor upgrades are already maxed."), true);
			return;
		}

		int cost = upgrades.getArmorUpgradeCost();
		if (!GuardVillagersMod.spendEmeraldBlocks(this.player, cost)) {
			this.player.sendMessage(Text.literal("Need " + cost + " emerald blocks for armor upgrade."), true);
			return;
		}

		upgrades.upgradeArmor();
		this.player.sendMessage(Text.literal("Armor upgrade purchased."), true);
	}

	private void upgradeWeapon() {
		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		if (upgrades.getWeaponLevel() >= GuardPlayerUpgrades.MAX_WEAPON_LEVEL) {
			this.player.sendMessage(Text.literal("Weapon upgrades are already maxed."), true);
			return;
		}

		int cost = upgrades.getWeaponUpgradeCost();
		if (!GuardVillagersMod.spendEmeraldBlocks(this.player, cost)) {
			this.player.sendMessage(Text.literal("Need " + cost + " emerald blocks for weapon upgrade."), true);
			return;
		}

		upgrades.upgradeWeapon();
		this.player.sendMessage(Text.literal("Weapon upgrade purchased."), true);
	}

	private void upgradeHealing() {
		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		if (upgrades.getSupportLevel() >= GuardPlayerUpgrades.MAX_SUPPORT_LEVEL) {
			this.player.sendMessage(Text.literal("Support upgrades are already maxed."), true);
			return;
		}

		int cost = upgrades.getHealingUpgradeCost();
		if (!GuardVillagersMod.spendEmeraldBlocks(this.player, cost)) {
			this.player.sendMessage(Text.literal("Need " + cost + " emerald blocks for support upgrade."), true);
			return;
		}

		upgrades.unlockHealingUpgrade();
		String message = switch (upgrades.getSupportLevel()) {
			case 1 -> "Healing upgraded to 1 heart every 2.5 seconds.";
			case 2 -> "Shield upgrade unlocked.";
			case 3 -> "Healing upgraded to 1 heart every second.";
			default -> "Support upgraded.";
		};
		this.player.sendMessage(Text.literal(message), true);
	}

	public void refresh() {
		for (int i = 0; i < this.size(); i++) {
			this.setStack(i, ItemStack.EMPTY);
		}

		for (int i = 0; i < this.size(); i++) {
			if (isInteractiveSlot(i)) {
				continue;
			}
			this.setStack(i, this.decorativePane(i));
		}

		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		GuardPlayerUpgrades.ArmorDistribution dist = upgrades.getArmorDistribution();

		this.setStack(SLOT_INFO, this.card(
			Items.BOOK,
			"Guard Villagers",
			Formatting.GOLD,
			"Armor odds:",
			"Leather " + dist.leather() + "%",
			"Iron " + dist.iron() + "%",
			"Gold " + dist.gold() + "%",
			"Diamond " + dist.diamond() + "%"
		));

		int guardCost = GuardVillagersMod.getAdjustedGuardCost(this.player);
		this.setStack(SLOT_BUY_GUARD, this.card(
			Items.EMERALD_BLOCK,
			"Hire Guard",
			Formatting.GREEN,
			"Cost: " + guardCost + " emerald block(s)",
			"Spawns with your current",
			"armor, weapon and support levels."
		));

		int armorCost = upgrades.getArmorUpgradeCost();
		this.setStack(SLOT_UPGRADE_ARMOR, this.card(
			Items.IRON_CHESTPLATE,
			"Upgrade Armor",
			Formatting.AQUA,
			"Cost: " + armorCost + " emerald block(s)",
			"Current: Lv " + upgrades.getArmorLevel() + "/" + GuardPlayerUpgrades.MAX_ARMOR_LEVEL,
			"Power increases linearly;",
			"cost scales exponentially."
		));

		int weaponCost = upgrades.getWeaponUpgradeCost();
		this.setStack(SLOT_UPGRADE_WEAPON, this.card(
			Items.IRON_SWORD,
			"Upgrade Weapons",
			Formatting.RED,
			"Cost: " + weaponCost + " emerald block(s)",
			"Current: " + describeWeaponLevel(upgrades.getWeaponLevel()),
			"Next: " + describeWeaponLevel(Math.min(GuardPlayerUpgrades.MAX_WEAPON_LEVEL, upgrades.getWeaponLevel() + 1))
		));

		if (upgrades.getSupportLevel() >= GuardPlayerUpgrades.MAX_SUPPORT_LEVEL) {
			this.setStack(SLOT_UPGRADE_HEAL, this.card(
				Items.GOLDEN_APPLE,
				"Support Upgrade",
				Formatting.LIGHT_PURPLE,
				"Maxed",
				"Heal: 1 heart / 1s",
				"Shield: Enabled"
			));
		} else {
			int supportCost = upgrades.getHealingUpgradeCost();
			String nextLabel = switch (upgrades.getSupportLevel()) {
				case 0 -> "Next: Heal 1 heart / 2.5s";
				case 1 -> "Next: Shield";
				default -> "Next: Heal 1 heart / 1s";
			};
			this.setStack(SLOT_UPGRADE_HEAL, this.card(
				Items.GOLDEN_APPLE,
				"Support Upgrade",
				Formatting.LIGHT_PURPLE,
				"Cost: " + supportCost + " emerald block(s)",
				nextLabel,
				"Upgrades in 3 stages."
			));
		}

		this.setStack(SLOT_STATUS, this.card(
			Items.COMPASS,
			"Current Levels",
			Formatting.YELLOW,
			"Armor Lv: " + upgrades.getArmorLevel() + "/" + GuardPlayerUpgrades.MAX_ARMOR_LEVEL,
			"Weapon Lv: " + upgrades.getWeaponLevel() + "/" + GuardPlayerUpgrades.MAX_WEAPON_LEVEL,
			"Support Lv: " + upgrades.getSupportLevel() + "/" + GuardPlayerUpgrades.MAX_SUPPORT_LEVEL
		));
	}

	private String describeWeaponLevel(int level) {
		return switch (Math.max(0, level)) {
			case 0 -> "Stone/Basic Bow";
			case 1 -> "Iron + enchant I";
			case 2 -> "Diamond + enchant II";
			case 3 -> "Diamond + enchant III";
			case 4 -> "Diamond + cap tier";
			default -> "Diamond + cap tier";
		};
	}

	private boolean isInteractiveSlot(int slot) {
		return slot == SLOT_INFO
			|| slot == SLOT_BUY_GUARD
			|| slot == SLOT_UPGRADE_ARMOR
			|| slot == SLOT_UPGRADE_WEAPON
			|| slot == SLOT_UPGRADE_HEAL
			|| slot == SLOT_STATUS;
	}

	private ItemStack decorativePane(int slot) {
		Item pane = (slot % 2 == 0) ? Items.BLACK_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE;
		ItemStack stack = new ItemStack(pane);
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
		return stack;
	}

	private ItemStack card(Item item, String title, Formatting titleColor, String... lines) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title).formatted(titleColor, Formatting.BOLD));
		if (lines.length > 0) {
			List<Text> loreLines = new ArrayList<>();
			for (String line : lines) {
				loreLines.add(Text.literal(line).formatted(Formatting.GRAY));
			}
			stack.set(DataComponentTypes.LORE, new LoreComponent(loreLines));
		}
		return stack;
	}
}
