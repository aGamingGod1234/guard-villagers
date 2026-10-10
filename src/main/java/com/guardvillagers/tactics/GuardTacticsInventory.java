package com.guardvillagers.tactics;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class GuardTacticsInventory extends SimpleContainer {
	private static final int SIZE = 54;

	private final ServerPlayer owner;

	public GuardTacticsInventory(ServerPlayer owner) {
		super(SIZE);
		this.owner = Objects.requireNonNull(owner, "owner");
		this.refresh();
	}

	@Override
	public boolean stillValid(Player player) {
		return player == this.owner;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return false;
	}

	public boolean handleClick(int slot, int button) {
		return false;
	}

	public void refresh() {
		for (int i = 0; i < this.getContainerSize(); i++) {
			this.setItem(i, ItemStack.EMPTY);
		}
	}
}
