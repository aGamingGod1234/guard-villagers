package com.guardvillagers.item;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;

public class GuardSpawnEggItem extends SpawnEggItem {
	public GuardSpawnEggItem(Settings settings) {
		super(settings);
	}

	@Override
	public EntityType<?> getEntityType(ItemStack stack) {
		return GuardVillagersMod.GUARD_ENTITY_TYPE;
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		if (!(context.getWorld() instanceof ServerWorld world)) {
			return ActionResult.SUCCESS;
		}

		GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(world, SpawnReason.SPAWN_ITEM_USE);
		if (guard == null) {
			return ActionResult.FAIL;
		}

		BlockPos spawnBase = context.getBlockPos().offset(context.getSide());
		BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnBase);
		guard.refreshPositionAndAngles(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D, context.getPlayerYaw(), 0.0F);
		guard.applyNaturalLoadout(world);
		if (!world.spawnEntity(guard)) {
			return ActionResult.FAIL;
		}

		ItemStack stack = context.getStack();
		if (context.getPlayer() == null || !context.getPlayer().getAbilities().creativeMode) {
			stack.decrement(1);
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		return super.use(world, user, hand);
	}
}
