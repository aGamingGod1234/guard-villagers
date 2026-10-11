package com.guardvillagers;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiController;
import com.guardvillagers.entity.ai.GuardHuntManager;
import com.guardvillagers.entity.ai.GuardMovementSlotResolver;
import com.guardvillagers.navigation.SquadRouteCache;
import com.guardvillagers.tactics.GuardTacticsMenus;
import com.guardvillagers.village.GuardBellRally;
import com.guardvillagers.village.VillageManagerHandler;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Server-side event hooks: owner-defense alerts, reputation side effects, periodic world work and cleanup. */
final class GuardEvents {
	private static final int GOLEM_AGGRO_CHECK_INTERVAL_TICKS = 200;
	private static final double GOLEM_AGGRO_RADIUS = 24.0D;
	private static final int GOLEM_AGGRO_LEGACY_DELTA = -2;
	private static final double MIN_ALERT_RANGE = 8.0D;

	private GuardEvents() {
	}

	static void register() {
		GuardTacticsManager.register();
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(entity instanceof LivingEntity target)) {
				return InteractionResult.PASS;
			}
			if (!(target instanceof GuardEntity)) {
				alertGuardsOfAttackingOwner(serverPlayer, target);
			}
			return InteractionResult.PASS;
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (!(entity.level() instanceof ServerLevel) || !(source.getEntity() instanceof LivingEntity attacker)) {
				return;
			}
			if (entity instanceof ServerPlayer ownerVictim) {
				alertGuardsOfDamagedOwner(ownerVictim, attacker);
			}
			if (damageTaken > 0.0F) {
				recordVillageHarm(entity, source.getEntity());
			}
			if (attacker instanceof ServerPlayer ownerAttacker && !(entity instanceof GuardEntity)) {
				alertGuardsOfAttackingOwner(ownerAttacker, entity);
			}
		});
		// Fabric's AFTER_DAMAGE deliberately omits lethal hits.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> recordVillageHarm(entity, source.getEntity()));

		ServerTickEvents.END_LEVEL_TICK.register(GuardEvents::onLevelTick);
		ServerTickEvents.END_SERVER_TICK.register(GuardTacticsMenus::tickOpenRosters);

		ServerPlayerEvents.LEAVE.register(player -> {
			GuardHuntManager.cancel(player.getUUID());
			GuardDebugManager.forget(player.getUUID());
			GuardTacticsMenus.forget(player.getUUID());
		});

		// Static caches outlive an integrated server; drop them so the next world starts clean.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			GuardDebugManager.clearSessionState();
			GuardTacticsMenus.clearSessionState();
			GuardReputationManager.clearSessionState();
			GuardOwnershipIndex.clearSessionState();
			VillageManagerHandler.clearSessionState();
			GuardBellRally.clearSessionState();
			GuardEntity.clearSessionState();
			GuardAiController.clearSessionState();
			GuardHuntManager.clear();
			GuardMovementSlotResolver.clearSessionState();
			SquadRouteCache.clear();
		});
	}

	private static void onLevelTick(ServerLevel world) {
		try {
			GuardHuntManager.tick(world);
			GuardDebugManager.tick(world);
			VillageManagerHandler.maintainVillageGuards(world);
			if (world.dimension() == Level.OVERWORLD) {
				GuardReputationManager.tickDecay(world);
			}
			if (world.getGameTime() % GOLEM_AGGRO_CHECK_INTERVAL_TICKS == 0) {
				for (ServerPlayer player : world.players()) {
					boolean golemAggro = !world.getEntitiesOfClass(
							IronGolem.class,
							player.getBoundingBox().inflate(GOLEM_AGGRO_RADIUS),
							golem -> golem.getTarget() == player).isEmpty();
					if (golemAggro) {
						GuardReputationManager.applyReputationDelta(world, player.getUUID(), GOLEM_AGGRO_LEGACY_DELTA);
					}
				}
			}
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.error("Guard level tick failed in {}", world.dimension().identifier(), exception);
		}
	}

	/** The owner hit something: nearby owned guards join in, unless the target is one of their own or protected by PvP rules. */
	static void alertGuardsOfAttackingOwner(ServerPlayer owner, LivingEntity target) {
		if (target instanceof GuardEntity guardTarget && guardTarget.isOwnedBy(owner.getUUID())) {
			return;
		}
		if (!canOwnerDirectGuardAt(owner, target)) {
			return;
		}
		try {
			GuardHuntManager.punch(owner, target);
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.error("Failed owner-attack alert for {}", owner.getName().getString(), exception);
		}
	}

	private static void recordVillageHarm(LivingEntity victim, net.minecraft.world.entity.Entity attacker) {
		if ((victim instanceof Villager || victim instanceof IronGolem)
				&& victim.level() instanceof ServerLevel world && attacker instanceof ServerPlayer player) {
			GuardReputationManager.recordVillagerHarm(world, player.getUUID());
		}
	}

	/** Something hurt the owner: nearby owned guards retaliate. */
	private static void alertGuardsOfDamagedOwner(ServerPlayer owner, LivingEntity attacker) {
		try {
			long tick = owner.level().getGameTime();
			for (GuardEntity guard : GuardOwnershipIndex.getOwnedGuards(owner.level().getServer(), owner.getUUID())) {
				if (guard.isAlive() && !guard.isAlly(attacker) && isWithinAlertRange(guard, attacker)) {
					guard.receiveOwnerDamagedAlert(attacker, tick);
				}
			}
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.error("Failed owner-defense alert for {}", owner.getName().getString(), exception);
		}
	}

	private static boolean isWithinAlertRange(GuardEntity guard, LivingEntity target) {
		if (guard.level() != target.level()) {
			return false;
		}
		double range = Math.max(MIN_ALERT_RANGE, guard.getAttributeValue(Attributes.FOLLOW_RANGE));
		return guard.distanceToSqr(target) <= range * range;
	}

	private static boolean canOwnerDirectGuardAt(ServerPlayer owner, LivingEntity target) {
		if (target == owner) {
			return false;
		}
		if (target instanceof Player targetPlayer) {
			return owner.level().isPvpAllowed() && owner.canHarmPlayer(targetPlayer);
		}
		return true;
	}
}
