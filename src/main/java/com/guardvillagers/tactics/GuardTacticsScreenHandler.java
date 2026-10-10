package com.guardvillagers.tactics;

import com.guardvillagers.GuardVillagersMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class GuardTacticsScreenHandler extends ChestMenu {
	private static final int TACTICS_SLOT_COUNT = 54;
	private final GuardTacticsInventory tacticsInventory;
	private final ServerPlayer owner;

	public GuardTacticsScreenHandler(int syncId, Inventory playerInventory) {
		this(syncId, playerInventory, new SimpleContainer(54), null, null);
	}

	public GuardTacticsScreenHandler(int syncId, Inventory playerInventory, ServerPlayer owner) {
		this(syncId, playerInventory, createServerInventory(owner), owner);
	}

	private GuardTacticsScreenHandler(int syncId, Inventory playerInventory, GuardTacticsInventory tacticsInventory, ServerPlayer owner) {
		this(syncId, playerInventory, tacticsInventory, tacticsInventory, owner);
	}

	private GuardTacticsScreenHandler(int syncId, Inventory playerInventory, Container inventory, GuardTacticsInventory tacticsInventory, ServerPlayer owner) {
		super(GuardVillagersMod.GUARD_TACTICS_SCREEN_HANDLER, syncId, playerInventory, inventory, 6);
		this.tacticsInventory = tacticsInventory;
		this.owner = owner;
	}

	private static GuardTacticsInventory createServerInventory(ServerPlayer owner) {
		if (owner == null) {
			throw new IllegalArgumentException("owner cannot be null for server GuardTacticsScreenHandler");
		}
		return new GuardTacticsInventory(owner);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void clicked(int slotIndex, int button, ClickType actionType, Player player) {
		if (this.owner == null || this.tacticsInventory == null) {
			super.clicked(slotIndex, button, actionType, player);
			return;
		}

		if (player != this.owner) {
			return;
		}

		if (!this.getCarried().isEmpty()) {
			return;
		}

		if (slotIndex >= 0 && slotIndex < TACTICS_SLOT_COUNT) {
			if (actionType != ClickType.PICKUP && actionType != ClickType.QUICK_MOVE) {
				return;
			}
			if (this.tacticsInventory.handleClick(slotIndex, button)) {
				this.tacticsInventory.refresh();
				this.broadcastChanges();
			}
			return;
		}

		if (slotIndex >= TACTICS_SLOT_COUNT || slotIndex == -999) {
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
		if (this.owner == null) {
			return true;
		}
		return player == this.owner;
	}

	public void refreshInventory() {
		if (this.tacticsInventory == null) {
			return;
		}
		this.tacticsInventory.refresh();
		this.broadcastChanges();
	}
}
