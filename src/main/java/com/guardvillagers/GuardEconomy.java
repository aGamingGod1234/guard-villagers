package com.guardvillagers;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GuardEconomy {
	private static final Logger LOGGER = LoggerFactory.getLogger(GuardVillagersMod.MOD_ID + "/economy");

	private GuardEconomy() {
	}

	public static int countEmeraldBlocks(Inventory inventory) {
		int total = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(Items.EMERALD_BLOCK)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	public static boolean spendEmeraldBlocks(ServerPlayer player, int amount) {
		if (amount < 0) {
			LOGGER.warn("Rejected negative emerald cost {} for {}", amount, player.getName().getString());
			return false;
		}
		if (amount == 0 || player.getAbilities().instabuild) {
			return true;
		}

		Inventory inventory = player.getInventory();
		int available = countEmeraldBlocks(inventory);
		if (available < amount) {
			return false;
		}

		int remaining = amount;
		int removed = 0;

		try {
			for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
				ItemStack stack = inventory.getItem(i);
				if (!stack.is(Items.EMERALD_BLOCK)) {
					continue;
				}
				int remove = Math.min(remaining, stack.getCount());
				stack.shrink(remove);
				remaining -= remove;
				removed += remove;
			}
			inventory.setChanged();
		} catch (RuntimeException exception) {
			LOGGER.error("Failed emerald debit for player {}", player.getName().getString(), exception);
			refundEmeraldBlocks(player, removed);
			return false;
		}

		if (remaining == 0) {
			return true;
		}

		LOGGER.error("Emerald debit drift: requested {}, removed {} for {}", amount, removed, player.getName().getString());
		refundEmeraldBlocks(player, removed);
		return false;
	}

	public static void refundEmeraldBlocks(ServerPlayer player, int amount) {
		if (amount <= 0) {
			return;
		}

		int remaining = amount;
		Inventory inventory = player.getInventory();
		while (remaining > 0) {
			ItemStack stack = new ItemStack(Items.EMERALD_BLOCK, Math.min(64, remaining));
			inventory.add(stack);
			if (!stack.isEmpty()) {
				player.drop(stack, false);
			}
			remaining -= Math.min(64, remaining);
		}
		inventory.setChanged();
	}
}
