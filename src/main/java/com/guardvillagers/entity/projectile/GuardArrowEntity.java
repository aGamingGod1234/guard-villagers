package com.guardvillagers.entity.projectile;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

public class GuardArrowEntity extends Arrow {
	public GuardArrowEntity(EntityType<? extends Arrow> type, Level world) {
		super(type, world);
	}

	public GuardArrowEntity(Level world, LivingEntity owner, ItemStack stack, ItemStack shotFrom) {
		super(world, owner, stack, shotFrom);
	}

	@Override
	protected void onHitEntity(EntityHitResult entityHitResult) {
		Entity hitEntity = entityHitResult.getEntity();
		Entity owner = this.getOwner();
		if (owner instanceof GuardEntity shooterGuard
				&& (shooterGuard.isAlly(hitEntity) || this.isBystanderPlayer(shooterGuard, hitEntity))
				&& !this.isActiveHostileOwnerTarget(shooterGuard, hitEntity)) {
			if (this.level() instanceof ServerLevel) {
				this.discard();
			}
			return;
		}
		super.onHitEntity(entityHitResult);
	}

	/**
	 * A player the guard is not fighting. Unowned village guards treat every player as a non-ally
	 * for targeting purposes, so without this a village bowman could hit a bystander standing in
	 * the line of fire.
	 */
	private boolean isBystanderPlayer(GuardEntity shooterGuard, Entity hitEntity) {
		return hitEntity instanceof Player && shooterGuard.getTarget() != hitEntity;
	}

	private boolean isActiveHostileOwnerTarget(GuardEntity shooterGuard, Entity hitEntity) {
		return hitEntity instanceof ServerPlayer player
				&& shooterGuard.getOwnerUuid() != null
				&& shooterGuard.getOwnerUuid().equals(player.getUUID())
				&& shooterGuard.getTarget() == hitEntity;
	}

	@Override
	protected void doPostHurtEffects(LivingEntity target) {
		super.doPostHurtEffects(target);
		if (this.level() instanceof ServerLevel) {
			this.discard();
		}
	}
}
