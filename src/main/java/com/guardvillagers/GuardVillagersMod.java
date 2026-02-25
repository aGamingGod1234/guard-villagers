package com.guardvillagers;

import com.guardvillagers.data.GuardUpgradeState;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.FormationType;
import com.guardvillagers.entity.GuardRole;
import com.guardvillagers.item.GuardSpawnEggItem;
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
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.dispenser.ItemDispenserBehavior;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.TypedEntityData;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GuardVillagersMod implements ModInitializer {
	public static final String MOD_ID = "guardvillagers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int FOLLOW_DISTANCE = 64;
	private static final int DEBUG_SCAN_RANGE = 96;
	private static final int DEBUG_TEXT_UPDATE_INTERVAL = 10;
	private static final int DEBUG_PARTICLE_INTERVAL = 20;
	private static final String DEBUG_PREFIX = "[DBG] ";
	private static final Set<UUID> DEBUG_PLAYERS = ConcurrentHashMap.newKeySet();

	public enum GuardPurchaseResult {
		SUCCESS,
		NOT_TRUSTED,
		INSUFFICIENT_FUNDS,
		SPAWN_FAILED,
		INTERNAL_ERROR
	}

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

	public static final Item GUARD_SPAWN_EGG = Registry.register(
		Registries.ITEM,
		id("guard_spawn_egg"),
		new GuardSpawnEggItem(new Item.Settings()
			.registryKey(RegistryKey.of(RegistryKeys.ITEM, id("guard_spawn_egg")))
			.component(DataComponentTypes.ENTITY_DATA, TypedEntityData.create(GUARD_ENTITY_TYPE, new NbtCompound())))
	);

	public static final net.minecraft.item.ItemGroup GUARD_KIT_GROUP = Registry.register(
		Registries.ITEM_GROUP,
		id("guards"),
		FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.guardvillagers.guards"))
			.icon(() -> new ItemStack(GUARD_SPAWN_EGG))
			.entries((context, entries) -> {
				entries.add(GUARD_SPAWN_EGG);
				entries.add(GUARD_WHISTLE);
				entries.add(Items.EMERALD);
			})
			.build()
	);

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(GUARD_ENTITY_TYPE, GuardEntity.createAttributes());
		registerDispenserBehavior();
		registerCommands();
		registerEvents();
		LOGGER.info("Guard Villagers initialized");
	}

	private static void registerDispenserBehavior() {
		DispenserBlock.registerBehavior(GUARD_SPAWN_EGG, new ItemDispenserBehavior() {
			@Override
			protected ItemStack dispenseSilently(net.minecraft.util.math.BlockPointer pointer, ItemStack stack) {
				ServerWorld world = pointer.world();
				Direction direction = pointer.state().get(DispenserBlock.FACING);
				BlockPos spawnPos = pointer.pos().offset(direction);

				GuardEntity guard = GUARD_ENTITY_TYPE.create(world, SpawnReason.DISPENSER);
				if (guard == null) {
					return stack;
				}

				BlockPos top = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnPos);
				guard.refreshPositionAndAngles(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D, direction.getPositiveHorizontalDegrees(), 0.0F);
				guard.applyNaturalLoadout(world);
				guard.setBehavior(GuardBehavior.random(world.getRandom()));
				if (world.spawnEntity(guard)) {
					stack.decrement(1);
				}
				return stack;
			}
		});
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
		dispatcher.register(buildGuardsCommand("guards"));
		dispatcher.register(buildGuardsCommand("gaurds"));
	}

	private com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildGuardsCommand(String rootLiteral) {
		return CommandManager.literal(rootLiteral)
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
				.then(CommandManager.literal("defensive").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.DEFENSIVE)))
				.then(CommandManager.literal("random").executes(context -> setBehaviorRandom(context.getSource().getPlayerOrThrow()))))
			.then(CommandManager.literal("behaviour")
				.then(CommandManager.literal("perimeter").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.PERIMETER)))
				.then(CommandManager.literal("bodyguard").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.BODYGUARD)))
				.then(CommandManager.literal("crowd_control").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.CROWD_CONTROL)))
				.then(CommandManager.literal("offensive").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.OFFENSIVE)))
				.then(CommandManager.literal("defensive").executes(context -> setBehavior(context.getSource().getPlayerOrThrow(), GuardBehavior.DEFENSIVE)))
				.then(CommandManager.literal("random").executes(context -> setBehaviorRandom(context.getSource().getPlayerOrThrow()))))
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
			.then(CommandManager.literal("debug")
				.executes(context -> toggleDebug(context.getSource().getPlayerOrThrow()))
				.then(CommandManager.literal("on")
					.executes(context -> setDebug(context.getSource().getPlayerOrThrow(), true)))
				.then(CommandManager.literal("off")
					.executes(context -> setDebug(context.getSource().getPlayerOrThrow(), false))))
		;
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

	public static int getAdjustedGuardCost(ServerPlayerEntity player) {
		GuardPlayerUpgrades upgrades = getUpgrades(player);
		return GuardReputationManager.getAdjustedGuardCost(player, upgrades.getGuardCost());
	}

	public static GuardPurchaseResult purchaseGuard(ServerPlayerEntity player) {
		if (!GuardReputationManager.isTrustedByGuards(player.getEntityWorld(), player.getUuid(), player.getBlockPos())) {
			return GuardPurchaseResult.NOT_TRUSTED;
		}

		GuardPlayerUpgrades upgrades = getUpgrades(player);
		int cost = GuardReputationManager.getAdjustedGuardCost(player, upgrades.getGuardCost());
		if (!GuardEconomy.spendEmeraldBlocks(player, cost)) {
			return GuardPurchaseResult.INSUFFICIENT_FUNDS;
		}

		ServerWorld world = player.getEntityWorld();
		try {
			if (trySpawnPurchasedGuard(world, player, upgrades)) {
				return GuardPurchaseResult.SUCCESS;
			}
			GuardEconomy.refundEmeraldBlocks(player, cost);
			LOGGER.warn("Guard purchase failed to spawn for {} in world {}", player.getName().getString(), world.getRegistryKey().getValue());
			return GuardPurchaseResult.SPAWN_FAILED;
		} catch (RuntimeException exception) {
			GuardEconomy.refundEmeraldBlocks(player, cost);
			LOGGER.error("Guard purchase crashed for {}", player.getName().getString(), exception);
			return GuardPurchaseResult.INTERNAL_ERROR;
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
			BlockPos candidate = new BlockPos((int) Math.floor(player.getX() + offset[0]), player.getBlockY(), (int) Math.floor(player.getZ() + offset[1]));
			BlockPos top = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, candidate);
			guard.refreshPositionAndAngles(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D, player.getYaw(), 0.0F);
			guard.setOwnerUuid(player.getUuid());
			guard.setStaying(false);
			guard.setRole(pickDynamicPurchasedRole(world, player.getUuid(), player.getBlockPos()));
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
		for (GuardEntity guard : getOwnedGuards(player.getCommandSource().getServer(), player.getUuid())) {
			guard.setStaying(staying);
			if (!staying) {
				guard.clearHome();
				guard.setBehavior(GuardBehavior.BODYGUARD);
				guard.clearCombatTarget();
			}
			changed++;
		}
		return changed;
	}

	private static int setBehavior(ServerPlayerEntity player, GuardBehavior behavior) {
		int changed = 0;
		for (GuardEntity guard : getOwnedGuards(player.getCommandSource().getServer(), player.getUuid())) {
			guard.setBehavior(behavior);
			guard.clearCombatTarget();
			changed++;
		}
		player.sendMessage(Text.literal("Set " + changed + " guards to " + behavior.name().toLowerCase() + "."), true);
		return changed;
	}

	private static int setBehaviorRandom(ServerPlayerEntity player) {
		List<GuardEntity> ownedGuards = getOwnedGuards(player.getCommandSource().getServer(), player.getUuid());
		if (ownedGuards.isEmpty()) {
			player.sendMessage(Text.literal("No owned guards found."), true);
			return 0;
		}
		Collections.shuffle(ownedGuards, new java.util.Random(player.getEntityWorld().getRandom().nextLong()));
		GuardBehavior[] behaviors = GuardBehavior.values();
		for (int i = 0; i < ownedGuards.size(); i++) {
			GuardEntity guard = ownedGuards.get(i);
			GuardBehavior behavior = behaviors[i % behaviors.length];
			guard.setBehavior(behavior);
			guard.clearCombatTarget();
		}
		player.sendMessage(Text.literal("Distributed " + ownedGuards.size() + " guards evenly across behaviors."), true);
		return ownedGuards.size();
	}

	private static int setFormation(ServerPlayerEntity player, FormationType formationType) {
		int changed = 0;
		for (GuardEntity guard : getOwnedGuards(player.getCommandSource().getServer(), player.getUuid())) {
			guard.setFormationType(formationType);
			guard.clearCombatTarget();
			changed++;
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

	private static List<GuardEntity> getOwnedGuards(MinecraftServer server, UUID ownerUuid) {
		List<GuardEntity> guards = new java.util.ArrayList<>();
		for (ServerWorld world : server.getWorlds()) {
			guards.addAll(world.getEntitiesByClass(
				GuardEntity.class,
				new Box(-30_000_000, world.getBottomY(), -30_000_000, 30_000_000, world.getTopYInclusive(), 30_000_000),
				guard -> guard.isOwnedBy(ownerUuid)
			));
		}
		return guards;
	}

	private static GuardRole pickDynamicPurchasedRole(ServerWorld world, UUID ownerUuid, BlockPos center) {
		List<GuardEntity> owned = world.getEntitiesByClass(
			GuardEntity.class,
			new Box(center).expand(128.0D),
			guard -> guard.isOwnedBy(ownerUuid)
		);
		Map<GuardRole, Integer> counts = new java.util.EnumMap<>(GuardRole.class);
		for (GuardRole role : GuardRole.values()) {
			counts.put(role, 0);
		}
		for (GuardEntity guard : owned) {
			counts.computeIfPresent(guard.getRole(), (role, count) -> count + 1);
		}

		GuardRole choice = GuardRole.SWORDSMAN;
		int best = Integer.MAX_VALUE;
		for (GuardRole role : GuardRole.values()) {
			int count = counts.getOrDefault(role, 0);
			if (count < best) {
				best = count;
				choice = role;
			}
		}
		return choice;
	}

	private static int toggleDebug(ServerPlayerEntity player) {
		boolean enable = !DEBUG_PLAYERS.contains(player.getUuid());
		return setDebug(player, enable);
	}

	private static int setDebug(ServerPlayerEntity player, boolean enable) {
		UUID playerId = player.getUuid();
		if (enable) {
			DEBUG_PLAYERS.add(playerId);
			player.sendMessage(Text.literal("Guard debug enabled."), false);
		} else {
			DEBUG_PLAYERS.remove(playerId);
			clearDebugOverlays(player.getCommandSource().getServer());
			player.sendMessage(Text.literal("Guard debug disabled."), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static void clearDebugOverlays(MinecraftServer server) {
		for (ServerWorld world : server.getWorlds()) {
			for (GuardEntity guard : world.getEntitiesByClass(
				GuardEntity.class,
				new Box(-30_000_000, world.getBottomY(), -30_000_000, 30_000_000, world.getTopYInclusive(), 30_000_000),
				GuardVillagersMod::isDebugName)
			) {
				guard.setCustomNameVisible(false);
				guard.setCustomName(null);
			}
		}
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
				updateGuardDebug(world);
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

	private static void updateGuardDebug(ServerWorld world) {
		if (DEBUG_PLAYERS.isEmpty()) {
			return;
		}

		Set<UUID> seen = new HashSet<>();
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (!DEBUG_PLAYERS.contains(player.getUuid())) {
				continue;
			}

			List<GuardEntity> visibleGuards = world.getEntitiesByClass(
				GuardEntity.class,
				player.getBoundingBox().expand(DEBUG_SCAN_RANGE),
				guard -> true
			);
			for (GuardEntity guard : visibleGuards) {
				seen.add(guard.getUuid());

				if (world.getTime() % DEBUG_TEXT_UPDATE_INTERVAL == 0) {
					guard.setCustomName(buildDebugName(world, guard));
					guard.setCustomNameVisible(true);
				}

				if (world.getTime() % DEBUG_PARTICLE_INTERVAL == 0) {
					renderDebugRanges(world, guard);
					renderDebugTargetLine(world, guard);
				}
			}

		}
		if (world.getTime() % DEBUG_TEXT_UPDATE_INTERVAL == 0) {
			clearOutOfRangeDebug(world, seen);
		}
	}

	private static void clearOutOfRangeDebug(ServerWorld world, Set<UUID> seen) {
		List<GuardEntity> allDebug = world.getEntitiesByClass(
			GuardEntity.class,
			new Box(-30_000_000, world.getBottomY(), -30_000_000, 30_000_000, world.getTopYInclusive(), 30_000_000),
			GuardVillagersMod::isDebugName
		);
		for (GuardEntity guard : allDebug) {
			if (seen.contains(guard.getUuid())) {
				continue;
			}
			guard.setCustomNameVisible(false);
			guard.setCustomName(null);
		}
	}

	private static boolean isDebugName(GuardEntity guard) {
		Text customName = guard.getCustomName();
		return customName != null && customName.getString().startsWith(DEBUG_PREFIX);
	}

	private static Text buildDebugName(ServerWorld world, GuardEntity guard) {
		String targetName = "none";
		LivingEntity target = guard.getTarget();
		if (target != null && target.isAlive()) {
			targetName = target.getName().getString();
		} else if (guard.getPriorityTargetUuid() != null) {
			Entity tracked = world.getEntity(guard.getPriorityTargetUuid());
			if (tracked != null) {
				targetName = tracked.getName().getString();
			}
		}

		double followRange = guard.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.FOLLOW_RANGE);
		String home = guard.getHome().map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ()).orElse("none");
		return Text.literal(
			DEBUG_PREFIX + "Guard " + guard.getUuid().toString().substring(0, 8)
				+ "\nLv " + guard.getLevel() + "  XP " + guard.getExperience()
				+ "\nType: " + guard.getRole().name() + "  Behavior: " + guard.getBehavior().name()
				+ "\nHP: " + Math.round(guard.getHealth()) + "/" + Math.round(guard.getMaxHealth())
				+ "  Staying: " + guard.isStaying() + "  Cooldown: " + guard.getCombatCooldown()
				+ "\nFollowRange: " + String.format("%.1f", followRange) + "  PatrolRadius: " + guard.getPatrolRadius()
				+ "\nHome: " + home
				+ "\nTarget: " + targetName
		);
	}

	private static void renderDebugRanges(ServerWorld world, GuardEntity guard) {
		double followRange = guard.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.FOLLOW_RANGE);
		spawnCircleParticles(world, guard.getEntityPos(), Math.max(2.0D, followRange), ParticleTypes.WAX_OFF, 20);
		guard.getHome().ifPresent(home -> spawnCircleParticles(world, Vec3d.ofCenter(home), Math.max(2.0D, guard.getPatrolRadius()), ParticleTypes.HAPPY_VILLAGER, 24));
	}

	private static void renderDebugTargetLine(ServerWorld world, GuardEntity guard) {
		LivingEntity target = guard.getTarget();
		if (target == null || !target.isAlive()) {
			return;
		}

		Vec3d start = guard.getEntityPos().add(0.0D, 1.5D, 0.0D);
		Vec3d end = target.getEntityPos().add(0.0D, target.getHeight() * 0.5D, 0.0D);
		Vec3d delta = end.subtract(start);
		int segments = Math.max(4, (int) Math.ceil(delta.length() * 2.0D));
		Vec3d step = delta.multiply(1.0D / segments);
		Vec3d cursor = start;
		for (int i = 0; i <= segments; i++) {
			world.spawnParticles(ParticleTypes.CRIT, cursor.x, cursor.y, cursor.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			cursor = cursor.add(step);
		}
	}

	private static void spawnCircleParticles(ServerWorld world, Vec3d center, double radius, net.minecraft.particle.ParticleEffect particle, int points) {
		for (int i = 0; i < points; i++) {
			double angle = (Math.PI * 2.0D * i) / points;
			double x = center.x + Math.cos(angle) * radius;
			double z = center.z + Math.sin(angle) * radius;
			world.spawnParticles(particle, x, center.y + 0.1D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}
}
