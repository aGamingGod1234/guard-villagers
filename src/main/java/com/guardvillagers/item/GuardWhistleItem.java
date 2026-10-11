package com.guardvillagers.item;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.tactics.GuardTacticsMenus;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public final class GuardWhistleItem extends Item {
	public GuardWhistleItem(Properties settings) {
		super(settings);
	}

	@Override
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		if (world.isClientSide() || !(user instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}

		if (player.isShiftKeyDown()) {
			this.rallyNearbyLeaders(player);
			return InteractionResult.SUCCESS;
		}

		GuardTacticsMenus.openTactics(player);
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player user, LivingEntity entity, InteractionHand hand) {
		if (!(entity instanceof GuardEntity guard) || !(user instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}
		if (!guard.isOwnedBy(player.getUUID())) {
			player.sendOverlayMessage(Component.translatable("message.guardvillagers.whistle.not_owner"));
			return InteractionResult.SUCCESS;
		}

		if (player.isShiftKeyDown()) {
			int nextRadius = Math.min(96, Math.max(16, guard.getPatrolRadius() + 8));
			guard.setFollowOverride(false);
			guard.clearCombatTarget();
			if (guard.getHome().isEmpty()) {
				guard.setHome(guard.blockPosition(), nextRadius);
			} else {
				guard.setPatrolRadius(nextRadius);
			}
			player.sendOverlayMessage(Component.translatable("message.guardvillagers.whistle.patrol_radius", nextRadius));
		} else {
			int radius = Math.max(24, guard.getPatrolRadius());
			guard.setFollowOverride(false);
			guard.clearCombatTarget();
			guard.setHome(guard.blockPosition(), radius);
			player.sendOverlayMessage(Component.translatable("message.guardvillagers.whistle.home_set"));
		}
		return InteractionResult.SUCCESS;
	}

	private void rallyNearbyLeaders(ServerPlayer caller) {
		ServerLevel world = caller.level();
		AABB rallyRange = caller.getBoundingBox().inflate(64.0D);
		List<GuardEntity> leaders = world.getEntitiesOfClass(
			GuardEntity.class,
			rallyRange,
			guard -> guard.isSquadLeader() && guard.isOwnedBy(caller.getUUID())
		);

		LivingEntity threat = caller.getLastHurtByMob();
		for (GuardEntity leader : leaders) {
			leader.rallyTo(caller.blockPosition(), 240);
			if (threat != null && threat.isAlive()) {
				leader.setPriorityTarget(threat);
			}
		}
		caller.sendOverlayMessage(Component.translatable("message.guardvillagers.whistle.rally", leaders.size()));
	}
}
