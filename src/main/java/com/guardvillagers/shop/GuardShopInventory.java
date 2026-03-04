package com.guardvillagers.shop;

import com.guardvillagers.GuardEconomy;
import com.guardvillagers.GuardPlayerUpgrades;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.GuardVillagersMod.PurchaseBatchResult;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
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
		int requested = bulkPurchase ? Math.max(1, GuardEconomy.countEmeraldBlocks(this.player.getInventory()) / Math.max(1, cost)) : 1;
		PurchaseBatchResult result = GuardVillagersMod.purchaseGuards(this.player, requested);
		switch (result.result()) {
			case SUCCESS -> this.player.sendMessage(Text.literal("Guard hired: " + result.spawnedCount() + "."), true);
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

		List<String> bookStats = this.buildCurrentGuardStats(upgrades);
		this.setStack(SLOT_INFO, this.card(Items.BOOK, "Guard Villagers", Formatting.GOLD, bookStats.toArray(String[]::new)));

		int guardCost = GuardVillagersMod.getAdjustedGuardCost(this.player);
		this.setStack(SLOT_BUY_GUARD, this.card(
			Items.EMERALD_BLOCK,
			"Hire Guard",
			Formatting.GREEN,
			"Cost: " + guardCost + " emerald block(s)",
			"Shift-click buys multiple",
			"with round-robin roles."
		));

		int armorLevel = upgrades.getArmorLevel();
		if (armorLevel >= GuardPlayerUpgrades.MAX_ARMOR_LEVEL) {
			GuardPlayerUpgrades.ArmorDistribution currentDist = upgrades.getArmorDistribution();
			this.setStack(SLOT_UPGRADE_ARMOR, this.card(
				Items.IRON_CHESTPLATE,
				"Upgrade Armor",
				Formatting.AQUA,
				"Maxed",
				"L:" + currentDist.leather() + "% I:" + currentDist.iron() + "% G:" + currentDist.gold() + "% D:" + currentDist.diamond() + "%"
			));
		} else {
			int armorCost = upgrades.getArmorUpgradeCostForLevel(armorLevel);
			GuardPlayerUpgrades.ArmorDistribution currentDist = upgrades.getArmorDistribution();
			GuardPlayerUpgrades nextArmor = upgrades.copy();
			nextArmor.upgradeArmor();
			GuardPlayerUpgrades.ArmorDistribution nextDist = nextArmor.getArmorDistribution();
			this.setStack(SLOT_UPGRADE_ARMOR, this.card(
				Items.IRON_CHESTPLATE,
				"Upgrade Armor",
				Formatting.AQUA,
				"Cost: " + armorCost + " emerald block(s)",
				"Current: L:" + currentDist.leather() + "% I:" + currentDist.iron() + "% G:" + currentDist.gold() + "% D:" + currentDist.diamond() + "%",
				"Upgraded: L:" + nextDist.leather() + "% I:" + nextDist.iron() + "% G:" + nextDist.gold() + "% D:" + nextDist.diamond() + "%"
			));
		}

		int weaponLevel = upgrades.getWeaponLevel();
		if (weaponLevel >= GuardPlayerUpgrades.MAX_WEAPON_LEVEL) {
			this.setStack(SLOT_UPGRADE_WEAPON, this.card(
				Items.IRON_SWORD,
				"Upgrade Weapons",
				Formatting.RED,
				"Maxed",
				"Current: " + describeWeaponLevel(weaponLevel)
			));
		} else {
			int weaponCost = upgrades.getWeaponUpgradeCost();
			this.setStack(SLOT_UPGRADE_WEAPON, this.card(
				Items.IRON_SWORD,
				"Upgrade Weapons",
				Formatting.RED,
				"Cost: " + weaponCost + " emerald block(s)",
				"Current: " + describeWeaponLevel(weaponLevel),
				"Upgraded: " + describeWeaponLevel(weaponLevel + 1)
			));
		}

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

	private List<String> buildCurrentGuardStats(GuardPlayerUpgrades upgrades) {
		List<String> lines = new ArrayList<>();
		int level = upgrades.getHireLevel();
		ItemStack weapon = this.weaponForLevel(upgrades.getWeaponLevel());
		ItemStack helmet = this.armorForSlot("helmet", upgrades);
		ItemStack chest = this.armorForSlot("chestplate", upgrades);
		ItemStack legs = this.armorForSlot("leggings", upgrades);
		ItemStack boots = this.armorForSlot("boots", upgrades);
		int helmetArmor = this.armorPoints(helmet);
		int chestArmor = this.armorPoints(chest);
		int legsArmor = this.armorPoints(legs);
		int bootsArmor = this.armorPoints(boots);
		int totalArmor = helmetArmor + chestArmor + legsArmor + bootsArmor;
		double health = 20.0D + Math.max(0, level - 1) * 2.0D;
		double attackSpeed = 1.6D;
		int cooldownTicks = Math.max(1, (int) Math.round(20.0D / attackSpeed));

		lines.add("\u00A77Weapon: \u00A7f" + weapon.getName().getString() + " (" + this.weaponDamage(weapon) + " dmg)");
		lines.add("\u00A77Helmet: \u00A7f" + helmet.getName().getString() + " (" + helmetArmor + ")");
		lines.add("\u00A77Chestplate: \u00A7f" + chest.getName().getString() + " (" + chestArmor + ")");
		lines.add("\u00A77Leggings: \u00A7f" + legs.getName().getString() + " (" + legsArmor + ")");
		lines.add("\u00A77Boots: \u00A7f" + boots.getName().getString() + " (" + bootsArmor + ")");
		lines.add("\u00A77Total armour: \u00A7f" + totalArmor);
		lines.add("\u00A77Health: \u00A7f" + (int) health + " HP");
		lines.add("\u00A77Attack speed: \u00A7f" + String.format("%.2f", attackSpeed) + " (" + cooldownTicks + "t cd)");
		lines.add("\u00A77Hire price: \u00A7f" + GuardVillagersMod.getAdjustedGuardCost(this.player) + " emerald block(s)");
		GuardPlayerUpgrades.ArmorDistribution dist = upgrades.getArmorDistribution();
		lines.add("\u00A77Armor odds: \u00A7fL:" + dist.leather() + "% I:" + dist.iron() + "% G:" + dist.gold() + "% D:" + dist.diamond() + "%");
		if (upgrades.getArmorLevel() < GuardPlayerUpgrades.MAX_ARMOR_LEVEL) {
			GuardPlayerUpgrades nextArmor = upgrades.copy();
			nextArmor.upgradeArmor();
			GuardPlayerUpgrades.ArmorDistribution nextDist = nextArmor.getArmorDistribution();
			lines.add("\u00A77Next armor: \u00A7fL:" + nextDist.leather() + "% I:" + nextDist.iron() + "% G:" + nextDist.gold() + "% D:" + nextDist.diamond() + "%");
		}
		lines.add("\u00A77Weapon: \u00A7f" + describeWeaponLevel(upgrades.getWeaponLevel()));
		if (upgrades.getWeaponLevel() < GuardPlayerUpgrades.MAX_WEAPON_LEVEL) {
			lines.add("\u00A77Next weapon: \u00A7f" + describeWeaponLevel(upgrades.getWeaponLevel() + 1));
		}
		return lines;
	}

	private ItemStack weaponForLevel(int weaponLevel) {
		Item item = switch (Math.max(0, weaponLevel)) {
			case 1 -> Items.IRON_SWORD;
			case 2, 3, 4, 5 -> Items.DIAMOND_SWORD;
			default -> Items.STONE_SWORD;
		};
		return new ItemStack(item);
	}

	private ItemStack armorForSlot(String type, GuardPlayerUpgrades upgrades) {
		GuardPlayerUpgrades.ArmorDistribution dist = upgrades.getArmorDistribution();
		GuardPlayerUpgrades.ArmorTier tier;
		if (dist.diamond() >= dist.iron() && dist.diamond() >= dist.gold() && dist.diamond() >= dist.leather()) {
			tier = GuardPlayerUpgrades.ArmorTier.DIAMOND;
		} else if (dist.iron() >= dist.gold() && dist.iron() >= dist.leather()) {
			tier = GuardPlayerUpgrades.ArmorTier.IRON;
		} else if (dist.gold() >= dist.leather()) {
			tier = GuardPlayerUpgrades.ArmorTier.GOLD;
		} else {
			tier = GuardPlayerUpgrades.ArmorTier.LEATHER;
		}

		Item item = switch (tier) {
			case IRON -> switch (type) {
				case "helmet" -> Items.IRON_HELMET;
				case "chestplate" -> Items.IRON_CHESTPLATE;
				case "leggings" -> Items.IRON_LEGGINGS;
				case "boots" -> Items.IRON_BOOTS;
				default -> Items.IRON_BOOTS;
			};
			case GOLD -> switch (type) {
				case "helmet" -> Items.GOLDEN_HELMET;
				case "chestplate" -> Items.GOLDEN_CHESTPLATE;
				case "leggings" -> Items.GOLDEN_LEGGINGS;
				case "boots" -> Items.GOLDEN_BOOTS;
				default -> Items.GOLDEN_BOOTS;
			};
			case DIAMOND, NETHERITE -> switch (type) {
				case "helmet" -> Items.DIAMOND_HELMET;
				case "chestplate" -> Items.DIAMOND_CHESTPLATE;
				case "leggings" -> Items.DIAMOND_LEGGINGS;
				case "boots" -> Items.DIAMOND_BOOTS;
				default -> Items.DIAMOND_BOOTS;
			};
			default -> switch (type) {
				case "helmet" -> Items.LEATHER_HELMET;
				case "chestplate" -> Items.LEATHER_CHESTPLATE;
				case "leggings" -> Items.LEATHER_LEGGINGS;
				case "boots" -> Items.LEATHER_BOOTS;
				default -> Items.LEATHER_BOOTS;
			};
		};
		return new ItemStack(item);
	}

	private int armorPoints(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		String path = Registries.ITEM.getId(stack.getItem()).getPath();
		if (path.contains("helmet")) {
			if (path.contains("netherite") || path.contains("diamond")) return 3;
			if (path.contains("iron")) return 2;
			if (path.contains("chainmail") || path.contains("golden")) return 2;
			if (path.contains("leather")) return 1;
		}
		if (path.contains("chestplate")) {
			if (path.contains("netherite") || path.contains("diamond")) return 8;
			if (path.contains("iron")) return 6;
			if (path.contains("chainmail") || path.contains("golden")) return 5;
			if (path.contains("leather")) return 3;
		}
		if (path.contains("leggings")) {
			if (path.contains("netherite") || path.contains("diamond")) return 6;
			if (path.contains("iron")) return 5;
			if (path.contains("chainmail") || path.contains("golden")) return 3;
			if (path.contains("leather")) return 2;
		}
		if (path.contains("boots")) {
			if (path.contains("netherite") || path.contains("diamond")) return 3;
			if (path.contains("iron")) return 2;
			if (path.contains("chainmail") || path.contains("golden")) return 1;
			if (path.contains("leather")) return 1;
		}
		return 0;
	}

	private double weaponDamage(ItemStack stack) {
		if (stack.isOf(Items.STONE_SWORD)) {
			return 5.0D;
		}
		if (stack.isOf(Items.IRON_SWORD)) {
			return 6.0D;
		}
		if (stack.isOf(Items.DIAMOND_SWORD)) {
			return 7.0D;
		}
		return 1.0D;
	}

	private String describeWeaponLevel(int level) {
		return switch (Math.max(0, level)) {
			case 0 -> "Stone Sword / Basic Bow";
			case 1 -> "Iron Sword / Sharpness I";
			case 2 -> "Diamond Sword / Sharpness II";
			case 3 -> "Diamond Sword / Sharpness III";
			case 4 -> "Diamond Sword / Sharpness IV";
			case 5 -> "Diamond Sword / Sharpness V";
			default -> "Diamond Sword / Sharpness V";
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
				loreLines.add(Text.literal(line));
			}
			stack.set(DataComponentTypes.LORE, new LoreComponent(loreLines));
		}
		return stack;
	}
}
