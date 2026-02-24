package com.guardvillagers;

import com.guardvillagers.data.GuardUpgradeState;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.FormationType;
import com.guardvillagers.item.GuardWhistleItem;
import com.guardvillagers.shop.GuardShopScreenHandler;
import com.guardvillagers.tactics.GuardTacticsScreenHandler;
import com.guardvillagers.village.VillageManagerHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

public class GuardVillagersMod implements ModInitializer {
	public static final String MOD_ID = "guardvillagers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int FOLLOW_DISTANCE = 64;

	public static final EntityType<GuardEntity> GUARD_ENTITY_TYPE = Registry.register(
		Registries.ENTITY_TYPE,
		id("guard"),
		EntityType.Builder.create(GuardEntity::new, SpawnGroup.CREATURE)
			.dimensions(0.6F, 1.95F)
			.maxTrackingRange(10)
			.build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, id("guard")))
	);

	public static final Item GUARD_WHISTLE = Registry.register(
		Registries.ITEM,
		id("guard_whistle"),
		new GuardWhistleItem(new Item.Settings()
			.registryKey(RegistryKey.of(RegistryKeys.ITEM, id("guard_whistle")))
			.maxCount(1))
	);

	public static final net.minecraft.item.ItemGroup GUARD_KIT_GROUP = Registry.register(
		Registries.ITEM_GROUP,
		id("guard_kit"),
		FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.guardvillagers.guard_kit"))
			.icon(() -> new ItemStack(GUARD_WHISTLE))
			.entries((context, entries) -> {
				entries.add(GUARD_WHISTLE);
				entries.add(Items.EMERALD);
				entries.add(Items.WOODEN_SWORD);
				entries.add(Items.STONE_SWORD);
				entries.add(Items.GOLDEN_SWORD);
				entries.add(Items.IRON_SWORD);
				entries.add(Items.DIAMOND_SWORD);
				entries.add(Items.NETHERITE_SWORD);
				entries.add(Items.BOW);
				entries.add(Items.ARROW);
				entries.add(Items.LEATHER_HELMET);
				entries.add(Items.LEATHER_CHESTPLATE);
				entries.add(Items.LEATHER_LEGGINGS);
				entries.add(Items.LEATHER_BOOTS);
				entries.add(Items.CHAINMAIL_HELMET);
				entries.add(Items.CHAINMAIL_CHESTPLATE);
				entries.add(Items.CHAINMAIL_LEGGINGS);
				entries.add(Items.CHAINMAIL_BOOTS);
				entries.add(Items.GOLDEN_HELMET);
				entries.add(Items.GOLDEN_CHESTPLATE);
				entries.add(Items.GOLDEN_LEGGINGS);
				entries.add(Items.GOLDEN_BOOTS);
				entries.add(Items.IRON_HELMET);
				entries.add(Items.IRON_CHESTPLATE);
				entries.add(Items.IRON_LEGGINGS);
				entries.add(Items.IRON_BOOTS);
				entries.add(Items.DIAMOND_HELMET);
				entries.add(Items.DIAMOND_CHESTPLATE);
				entries.add(Items.DIAMOND_LEGGINGS);
				entries.add(Items.DIAMOND_BOOTS);
				entries.add(Items.NETHERITE_HELMET);
				entries.add(Items.NETHERITE_CHESTPLATE);
				entries.add(Items.NETHERITE_LEGGINGS);
				entries.add(Items.NETHERITE_BOOTS);
			})
			.build()
	);

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(GUARD_ENTITY_TYPE, GuardEntity.createAttributes());
		registerCommands();
		registerEvents();
		LOGGER.info("Guard Villagers initialized");
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	public static GuardPlayerUpgrades getUpgrades(ServerPlayerEntity player) {
		return getUpgrades(player.getCommandSource().getServer(), player.getUuid());
	}

	public static GuardPlayerUpgrades getUpgrades(ServerWorld world, UUID playerUuid) {
		return getUpgrades(world.getServer(), playerUuid);
	}

	public static GuardPlayerUpgrades getUpgrades(MinecraftServer server, UUID playerUuid) {
		GuardUpgradeState state = server.getOverworld().getPersistentStateManager().getOrCreate(GuardUpgradeState.TYPE);
		return state.getOrCreate(playerUuid);
	}

	public static float getHealingAmount(ServerWorld world, UUID ownerUuid) {
		if (ownerUuid == null) {
			return 1.0F;
		}
		return getUpgrades(world, ownerUuid).getHealingPerCycle();
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register(this::registerGuardCommands);
	}

	private void registerGuardCommands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
		dispatcher.register(CommandManager.literal("guards")
			.then(CommandManager.literal("shop")
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
					openShop(player);
					return Command.SINGLE_SUCCESS;
				}))
			.then(CommandManager.literal("tactics")
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
					openTacticsScreen(player);
					return Command.SINGLE_SUCCESS;
				}))
			.then(CommandManager.literal("stay")
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
					int updated = setStance(player, true);
					context.getSource().sendFeedback(() -> Text.literal("Ordered " + updated + " guards to stay."), false);
					return updated;
				}))
			.then(CommandManager.literal("follow")
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
					int updated = setStance(player, false);
					context.getSource().sendFeedback(() -> Text.literal("Ordered " + updated + " guards to follow."), false);
					return updated;
				}))
			.then(CommandManager.literal("behavior")
				.then(CommandManager.literal("perimeter").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.PERIMETER)))
				.then(CommandManager.literal("bodyguard").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.BODYGUARD)))
				.then(CommandManager.literal("crowd_control").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.CROWD_CONTROL)))
				.then(CommandManager.literal("offensive").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.OFFENSIVE)))
				.then(CommandManager.literal("defensive").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.DEFENSIVE))))
			.then(CommandManager.literal("formation")
				.then(CommandManager.literal("line").executes(context -> setFormation(context.getSource().getPlayerOrThrow(), FormationType.LINE)))
				.then(CommandManager.literal("wedge").executes(context -> setFormation(context.getSource().getPlayerOrThrow(), FormationType.WEDGE)))
				.then(CommandManager.literal("circle").executes(context -> setFormation(context.getSource().getPlayerOrThrow(), FormationType.CIRCLE))))
			.then(CommandManager.literal("zone")
				.then(CommandManager.argument("radius", IntegerArgumentType.integer(8, 128))
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
						int radius = IntegerArgumentType.getInteger(context, "radius");
						int updated = assignZone(player, radius);
						context.getSource().sendFeedback(() -> Text.literal("Assigned home zone to " + updated + " guards."), false);
						return updated;
					})))
			.then(CommandManager.literal("count")
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
					int count = countOwnedGuards(context.getSource().getServer(), player.getUuid());
					context.getSource().sendFeedback(() -> Text.literal("You own " + count + " guards."), false);
					return count;
				}))
		);
	}

	private static void openShop(ServerPlayerEntity player) {
		try {
			player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, playerInventory, ignoredPlayer) -> new GuardShopScreenHandler(syncId, playerInventory, player),
				Text.translatable("screen.guardvillagers.shop")
			));
		} catch (RuntimeException exception) {
			LOGGER.error("Failed to open guard shop for {}", player.getName().getString(), exception);
			player.sendMessage(Text.literal("Could not open the guard shop. Check server logs."), false);
		}
	}

	public static void openTacticsScreen(ServerPlayerEntity player) {
		try {
			player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, playerInventory, ignoredPlayer) -> new GuardTacticsScreenHandler(syncId, playerInventory, player),
				Text.translatable("screen.guardvillagers.tactics")
			));
		} catch (RuntimeException exception) {
			LOGGER.error("Failed to open tactics screen for {}", player.getName().getString(), exception);
			player.sendMessage(Text.literal("Could not open tactics screen. Check server logs."), false);
		}
	}

	public static boolean purchaseGuard(ServerPlayerEntity player) {
		if (!GuardReputationManager.isTrustedByGuards(player.getEntityWorld(), player.getUuid(), player.getBlockPos())) {
			player.sendMessage(Text.literal("Village trust is too low to hire guards."), true);
			return false;
		}

		GuardPlayerUpgrades upgrades = getUpgrades(player);
		int cost = GuardReputationManager.getAdjustedGuardCost(player, upgrades.getGuardCost());
		if (!GuardEconomy.spendEmeraldBlocks(player, cost)) {
			return false;
		}

		ServerWorld world = player.getEntityWorld();
		try {
			if (trySpawnPurchasedGuard(world, player, upgrades)) {
				return true;
			}
			GuardEconomy.refundEmeraldBlocks(player, cost);
			LOGGER.warn("Guard purchase failed to spawn for {} in world {}", player.getName().getString(), world.getRegistryKey().getValue());
			return false;
		} catch (RuntimeException exception) {
			GuardEconomy.refundEmeraldBlocks(player, cost);
			LOGGER.error("Guard purchase crashed for {}", player.getName().getString(), exception);
			return false;
		}
	}

	public static boolean spendEmeraldBlocks(ServerPlayerEntity player, int amount) {
		return GuardEconomy.spendEmeraldBlocks(player, amount);
	}

	private static boolean trySpawnPurchasedGuard(ServerWorld world, ServerPlayerEntity player, GuardPlayerUpgrades upgrades) {
		double[][] offsets = {
			{1.0D, 1.0D},
			{-1.0D, -1.0D},
			{2.0D, 0.0D},
			{0.0D, 2.0D},
			{-2.0D, 0.0D},
			{0.0D, -2.0D},
			{3.0D, 1.0D},
			{-3.0D, -1.0D}
		};

		for (double[] offset : offsets) {
			GuardEntity guard = GUARD_ENTITY_TYPE.create(world, SpawnReason.EVENT);
			if (guard == null) {
				continue;
			}
			guard.refreshPositionAndAngles(player.getX() + offset[0], player.getY(), player.getZ() + offset[1], player.getYaw(), 0.0F);
			guard.setOwnerUuid(player.getUuid());
			guard.setStaying(false);
			guard.applyPurchasedLoadout(world, upgrades);
			guard.setSquadLeader(false);
			if (world.spawnEntity(guard)) {
				return true;
			}
		}
		return false;
	}

	private static int setStance(ServerPlayerEntity player, boolean staying) {
		int changed = 0;
		for (ServerWorld world : player.getCommandSource().getServer().getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof GuardEntity guard && guard.isOwnedBy(player.getUuid())) {
					guard.setStaying(staying);
					if (!staying) {
						guard.clearCombatTarget();
					}
					changed++;
				}
			}
		}
		return changed;
	}

	private static int setBehavior(ServerPlayerEntity player, GuardBehavior behavior) {
		int changed = 0;
		for (ServerWorld world : player.getCommandSource().getServer().getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof GuardEntity guard && guard.isOwnedBy(player.getUuid())) {
					guard.setBehavior(behavior);
					guard.clearCombatTarget();
					changed++;
				}
			}
		}
		player.sendMessage(Text.literal("Set " + changed + " guards to " + behavior.name().toLowerCase() + "."), true);
		return changed;
	}

	private static int setFormation(ServerPlayerEntity player, FormationType formationType) {
		int changed = 0;
		for (ServerWorld world : player.getCommandSource().getServer().getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof GuardEntity guard && guard.isOwnedBy(player.getUuid())) {
					guard.setFormationType(formationType);
					guard.clearCombatTarget();
					changed++;
				}
			}
		}
		player.sendMessage(Text.literal("Set formation to " + formationType.name().toLowerCase() + " for " + changed + " guards."), true);
		return changed;
	}

	private static int assignZone(ServerPlayerEntity player, int radius) {
		int changed = 0;
		BlockPos home = player.getBlockPos();
		for (GuardEntity guard : player.getEntityWorld().getEntitiesByClass(
			GuardEntity.class,
			player.getBoundingBox().expand(96.0D),
			entity -> entity.isOwnedBy(player.getUuid()))
		) {
			guard.setHome(home, radius);
			guard.clearCombatTarget();
			changed++;
		}
		return changed;
	}

	private static int countOwnedGuards(MinecraftServer server, UUID ownerUuid) {
		int count = 0;
		for (ServerWorld world : server.getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof GuardEntity guard && guard.isOwnedBy(ownerUuid)) {
					count++;
				}
			}
		}
		return count;
	}

	private void registerEvents() {
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer) || !(entity instanceof LivingEntity livingTarget)) {
				return ActionResult.PASS;
			}

			ServerWorld serverWorld = serverPlayer.getEntityWorld();
			if (livingTarget instanceof VillagerEntity || livingTarget instanceof IronGolemEntity) {
				GuardReputationManager.recordVillagerHarm(serverWorld, serverPlayer.getUuid());
			}
			if (livingTarget instanceof GuardEntity) {
				GuardReputationManager.recordGuardHarm(serverWorld, serverPlayer.getUuid());
			}

			if (livingTarget instanceof GuardEntity targetGuard && targetGuard.isOwnedBy(serverPlayer.getUuid())) {
				return ActionResult.PASS;
			}

			try {
				Box range = serverPlayer.getBoundingBox().expand(FOLLOW_DISTANCE);
				List<GuardEntity> nearbyGuards = serverPlayer.getEntityWorld().getEntitiesByClass(
					GuardEntity.class,
					range,
					guard -> guard.isOwnedBy(serverPlayer.getUuid())
				);
				for (GuardEntity guard : nearbyGuards) {
					guard.setPriorityTarget(livingTarget);
				}
			} catch (RuntimeException exception) {
				LOGGER.error("Failed to dispatch manual target from {}", serverPlayer.getName().getString(), exception);
			}

			return ActionResult.PASS;
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
				return ActionResult.PASS;
			}
			if (entity instanceof VillagerEntity villager) {
				GuardReputationManager.recordTradeInteraction(serverPlayer, villager);
			}
			return ActionResult.PASS;
		});

		ServerTickEvents.END_WORLD_TICK.register(world -> {
			try {
				VillageManagerHandler.maintainVillageGuards(world);
				if (world.getTime() % 200 == 0) {
					for (ServerPlayerEntity player : world.getPlayers()) {
						boolean golemAggro = !world.getEntitiesByClass(
							IronGolemEntity.class,
							player.getBoundingBox().expand(24.0D),
							golem -> golem.getTarget() == player
						).isEmpty();
						if (golemAggro) {
							GuardReputationManager.applyReputationDelta(world, player.getUuid(), -2);
						}
					}
				}
			} catch (RuntimeException exception) {
				LOGGER.error("Village manager tick failed in world {}", world.getRegistryKey().getValue(), exception);
			}
		});
	}
}
