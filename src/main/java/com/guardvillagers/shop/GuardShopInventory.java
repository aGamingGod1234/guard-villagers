package com.guardvillagers.shop;

import com.guardvillagers.GuardEconomy;
import com.guardvillagers.GuardPlayerUpgrades;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.GuardVillagersMod.PurchaseBatchResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public class GuardShopInventory extends SimpleContainer {
	public static final int SLOT_INFO = 4;
	public static final int SLOT_BUY_GUARD = 10;
	public static final int SLOT_UPGRADE_ARMOR = 12;
	public static final int SLOT_UPGRADE_WEAPON = 14;
	public static final int SLOT_UPGRADE_HEAL = 16;
	public static final int SLOT_STATUS = 22;

	private final ServerPlayer player;

	public GuardShopInventory(ServerPlayer player) {
		super(27);
		this.player = player;
		this.refresh();
	}

	@Override
	public boolean stillValid(Player player) {
		return this.player == player;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return false;
	}

	public boolean handleClick(int slot, boolean bulkPurchase) {
		switch (slot) {
			case SLOT_BUY_GUARD -> this.buyGuard(bulkPurchase);
			case SLOT_UPGRADE_ARMOR -> this.upgradeArmor();
			case SLOT_UPGRADE_WEAPON -> this.upgradeWeapon();
			case SLOT_UPGRADE_HEAL -> this.upgradeHealing();
			default -> {
				return false;
			}
		}
		return true;
	}

	private void buyGuard(boolean bulkPurchase) {
		int cost = GuardVillagersMod.getAdjustedGuardCost(this.player);
		int requested;
		if (bulkPurchase) {
			if (this.player.getAbilities().instabuild) {
				requested = 64;
			} else {
				requested = Math.max(1, GuardEconomy.countEmeraldBlocks(this.player.getInventory()) / Math.max(1, cost));
			}
		} else {
			requested = 1;
		}
		PurchaseBatchResult result = GuardVillagersMod.purchaseGuards(this.player, requested);
		switch (result.result()) {
			case SUCCESS -> {
				if (result.spawnedCount() == 1 && !result.guardNames().isEmpty()) {
					this.player.displayClientMessage(Component.literal(result.guardNames().getFirst() + " hired."), true);
				} else {
					this.player.displayClientMessage(Component.literal("Guards hired: " + result.spawnedCount() + "."), true);
				}
			}
			case NOT_TRUSTED -> this.player.displayClientMessage(Component.literal("Village trust is too low to hire guards."), true);
			case LIMIT_REACHED -> this.player.displayClientMessage(Component.literal("Personal guard limit reached."), true);
			case INSUFFICIENT_FUNDS -> this.player.displayClientMessage(Component.literal("Need " + cost + " emerald block(s) to hire a guard."), true);
			case SPAWN_FAILED -> this.player.displayClientMessage(Component.literal("Could not find space to spawn a guard. Move to open ground."), true);
			case INTERNAL_ERROR -> this.player.displayClientMessage(Component.literal("Guard purchase failed due to an internal error. Check logs."), true);
		}
	}

	private void upgradeArmor() {
		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		if (upgrades.getArmorLevel() >= GuardPlayerUpgrades.MAX_ARMOR_LEVEL) {
			this.player.displayClientMessage(Component.literal("Armor upgrades are already maxed."), true);
			return;
		}

		int cost = upgrades.getArmorUpgradeCost();
		if (!GuardVillagersMod.spendEmeraldBlocks(this.player, cost)) {
			this.player.displayClientMessage(Component.literal("Need " + cost + " emerald blocks for armor upgrade."), true);
			return;
		}

		upgrades.upgradeArmor();
		this.player.displayClientMessage(Component.literal("Armor upgrade purchased."), true);
	}

	private void upgradeWeapon() {
		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		if (upgrades.getWeaponLevel() >= GuardPlayerUpgrades.MAX_WEAPON_LEVEL) {
			this.player.displayClientMessage(Component.literal("Weapon upgrades are already maxed."), true);
			return;
		}

		int cost = upgrades.getWeaponUpgradeCost();
		if (!GuardVillagersMod.spendEmeraldBlocks(this.player, cost)) {
			this.player.displayClientMessage(Component.literal("Need " + cost + " emerald blocks for weapon upgrade."), true);
			return;
		}

		upgrades.upgradeWeapon();
		this.player.displayClientMessage(Component.literal("Weapon upgrade purchased."), true);
	}

	private void upgradeHealing() {
		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		if (upgrades.getSupportLevel() >= GuardPlayerUpgrades.MAX_SUPPORT_LEVEL) {
			this.player.displayClientMessage(Component.literal("Support upgrades are already maxed."), true);
			return;
		}

		int cost = upgrades.getHealingUpgradeCost();
		if (!GuardVillagersMod.spendEmeraldBlocks(this.player, cost)) {
			this.player.displayClientMessage(Component.literal("Need " + cost + " emerald blocks for support upgrade."), true);
			return;
		}

		upgrades.unlockHealingUpgrade();
		String message = switch (upgrades.getSupportLevel()) {
			case 1 -> "Healing upgraded to 1 heart every 2.5 seconds.";
			case 2 -> "Shield upgrade unlocked.";
			case 3 -> "Healing upgraded to 1 heart every second.";
			default -> "Support upgraded.";
		};
		this.player.displayClientMessage(Component.literal(message), true);
	}

	public void refresh() {
		for (int i = 0; i < this.getContainerSize(); i++) {
			this.setItem(i, ItemStack.EMPTY);
		}

		for (int i = 0; i < this.getContainerSize(); i++) {
			if (isInteractiveSlot(i)) {
				continue;
			}
			this.setItem(i, this.decorativePane(i));
		}

		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		boolean creativeMode = this.player.getAbilities().instabuild;

		List<String> bookStats = this.buildCurrentGuardStats(upgrades);
		this.setItem(SLOT_INFO, this.card(Items.BOOK, "Guard Villagers", ChatFormatting.GOLD, bookStats.toArray(String[]::new)));

		int guardCost = GuardVillagersMod.getAdjustedGuardCost(this.player);
		String guardCostText = creativeMode ? "Cost: Free" : "Cost: " + guardCost + " emerald block(s)";
		this.setItem(SLOT_BUY_GUARD, this.card(
			Items.EMERALD_BLOCK,
			"Hire Guard",
			ChatFormatting.GREEN,
			guardCostText,
			"Shift-click to buy max."
		));

		int armorLevel = upgrades.getArmorLevel();
		if (armorLevel >= GuardPlayerUpgrades.MAX_ARMOR_LEVEL) {
			GuardPlayerUpgrades.ArmorDistribution currentDist = upgrades.getArmorDistribution();
			this.setItem(SLOT_UPGRADE_ARMOR, this.card(
				Items.IRON_CHESTPLATE,
				"Upgrade Armor",
				ChatFormatting.AQUA,
				"Maxed",
				"L:" + currentDist.leather() + "% I:" + currentDist.iron() + "% G:" + currentDist.gold() + "% D:" + currentDist.diamond() + "%"
			));
		} else {
			int armorCost = upgrades.getArmorUpgradeCostForLevel(armorLevel);
			String armorCostText = creativeMode ? "Cost: Free" : "Cost: " + armorCost + " emerald block(s)";
			GuardPlayerUpgrades.ArmorDistribution currentDist = upgrades.getArmorDistribution();
			GuardPlayerUpgrades nextArmor = upgrades.copy();
			nextArmor.upgradeArmor();
			GuardPlayerUpgrades.ArmorDistribution nextDist = nextArmor.getArmorDistribution();
			this.setItem(SLOT_UPGRADE_ARMOR, this.card(
				Items.IRON_CHESTPLATE,
				"Upgrade Armor",
				ChatFormatting.AQUA,
				armorCostText,
				"Current: L:" + currentDist.leather() + "% I:" + currentDist.iron() + "% G:" + currentDist.gold() + "% D:" + currentDist.diamond() + "%",
				"Upgraded: L:" + nextDist.leather() + "% I:" + nextDist.iron() + "% G:" + nextDist.gold() + "% D:" + nextDist.diamond() + "%"
			));
		}

		int weaponLevel = upgrades.getWeaponLevel();
		if (weaponLevel >= GuardPlayerUpgrades.MAX_WEAPON_LEVEL) {
			this.setItem(SLOT_UPGRADE_WEAPON, this.card(
				Items.IRON_SWORD,
				"Upgrade Weapons",
				ChatFormatting.RED,
				"Maxed",
				"Current: " + describeWeaponSummary(weaponLevel)
			));
		} else {
			int weaponCost = upgrades.getWeaponUpgradeCostForLevel(weaponLevel);
			String weaponCostText = creativeMode ? "Cost: Free" : "Cost: " + weaponCost + " emerald block(s)";
			this.setItem(SLOT_UPGRADE_WEAPON, this.card(
				Items.IRON_SWORD,
				"Upgrade Weapons",
				ChatFormatting.RED,
				weaponCostText,
				"Current: " + describeWeaponSummary(weaponLevel),
				"Upgraded: " + describeWeaponSummary(weaponLevel + 1)
			));
		}

		if (upgrades.getSupportLevel() >= GuardPlayerUpgrades.MAX_SUPPORT_LEVEL) {
			this.setItem(SLOT_UPGRADE_HEAL, this.card(
				Items.GOLDEN_APPLE,
				"Support Upgrade",
				ChatFormatting.LIGHT_PURPLE,
				"Maxed",
				"Heal: 1 heart / 1s",
				"Shield: Enabled"
			));
		} else {
			int supportCost = upgrades.getHealingUpgradeCost();
			String supportCostText = creativeMode ? "Cost: Free" : "Cost: " + supportCost + " emerald block(s)";
			String nextLabel = switch (upgrades.getSupportLevel()) {
				case 0 -> "Next: Heal 1 heart / 2.5s";
				case 1 -> "Next: Shield";
				default -> "Next: Heal 1 heart / 1s";
			};
			this.setItem(SLOT_UPGRADE_HEAL, this.card(
				Items.GOLDEN_APPLE,
				"Support Upgrade",
				ChatFormatting.LIGHT_PURPLE,
				supportCostText,
				nextLabel,
				"Upgrades in 3 stages."
			));
		}

		this.setItem(SLOT_STATUS, this.card(
			Items.COMPASS,
			"Current Levels",
			ChatFormatting.YELLOW,
			"Armor Lv: " + upgrades.getArmorLevel() + "/" + GuardPlayerUpgrades.MAX_ARMOR_LEVEL,
			"Weapon Lv: " + upgrades.getWeaponLevel() + "/" + GuardPlayerUpgrades.MAX_WEAPON_LEVEL,
			"Support Lv: " + upgrades.getSupportLevel() + "/" + GuardPlayerUpgrades.MAX_SUPPORT_LEVEL
		));
	}

	private List<String> buildCurrentGuardStats(GuardPlayerUpgrades upgrades) {
		List<String> lines = new ArrayList<>();
		GuardPlayerUpgrades.ArmorDistribution dist = upgrades.getArmorDistribution();
		lines.add("\u00A77Armor odds: \u00A7fL:" + dist.leather() + "% I:" + dist.iron() + "% G:" + dist.gold() + "% D:" + dist.diamond() + "%");
		lines.add("\u00A77Sword: \u00A7f" + describeSwordLevel(upgrades.getWeaponLevel()));
		lines.add("\u00A77Bow: \u00A7f" + describeBowLevel(upgrades.getWeaponLevel()));
		lines.add("\u00A77Healing: \u00A7f" + (upgrades.hasHealingUpgrade() ? "Enabled" : "Disabled"));
		lines.add("\u00A77Shield: \u00A7f" + (upgrades.hasShieldUpgrade() ? "Enabled" : "Disabled"));
		return lines;
	}

	private String describeWeaponSummary(int level) {
		return "Sword: " + describeSwordLevel(level) + " | Bow: " + describeBowLevel(level);
	}

	private String describeSwordLevel(int level) {
		return switch (Math.max(0, level)) {
			case 0 -> "Stone";
			case 1 -> "Iron";
			case 2 -> "Diamond";
			case 3 -> "Diamond (Sharp III)";
			case 4 -> "Diamond (Sharp IV)";
			case 5 -> "Diamond (Sharp V)";
			default -> "Diamond (Sharp V)";
		};
	}

	private String describeBowLevel(int level) {
		int powerLevel = Math.min(5, Math.max(1, level + 1));
		return "Power " + toRoman(powerLevel);
	}

	private String toRoman(int value) {
		return switch (value) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			default -> "V";
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
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
		return stack;
	}

	private ItemStack card(Item item, String title, ChatFormatting titleColor, String... lines) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(titleColor, ChatFormatting.BOLD));
		if (lines.length > 0) {
			List<Component> loreLines = new ArrayList<>();
			for (String line : lines) {
				loreLines.add(Component.literal(line));
			}
			stack.set(DataComponents.LORE, new ItemLore(loreLines));
		}
		return stack;
	}
}
