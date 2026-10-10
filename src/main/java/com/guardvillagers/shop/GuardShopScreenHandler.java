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

/**
 * A chest-shaped button menu. The display stacks are never real items: no click is forwarded to vanilla slot logic,
 * so pickup, shift-move, number-key swap, drag, double-click collect, clone and throw cannot take or duplicate them,
 * and the player's own inventory is frozen while the shop is open.
 */
public class GuardShopScreenHandler extends ChestMenu {
	private static final int SHOP_SLOT_COUNT = 27;
	/** Each purchase can run a spawn search; cap how often one menu can trigger it (a vanilla client clicks far slower). */
	private static final int ACTION_COOLDOWN_TICKS = 4;
	private static final Logger LOGGER = LoggerFactory.getLogger("guardvillagers/shop");

	private final GuardShopInventory shopInventory;
	private final ServerPlayer owner;
	private int lastActionTick = Integer.MIN_VALUE;

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
		if (player != this.owner || slotIndex < 0 || slotIndex >= SHOP_SLOT_COUNT) {
			return;
		}
		if ((actionType != ContainerInput.PICKUP && actionType != ContainerInput.QUICK_MOVE) || (button != 0 && button != 1)) {
			return;
		}
		int now = this.owner.level().getServer().getTickCount();
		if (now >= this.lastActionTick && now - this.lastActionTick < ACTION_COOLDOWN_TICKS) {
			return;
		}
		this.lastActionTick = now;

		try {
			if (this.shopInventory.handleClick(slotIndex, actionType == ContainerInput.QUICK_MOVE)) {
				// The packet handler resyncs the client's predicted slot/cursor changes right after this returns.
				this.shopInventory.refresh();
			}
		} catch (RuntimeException exception) {
			LOGGER.error("Shop click failed for {} on slot {}", this.owner.getName().getString(), slotIndex, exception);
		}
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
		return player == this.owner && this.owner.isAlive();
	}
}
