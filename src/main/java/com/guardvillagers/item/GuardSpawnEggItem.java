package com.guardvillagers.item;

import com.guardvillagers.GuardVillagersMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

public class GuardSpawnEggItem extends SpawnEggItem {
	public GuardSpawnEggItem(Properties settings) {
		super(settings);
	}

	@Override
	public EntityType<?> getType(ItemStack stack) {
		return GuardVillagersMod.GUARD_ENTITY_TYPE;
	}
}
