package com.guardvillagers.entity.projectile;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;

public class GuardArrowEntity extends ArrowEntity {
	public GuardArrowEntity(EntityType<? extends ArrowEntity> type, World world) {
		super(type, world);
	}

	public GuardArrowEntity(World world, LivingEntity owner, ItemStack stack, ItemStack shotFrom) {
		super(world, owner, stack, shotFrom);
	}

	@Override
	protected void onEntityHit(EntityHitResult entityHitResult) {
		Entity hitEntity = entityHitResult.getEntity();
		Entity owner = this.getOwner();
		if (owner instanceof GuardEntity shooterGuard) {
			// Don't hit the guard's owner player
			if (shooterGuard.getOwnerUuid() != null && hitEntity.getUuid().equals(shooterGuard.getOwnerUuid())) {
				return;
			}
			// Don't hit allied guards with the same owner
			if (hitEntity instanceof GuardEntity hitGuard
					&& shooterGuard.getOwnerUuid() != null
					&& shooterGuard.getOwnerUuid().equals(hitGuard.getOwnerUuid())) {
				return;
			}
		}
		super.onEntityHit(entityHitResult);
	}
}
