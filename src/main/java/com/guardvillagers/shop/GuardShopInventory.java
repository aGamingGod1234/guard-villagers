package com.guardvillagers.shop;

import com.guardvillagers.GuardEconomy;
import com.guardvillagers.GuardHiring;
import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardPlayerUpgrades;
import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.GuardSecurityLimits;
import com.guardvillagers.GuardVillagersMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import it.unimi.dsi.fastutil.objects.ReferenceSortedSets;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;

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
		int cost = GuardHiring.getAdjustedGuardCost(this.player);
		int requested = 1;
		if (bulkPurchase) {
			requested = this.player.getAbilities().instabuild
				? GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER
				: Math.max(1, GuardEconomy.countEmeraldBlocks(this.player.getInventory()) / cost);
		}
		GuardHiring.BatchResult result = GuardHiring.hire(this.player, requested);
		Component message = switch (result.result()) {
			case SUCCESS -> result.spawnedCount() == 1 && !result.guardNames().isEmpty()
				? Component.translatable("message.guardvillagers.shop.hired_one", result.guardNames().getFirst())
				: Component.translatable("message.guardvillagers.shop.hired_many", result.spawnedCount());
			case NOT_TRUSTED -> Component.translatable("message.guardvillagers.shop.not_trusted");
			case LIMIT_REACHED -> Component.translatable("message.guardvillagers.shop.limit", GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER);
			case INSUFFICIENT_FUNDS -> Component.translatable("message.guardvillagers.shop.insufficient", cost);
			case SPAWN_FAILED -> Component.translatable("message.guardvillagers.shop.spawn_failed");
			case INTERNAL_ERROR -> Component.translatable("message.guardvillagers.shop.error");
		};
		this.player.sendOverlayMessage(message);
	}

	private void upgradeArmor() {
		GuardPlayerUpgrades current = GuardVillagersMod.getUpgradesView(this.player);
		if (current.getArmorLevel() >= GuardPlayerUpgrades.MAX_ARMOR_LEVEL) {
			this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.armor.maxed"));
			return;
		}
		int cost = current.getArmorUpgradeCost();
		if (!GuardEconomy.spendEmeraldBlocks(this.player, cost)) {
			this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.armor.need", cost));
			return;
		}
		GuardVillagersMod.getUpgrades(this.player).upgradeArmor();
		this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.armor.bought"));
	}

	private void upgradeWeapon() {
		GuardPlayerUpgrades current = GuardVillagersMod.getUpgradesView(this.player);
		if (current.getWeaponLevel() >= GuardPlayerUpgrades.MAX_WEAPON_LEVEL) {
			this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.weapon.maxed"));
			return;
		}
		int cost = current.getWeaponUpgradeCost();
		if (!GuardEconomy.spendEmeraldBlocks(this.player, cost)) {
			this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.weapon.need", cost));
			return;
		}
		GuardVillagersMod.getUpgrades(this.player).upgradeWeapon();
		this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.weapon.bought"));
	}

	private void upgradeHealing() {
		GuardPlayerUpgrades current = GuardVillagersMod.getUpgradesView(this.player);
		if (current.getSupportLevel() >= GuardPlayerUpgrades.MAX_SUPPORT_LEVEL) {
			this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.support.maxed"));
			return;
		}
		int cost = current.getHealingUpgradeCost();
		if (!GuardEconomy.spendEmeraldBlocks(this.player, cost)) {
			this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.support.need", cost));
			return;
		}
		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgrades(this.player);
		upgrades.unlockHealingUpgrade();
		this.player.sendOverlayMessage(Component.translatable("message.guardvillagers.shop.support.level_" + upgrades.getSupportLevel()));
	}

	public void refresh() {
		for (int i = 0; i < this.getContainerSize(); i++) {
			this.setItem(i, isInteractiveSlot(i) ? ItemStack.EMPTY : filler(i));
		}

		// Rendering must not create a saved upgrade row for players who never buy anything.
		GuardPlayerUpgrades upgrades = GuardVillagersMod.getUpgradesView(this.player);
		boolean creativeMode = this.player.getAbilities().instabuild;
		int ownedGuards = GuardOwnershipIndex.countOwnedGuards(this.player.level().getServer(), this.player.getUUID());
		int guardLimit = GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER;

		this.setItem(SLOT_INFO, card(Items.BOOK, key("info.title"), ChatFormatting.GOLD,
			line(key("info.armor", armorOdds(upgrades.getArmorDistribution())), ChatFormatting.GRAY),
			line(key("info.weapons", swordName(upgrades.getWeaponLevel()), bowName(upgrades.getWeaponLevel())), ChatFormatting.GRAY),
			line(key("info.support", healingText(upgrades.getSupportLevel()), shieldText(upgrades.getSupportLevel())), ChatFormatting.GRAY),
			line(key("info.reputation", GuardReputationManager.formatReputation(GuardReputationManager.getEffectiveReputation(this.player))),
				ChatFormatting.GRAY)));

		int guardCost = GuardHiring.getAdjustedGuardCost(this.player);
		this.setItem(SLOT_BUY_GUARD, card(Items.EMERALD_BLOCK, key("hire.title"), ChatFormatting.GREEN,
			costLine(guardCost, creativeMode, ownedGuards >= guardLimit),
			line(key("hire.owned", ownedGuards, guardLimit), ChatFormatting.GRAY),
			Component.empty(),
			line(key("hire.hint"), ChatFormatting.DARK_GRAY)));

		int armorLevel = upgrades.getArmorLevel();
		boolean armorMaxed = armorLevel >= GuardPlayerUpgrades.MAX_ARMOR_LEVEL;
		List<Component> armorLines = new ArrayList<>();
		armorLines.add(line(key("level", armorLevel, GuardPlayerUpgrades.MAX_ARMOR_LEVEL), ChatFormatting.GRAY));
		armorLines.add(costLine(upgrades.getArmorUpgradeCostForLevel(armorLevel), creativeMode, armorMaxed));
		armorLines.add(line(key("now", armorOdds(upgrades.getArmorDistribution())), ChatFormatting.WHITE));
		if (!armorMaxed) {
			GuardPlayerUpgrades next = upgrades.copy();
			next.upgradeArmor();
			armorLines.add(line(key("next", armorOdds(next.getArmorDistribution())), ChatFormatting.AQUA));
		}
		this.setItem(SLOT_UPGRADE_ARMOR, card(Items.IRON_CHESTPLATE, key("armor.title"), ChatFormatting.AQUA, armorLines.toArray(Component[]::new)));

		int weaponLevel = upgrades.getWeaponLevel();
		boolean weaponMaxed = weaponLevel >= GuardPlayerUpgrades.MAX_WEAPON_LEVEL;
		List<Component> weaponLines = new ArrayList<>();
		weaponLines.add(line(key("level", weaponLevel, GuardPlayerUpgrades.MAX_WEAPON_LEVEL), ChatFormatting.GRAY));
		weaponLines.add(costLine(upgrades.getWeaponUpgradeCostForLevel(weaponLevel), creativeMode, weaponMaxed));
		weaponLines.add(line(key("now", key("weapons", swordName(weaponLevel), bowName(weaponLevel))), ChatFormatting.WHITE));
		if (!weaponMaxed) {
			weaponLines.add(line(key("next", key("weapons", swordName(weaponLevel + 1), bowName(weaponLevel + 1))), ChatFormatting.RED));
		}
		this.setItem(SLOT_UPGRADE_WEAPON, card(Items.IRON_SWORD, key("weapon.title"), ChatFormatting.RED, weaponLines.toArray(Component[]::new)));

		int supportLevel = upgrades.getSupportLevel();
		boolean supportMaxed = supportLevel >= GuardPlayerUpgrades.MAX_SUPPORT_LEVEL;
		List<Component> supportLines = new ArrayList<>();
		supportLines.add(line(key("level", supportLevel, GuardPlayerUpgrades.MAX_SUPPORT_LEVEL), ChatFormatting.GRAY));
		supportLines.add(costLine(upgrades.getHealingUpgradeCost(), creativeMode, supportMaxed));
		supportLines.add(line(key("now", key("support", healingText(supportLevel), shieldText(supportLevel))), ChatFormatting.WHITE));
		if (!supportMaxed) {
			supportLines.add(line(key("next", key("support", healingText(supportLevel + 1), shieldText(supportLevel + 1))),
				ChatFormatting.LIGHT_PURPLE));
		}
		this.setItem(SLOT_UPGRADE_HEAL, card(Items.GOLDEN_APPLE, key("support.title"), ChatFormatting.LIGHT_PURPLE,
			supportLines.toArray(Component[]::new)));

		this.setItem(SLOT_STATUS, card(Items.COMPASS, key("status.title"), ChatFormatting.YELLOW,
			line(key("status.armor", armorLevel, GuardPlayerUpgrades.MAX_ARMOR_LEVEL), ChatFormatting.GRAY),
			line(key("status.weapon", weaponLevel, GuardPlayerUpgrades.MAX_WEAPON_LEVEL), ChatFormatting.GRAY),
			line(key("status.support", supportLevel, GuardPlayerUpgrades.MAX_SUPPORT_LEVEL), ChatFormatting.GRAY),
			line(key("status.hire_level", upgrades.getHireLevel()), ChatFormatting.GRAY)));
	}

	private static MutableComponent key(String suffix, Object... args) {
		return Component.translatable("shop.guardvillagers." + suffix, args);
	}

	/** Lore and names default to italic purple; every line sets its own color and turns italics off. */
	private static Component line(MutableComponent text, ChatFormatting color) {
		return text.withStyle(style -> style.withItalic(false).withColor(color));
	}

	private static Component costLine(int cost, boolean creativeMode, boolean maxed) {
		if (maxed) {
			return line(key("maxed"), ChatFormatting.GOLD);
		}
		return creativeMode ? line(key("cost.free"), ChatFormatting.GREEN) : line(key("cost", cost), ChatFormatting.YELLOW);
	}

	private static Component armorOdds(GuardPlayerUpgrades.ArmorDistribution d) {
		return key("armor.odds", d.leather(), d.iron(), d.gold(), d.diamond());
	}

	private static Component swordName(int weaponLevel) {
		return key("sword." + Math.max(0, Math.min(GuardPlayerUpgrades.MAX_WEAPON_LEVEL, weaponLevel)));
	}

	private static Component bowName(int weaponLevel) {
		return key("bow", Math.min(5, Math.max(1, weaponLevel + 1)));
	}

	private static Component healingText(int supportLevel) {
		return key("healing." + Math.max(0, Math.min(GuardPlayerUpgrades.MAX_SUPPORT_LEVEL, supportLevel)));
	}

	private static Component shieldText(int supportLevel) {
		return key(supportLevel >= 2 ? "shield.on" : "shield.off");
	}

	private static boolean isInteractiveSlot(int slot) {
		return slot == SLOT_INFO
			|| slot == SLOT_BUY_GUARD
			|| slot == SLOT_UPGRADE_ARMOR
			|| slot == SLOT_UPGRADE_WEAPON
			|| slot == SLOT_UPGRADE_HEAL
			|| slot == SLOT_STATUS;
	}

	private static ItemStack filler(int slot) {
		ItemStack stack = new ItemStack((slot % 2 == 0) ? Items.BLACK_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE);
		stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, ReferenceSortedSets.emptySet()));
		return stack;
	}

	private static ItemStack card(Item item, MutableComponent title, ChatFormatting titleColor, Component... lines) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, title.withStyle(style -> style.withItalic(false).withBold(true).withColor(titleColor)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(lines)));
		// Display items are not equipment: hide the vanilla "When on Chest: +6 Armor" style lines.
		stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT
			.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true)
			.withHidden(DataComponents.ENCHANTMENTS, true));
		return stack;
	}
}
