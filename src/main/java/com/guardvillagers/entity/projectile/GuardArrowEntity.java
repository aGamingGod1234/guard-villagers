package com.guardvillagers.entity.projectile;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

public class GuardArrowEntity extends ArrowEntity {
	public GuardArrowEntity(EntityType<? extends ArrowEntity> type, World world) {
		super(type, world);
	}

	public GuardArrowEntity(World world, LivingEntity owner, ItemStack stack, ItemStack shotFrom) {
		super(world, owner, stack, shotFrom);
	}

	@Override
	protected boolean canHit(Entity entity) {
		if (!super.canHit(entity)) {
			return false;
		}
		if (!(entity instanceof GuardEntity targetGuard)) {
			return true;
		}
		if (!(this.getOwner() instanceof GuardEntity shooterGuard)) {
			return true;
		}
		return !targetGuard.hasOwner() || !shooterGuard.isOwnedBy(targetGuard.getOwnerUuid());
	}

	@Override
	protected void onHit(LivingEntity target) {
		super.onHit(target);
		if (this.getEntityWorld() instanceof ServerWorld) {
			this.discard();
		}
	}
}
