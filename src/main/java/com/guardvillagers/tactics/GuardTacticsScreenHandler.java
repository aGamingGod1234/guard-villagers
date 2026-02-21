package com.guardvillagers.tactics;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

public final class GuardTacticsScreenHandler extends GenericContainerScreenHandler {
	private final GuardTacticsInventory tacticsInventory;
	private final ServerPlayerEntity owner;

	public GuardTacticsScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
		this(syncId, playerInventory, new GuardTacticsInventory(owner), owner);
	}

	private GuardTacticsScreenHandler(int syncId, PlayerInventory playerInventory, GuardTacticsInventory tacticsInventory, ServerPlayerEntity owner) {
		super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, tacticsInventory, 6);
		this.tacticsInventory = tacticsInventory;
		this.owner = owner;
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
		if (player != this.owner) {
			return;
		}

		if (!this.getCursorStack().isEmpty()) {
			this.setCursorStack(ItemStack.EMPTY);
		}

		if (slotIndex >= 0 && slotIndex < 54) {
			if (actionType != SlotActionType.PICKUP && actionType != SlotActionType.QUICK_MOVE) {
				return;
			}
			if (this.tacticsInventory.handleClick(slotIndex, button)) {
				this.tacticsInventory.refresh();
				this.sendContentUpdates();
			}
			return;
		}

		if (slotIndex >= 54 || slotIndex == -999) {
			return;
		}

		super.onSlotClick(slotIndex, button, actionType, player);
	}

	@Override
	public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
		return false;
	}

	@Override
	public boolean canInsertIntoSlot(Slot slot) {
		return false;
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return player == this.owner;
	}
}
