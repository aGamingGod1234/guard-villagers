package com.guardvillagers.shop;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GuardShopScreenHandler extends ChestMenu {
	private static final int SHOP_SLOT_COUNT = 27;
	private static final Logger LOGGER = LoggerFactory.getLogger("guardvillagers/shop");

	private final GuardShopInventory shopInventory;
	private final ServerPlayer owner;

	public GuardShopScreenHandler(int syncId, Inventory playerInventory, ServerPlayer owner) {
		this(syncId, playerInventory, new GuardShopInventory(owner), owner);
	}

	private GuardShopScreenHandler(int syncId, Inventory playerInventory, GuardShopInventory shopInventory, ServerPlayer owner) {
		super(MenuType.GENERIC_9x3, syncId, playerInventory, shopInventory, 3);
		this.shopInventory = shopInventory;
		this.owner = owner;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void clicked(int slotIndex, int button, ContainerInput actionType, Player player) {
		if (player != this.owner) {
			return;
		}

		if (!this.getCarried().isEmpty()) {
			return;
		}

		if (slotIndex >= 0 && slotIndex < SHOP_SLOT_COUNT) {
			if (actionType != ContainerInput.PICKUP && actionType != ContainerInput.QUICK_MOVE) {
				return;
			}

			try {
				boolean bulkPurchase = actionType == ContainerInput.QUICK_MOVE;
				if (this.shopInventory.handleClick(slotIndex, bulkPurchase)) {
					this.shopInventory.refresh();
					this.broadcastChanges();
				}
			} catch (RuntimeException exception) {
				LOGGER.error("Shop click failed for {} on slot {}", this.owner.getName().getString(), slotIndex, exception);
			}
			return;
		}

		if (slotIndex >= SHOP_SLOT_COUNT || slotIndex == -999) {
			return;
		}

		super.clicked(slotIndex, button, actionType, player);
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return false;
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return false;
	}

	@Override
	public boolean stillValid(Player player) {
		return player == this.owner;
	}
}
