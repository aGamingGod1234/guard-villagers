package com.guardvillagers;

import com.guardvillagers.data.GuardUpgradeState;
import com.guardvillagers.data.GuardVillageState;
import com.guardvillagers.data.GuardReputationState;
import com.guardvillagers.data.GuardDebugState;
import com.guardvillagers.data.GuardDiplomacyState;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import net.minecraft.world.item.SpawnEggItem;
import com.guardvillagers.network.GuardDebugDataPayload;
import com.guardvillagers.network.GuardDebugSyncPayload;
import com.guardvillagers.network.GuardRosterSyncPayload;
import com.guardvillagers.item.GuardWhistleItem;
import com.guardvillagers.shop.GuardShopScreenHandler;
import com.guardvillagers.tactics.GuardTacticsScreenHandler;
import com.guardvillagers.village.VillageManagerHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.guardvillagers.data.GuardSavedData;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

public class GuardVillagersMod implements ModInitializer {
	public static final String MOD_ID = "guardvillagers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int DEBUG_SYNC_INTERVAL_TICKS = 5;
	private static final int DEBUG_MAX_PATH_NODES = 64;
	private static final int DEBUG_MAX_GUARD_DELTAS = 256;
	/**
	 * Upper bound on the group-row index exposed through /guards groups rename/assign.
	 * Prevents an unbounded IntegerArgumentType from forcing the server to allocate a
	 * multi-billion-entry group list (GuardTacticsState.ensureGroupCount).
	 */
	private static final int MAX_GROUP_ROW = 64;
	private static final Pattern REPUTATION_INPUT_PATTERN = Pattern.compile("^(?:0(?:\\.\\d{1,2})?|1(?:\\.0{1,2})?)$");
	/**
	 * Touched from END_WORLD_TICK, which fires per-world and may run concurrently
	 * across dimensions on servers with multiple loaded worlds. A plain HashMap
	 * would corrupt under concurrent put/remove; use a ConcurrentHashMap so the
	 * outer lookup is thread-safe. Inner per-player maps are only mutated for
	 * the owning player's map at a time, so they remain plain HashMaps.
	 */
	private static final Map<UUID, Map<Integer, Integer>> DEBUG_PATH_HASH_CACHE = new ConcurrentHashMap<>();

	public enum GuardPurchaseResult {
		SUCCESS,
		NOT_TRUSTED,
		LIMIT_REACHED,
		INSUFFICIENT_FUNDS,
		SPAWN_FAILED,
		INTERNAL_ERROR
	}

	public static final EntityType<GuardEntity> GUARD_ENTITY_TYPE = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			id("guard"),
			EntityType.Builder.of(GuardEntity::new, MobCategory.CREATURE)
					.sized(0.6F, 1.95F)
					.clientTrackingRange(10)
					.build(ResourceKey.create(Registries.ENTITY_TYPE, id("guard"))));

	public static final Item GUARD_WHISTLE = Registry.register(
			BuiltInRegistries.ITEM,
			id("guard_whistle"),
			new GuardWhistleItem(new Item.Properties()
					.setId(ResourceKey.create(Registries.ITEM, id("guard_whistle")))
					.stacksTo(1)));

	public static final Item GUARD_SPAWN_EGG = Registry.register(
			BuiltInRegistries.ITEM,
			id("guard_spawn_egg"),
			new SpawnEggItem(new Item.Properties()
					.setId(ResourceKey.create(Registries.ITEM, id("guard_spawn_egg")))
					.spawnEgg(GUARD_ENTITY_TYPE)));

	public static final net.minecraft.world.item.CreativeModeTab GUARD_KIT_GROUP = Registry.register(
			BuiltInRegistries.CREATIVE_MODE_TAB,
			id("guards"),
			FabricCreativeModeTab.builder()
					.title(Component.translatable("itemGroup.guardvillagers.guards"))
					.icon(() -> new ItemStack(GUARD_SPAWN_EGG))
					.displayItems((context, entries) -> {
						entries.accept(GUARD_SPAWN_EGG);
						entries.accept(GUARD_WHISTLE);
						entries.accept(Items.EMERALD);
					})
					.build());

	public static final MenuType<GuardTacticsScreenHandler> GUARD_TACTICS_SCREEN_HANDLER = Registry.register(
			BuiltInRegistries.MENU,
			id("guard_tactics"),
			new MenuType<>((syncId, playerInventory) -> new GuardTacticsScreenHandler(syncId, playerInventory),
					FeatureFlags.VANILLA_SET));

	@Override
	public void onInitialize() {
		warmupPersistentStateClasses();
		ServerLifecycleEvents.SERVER_STARTING.register(GuardSavedData::migrateLegacyFiles);
		registerPayloads();
		FabricDefaultAttributeRegistry.register(GUARD_ENTITY_TYPE, GuardEntity.createAttributes());
		registerDispenserBehavior();
		registerCommands();
		registerEvents();
		LOGGER.info("Guard Villagers initialized");
	}

	private static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(GuardDebugSyncPayload.ID, GuardDebugSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GuardDebugDataPayload.ID, GuardDebugDataPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GuardRosterSyncPayload.ID, GuardRosterSyncPayload.CODEC);
	}

	private static void warmupPersistentStateClasses() {
		Class<?>[] stateClasses = {
				GuardReputationState.class,
				GuardUpgradeState.class,
				GuardDebugState.class,
				GuardDiplomacyState.class,
				GuardVillageState.class,
				GuardTacticsState.class
		};
		for (Class<?> stateClass : stateClasses) {
			try {
				Class.forName(stateClass.getName(), true, GuardVillagersMod.class.getClassLoader());
			} catch (Throwable throwable) {
				LOGGER.error("Failed to preload persistent state class {}", stateClass.getName(), throwable);
				throw new IllegalStateException(
						"Guard Villagers startup failed while preloading " + stateClass.getSimpleName(), throwable);
			}
		}
	}

	private static void registerDispenserBehavior() {
		DispenserBlock.registerBehavior(GUARD_SPAWN_EGG, new DefaultDispenseItemBehavior() {
			@Override
			protected ItemStack execute(net.minecraft.core.dispenser.BlockSource pointer, ItemStack stack) {
				ServerLevel world = pointer.level();
				Direction direction = pointer.state().getValue(DispenserBlock.FACING);
				BlockPos spawnPos = pointer.pos().relative(direction);

				GuardEntity guard = GUARD_ENTITY_TYPE.create(world, EntitySpawnReason.DISPENSER);
				if (guard == null) {
					return stack;
				}

				BlockPos spawn = findNearbyGuardSpawnPos(world, spawnPos, 8);
				if (spawn == null) {
					return stack;
				}
				guard.snapTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
						direction.toYRot(), 0.0F);
				guard.applyNaturalLoadout(world);
				guard.setBehavior(GuardBehavior.random(world.getRandom()));
				if (world.addFreshEntity(guard)) {
					stack.shrink(1);
				}
				return stack;
			}
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static GuardPlayerUpgrades getUpgrades(ServerPlayer player) {
		return getUpgrades(player.createCommandSourceStack().getServer(), player.getUUID());
	}

	public static GuardPlayerUpgrades getUpgrades(ServerLevel world, UUID playerUuid) {
		return getUpgrades(world.getServer(), playerUuid);
	}

	public static GuardPlayerUpgrades getUpgrades(MinecraftServer server, UUID playerUuid) {
		GuardUpgradeState state = server.getDataStorage().computeIfAbsent(GuardUpgradeState.TYPE);
		return state.getOrCreate(playerUuid);
	}

	public static GuardPlayerUpgrades getUpgradesView(ServerPlayer player) {
		return getUpgradesView(player.createCommandSourceStack().getServer(), player.getUUID());
	}

	public static GuardPlayerUpgrades getUpgradesView(ServerLevel world, UUID playerUuid) {
		return getUpgradesView(world.getServer(), playerUuid);
	}

	public static GuardPlayerUpgrades getUpgradesView(MinecraftServer server, UUID playerUuid) {
		GuardUpgradeState state = server.getDataStorage().computeIfAbsent(GuardUpgradeState.TYPE);
		return state.getOrDefault(playerUuid);
	}

	public static float getHealingAmount(ServerLevel world, UUID ownerUuid) {
		if (ownerUuid == null) {
			return 1.0F;
		}
		return getUpgradesView(world, ownerUuid).getHealingPerCycle();
	}

	public static int getHealingIntervalTicks(ServerLevel world, UUID ownerUuid) {
		if (ownerUuid == null) {
			return 100;
		}
		return getUpgradesView(world, ownerUuid).getHealingIntervalTicks();
	}

	public static boolean hasShieldUpgrade(ServerLevel world, UUID ownerUuid) {
		return ownerUuid != null && getUpgradesView(world, ownerUuid).hasShieldUpgrade();
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register(this::registerGuardCommands);
	}

	private void registerGuardCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
			CommandBuildContext registryAccess, Commands.CommandSelection environment) {
		dispatcher.register(buildGuardsCommand("guards"));
	}

	private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildGuardsCommand(
			String rootLiteral) {
		return Commands.literal(rootLiteral)
				.then(Commands.literal("shop")
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							openShop(player);
							return Command.SINGLE_SUCCESS;
						}))
				.then(Commands.literal("tactics")
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							openTacticsScreen(player);
							return Command.SINGLE_SUCCESS;
						}))
				.then(Commands.literal("stay")
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							int updated = setStance(player, true);
							context.getSource()
									.sendSuccess(() -> Component.literal("Ordered " + updated + " guards to stay."), false);
							return updated;
						}))
				.then(Commands.literal("follow")
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							int updated = setFollowUnzoned(player);
							context.getSource().sendSuccess(
									() -> Component.literal("Ordered " + updated + " owned guards to follow."), false);
							return updated;
						})
						.then(Commands.argument("group", StringArgumentType.greedyString())
								.executes(context -> {
									ServerPlayer player = context.getSource().getPlayerOrException();
									String groupName = StringArgumentType.getString(context, "group");
									int updated = setFollowByGroup(player, groupName);
									context.getSource().sendSuccess(() -> Component.literal("Ordered " + updated
											+ " guards from group \"" + groupName + "\" to follow."), false);
									return updated;
								})))
				.then(Commands.literal("zone")
						.then(Commands.argument("radius", IntegerArgumentType.integer(8, 128))
								.executes(context -> {
									ServerPlayer player = context.getSource().getPlayerOrException();
									int radius = IntegerArgumentType.getInteger(context, "radius");
									int updated = assignZone(player, radius);
									context.getSource().sendSuccess(
											() -> Component.literal("Assigned home zone to " + updated + " guards."), false);
									return updated;
								})))
				.then(Commands.literal("groups")
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							openGroupsScreen(player);
							return Command.SINGLE_SUCCESS;
						})
						.then(Commands.literal("add")
								.executes(context -> {
									ServerPlayer player = context.getSource().getPlayerOrException();
									int row = addGroup(player);
									if (row < 0) {
										context.getSource().sendFailure(Component.literal("Group limit reached."));
										return 0;
									}
									refreshOpenTacticsScreen(player);
									context.getSource()
											.sendSuccess(() -> Component.literal("Added group " + (row + 1) + "."), false);
									return row + 1;
								}))
						.then(Commands.literal("rename")
								// Upper bound prevents an ensureGroupCount() balloon-allocation OOM if a
								// non-operator player invokes this with Integer.MAX_VALUE.
								.then(Commands.argument("row", IntegerArgumentType.integer(1, MAX_GROUP_ROW))
										.then(Commands.argument("name", StringArgumentType.greedyString())
												.executes(context -> {
													ServerPlayer player = context.getSource().getPlayerOrException();
													int row = IntegerArgumentType.getInteger(context, "row") - 1;
													String name = StringArgumentType.getString(context, "name");
													boolean renamed = renameGroup(player, row, name);
													if (renamed) {
														refreshOpenTacticsScreen(player);
														context.getSource().sendSuccess(() -> Component.literal(
																"Renamed group " + (row + 1) + " to \"" + name + "\"."),
																false);
														return Command.SINGLE_SUCCESS;
													}
													context.getSource()
															.sendFailure(Component.literal("Could not rename that group."));
													return 0;
												}))))
						.then(Commands.literal("assign")
								.then(Commands.argument("guardUuid", StringArgumentType.string())
										// 0 means "unassign"; positive values are a row index clamped to the same
										// upper bound as rename to keep group-list allocation bounded.
										.then(Commands.argument("groupRow", IntegerArgumentType.integer(0, MAX_GROUP_ROW))
												.executes(context -> {
													ServerPlayer player = context.getSource().getPlayerOrException();
													String uuidStr = StringArgumentType.getString(context, "guardUuid");
													int groupRow = IntegerArgumentType.getInteger(context, "groupRow")
															- 1;
													int result = assignGuardToGroup(player, uuidStr, groupRow);
													if (result > 0) {
														if (groupRow < 0) {
															context.getSource()
																	.sendSuccess(() -> Component.literal(
																			"Unassigned guard from all groups."),
																			false);
														} else {
															context.getSource().sendSuccess(() -> Component.literal(
																	"Assigned guard to group " + (groupRow + 1) + "."),
																	false);
														}
													} else {
														context.getSource().sendFailure(Component.literal(
																"Could not assign guard (not found or not owned)."));
													}
													return result;
												})))))
				.then(Commands.literal("count")
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							int count = countOwnedGuards(context.getSource().getServer(), player.getUUID());
							context.getSource().sendSuccess(() -> Component.literal("You own " + count + " guards."),
									false);
							return count;
						}))
				.then(Commands.literal("reputation")
						.requires(GuardVillagersMod::hasOperatorPermission)
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("value", StringArgumentType.word())
										.executes(context -> {
											ServerPlayer admin = context.getSource().getPlayerOrException();
											ServerPlayer target = EntityArgument.getPlayer(context, "player");
											String input = StringArgumentType.getString(context, "value");
											return setReputationValue(admin, target, input);
										})))
						.then(Commands.argument("value", StringArgumentType.word())
								.executes(context -> {
									ServerPlayer admin = context.getSource().getPlayerOrException();
									String input = StringArgumentType.getString(context, "value");
									return setReputationValue(admin, admin, input);
								})))
				.then(Commands.literal("debug")
						.requires(GuardVillagersMod::hasOperatorPermission)
						.executes(context -> toggleDebug(context.getSource().getPlayerOrException(), -1))
						.then(Commands.argument("range", IntegerArgumentType.integer(1))
								.executes(context -> toggleDebug(
										context.getSource().getPlayerOrException(),
										IntegerArgumentType.getInteger(context, "range")))));
	}

	private static void openShop(ServerPlayer player) {
		try {
			player.openMenu(new SimpleMenuProvider(
					(syncId, playerInventory, ignoredPlayer) -> new GuardShopScreenHandler(syncId, playerInventory,
							player),
					Component.translatable("screen.guardvillagers.shop")));
		} catch (RuntimeException exception) {
			LOGGER.error("Failed to open guard shop for {}", player.getName().getString(), exception);
			player.sendSystemMessage(Component.literal("Could not open the guard shop. Check server logs."));
		}
	}

	public static void openTacticsScreen(ServerPlayer player) {
		try {
			syncOwnedGuardRoster(player);
			player.openMenu(new SimpleMenuProvider(
					(syncId, playerInventory, ignoredPlayer) -> new GuardTacticsScreenHandler(syncId, playerInventory,
							player),
					Component.translatable("screen.guardvillagers.tactics")));
		} catch (RuntimeException exception) {
			LOGGER.error("Failed to open tactics screen for {}", player.getName().getString(), exception);
			player.sendSystemMessage(Component.literal("Could not open tactics screen. Check server logs."));
		}
	}

	public static void openGroupsScreen(ServerPlayer player) {
		try {
			syncOwnedGuardRoster(player);
			player.openMenu(new SimpleMenuProvider(
					(syncId, playerInventory, ignoredPlayer) -> new GuardTacticsScreenHandler(syncId, playerInventory,
							player),
					Component.translatable("screen.guardvillagers.groups")));
		} catch (RuntimeException exception) {
			LOGGER.error("Failed to open groups screen for {}", player.getName().getString(), exception);
			player.sendSystemMessage(Component.literal("Could not open groups screen. Check server logs."));
		}
	}

	public static int getAdjustedGuardCost(ServerPlayer player) {
		GuardPlayerUpgrades upgrades = getUpgradesView(player);
		int basePrice = upgrades.getGuardCost();
		double reputation = getReputation(player);
		double modifier = 1.5D - (reputation * 0.75D);
		modifier = Math.max(0.75D, Math.min(1.5D, modifier));
		return Math.max(1, (int) Math.round(basePrice * modifier));
	}

	public static double getReputation(ServerPlayer player) {
		return GuardReputationManager.getEffectiveReputation(player);
	}

	public static GuardPurchaseResult purchaseGuard(ServerPlayer player) {
		return purchaseGuards(player, 1).result();
	}

	public static PurchaseBatchResult purchaseGuards(ServerPlayer player, int requestedCount) {
		if (!GuardReputationManager.isTrustedByGuards(player.level(), player.getUUID(),
				player.blockPosition())) {
			return new PurchaseBatchResult(GuardPurchaseResult.NOT_TRUSTED, 0, List.of());
		}

		int normalizedCount = Math.max(1, requestedCount);
		GuardPlayerUpgrades upgrades = getUpgradesView(player);
		int costPerGuard = getAdjustedGuardCost(player);
		boolean creativeMode = player.getAbilities().instabuild;
		int toSpawn = normalizedCount;
		if (!creativeMode) {
			int affordable = costPerGuard <= 0 ? 0
					: GuardEconomy.countEmeraldBlocks(player.getInventory()) / costPerGuard;
			toSpawn = Math.min(normalizedCount, affordable);
		}
		if (toSpawn <= 0 && !creativeMode) {
			return new PurchaseBatchResult(GuardPurchaseResult.INSUFFICIENT_FUNDS, 0, List.of());
		}

		ServerLevel world = player.level();
		int spawned = 0;
		int refundableDebit = 0;
		List<String> spawnedNames = new ArrayList<>();
		try {
			int ownedGuardCount = countOwnedGuards(player.createCommandSourceStack().getServer(), player.getUUID());
			int remainingSlots = GuardSecurityLimits.remainingPersonalGuardSlots(ownedGuardCount);
			if (remainingSlots <= 0) {
				return new PurchaseBatchResult(GuardPurchaseResult.LIMIT_REACHED, 0, List.of());
			}
			toSpawn = Math.min(toSpawn, remainingSlots);
			int startRoleIndex = ownedGuardCount % GuardRole.values().length;
			for (int i = 0; i < toSpawn; i++) {
				if (!GuardEconomy.spendEmeraldBlocks(player, costPerGuard)) {
					break;
				}
				if (!creativeMode) {
					refundableDebit += costPerGuard;
				}
				GuardRole roleForSpawn = GuardRole.values()[(startRoleIndex + i) % GuardRole.values().length];
				GuardEntity spawnedGuard = trySpawnPurchasedGuard(world, player, upgrades, roleForSpawn);
				if (spawnedGuard != null) {
					if (!creativeMode) {
						refundableDebit -= costPerGuard;
					}
					spawned++;
					spawnedNames.add(spawnedGuard.getName().getString());
					continue;
				}
				if (!creativeMode) {
					GuardEconomy.refundEmeraldBlocks(player, costPerGuard);
					refundableDebit -= costPerGuard;
				}
				break;
			}
			if (spawned > 0) {
				return new PurchaseBatchResult(GuardPurchaseResult.SUCCESS, spawned, List.copyOf(spawnedNames));
			}
			LOGGER.warn("Guard purchase failed to spawn for {} in world {}", player.getName().getString(),
					world.dimension().identifier());
			return new PurchaseBatchResult(GuardPurchaseResult.SPAWN_FAILED, 0, List.of());
		} catch (RuntimeException exception) {
			if (!creativeMode && refundableDebit > 0) {
				GuardEconomy.refundEmeraldBlocks(player, refundableDebit);
			}
			LOGGER.error("Guard purchase crashed for {}", player.getName().getString(), exception);
			return new PurchaseBatchResult(GuardPurchaseResult.INTERNAL_ERROR, spawned, List.copyOf(spawnedNames));
		}
	}

	public static boolean spendEmeraldBlocks(ServerPlayer player, int amount) {
		return GuardEconomy.spendEmeraldBlocks(player, amount);
	}

	private static GuardEntity trySpawnPurchasedGuard(ServerLevel world, ServerPlayer player,
			GuardPlayerUpgrades upgrades, GuardRole assignedRole) {
		GuardEntity guard = GUARD_ENTITY_TYPE.create(world, EntitySpawnReason.EVENT);
		if (guard == null) {
			return null;
		}

		BlockPos candidate = new BlockPos(Mth.floor(player.getX()), player.getBlockY(),
				Mth.floor(player.getZ()));
		BlockPos spawn = findNearbyGuardSpawnPos(world, candidate, 10);
		if (spawn == null) {
			return null;
		}

		guard.snapTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, player.getYRot(),
				0.0F);
		guard.setOwnerUuid(player.getUUID());
		guard.setStaying(false);
		guard.setFollowOverride(true);
		guard.setRole(assignedRole);
		guard.applyPurchasedLoadout(world, upgrades);
		guard.setSquadLeader(false);
		if (world.addFreshEntity(guard)) {
			return guard;
		}
		GuardOwnershipIndex.untrack(guard);
		return null;
	}

	private static int setStance(ServerPlayer player, boolean staying) {
		int changed = 0;
		for (GuardEntity guard : getOwnedGuards(player.createCommandSourceStack().getServer(), player.getUUID())) {
			guard.setStaying(staying);
			if (!staying) {
				guard.clearHome();
				guard.clearCombatTarget();
			}
			changed++;
		}
		return changed;
	}

	private static int setFollowUnzoned(ServerPlayer player) {
		int changed = 0;
		for (GuardEntity guard : getOwnedGuards(player.createCommandSourceStack().getServer(), player.getUUID())) {
			guard.setStaying(false);
			guard.setFollowOverride(true);
			guard.clearCombatTarget();
			changed++;
		}
		return changed;
	}

	private static int setFollowByGroup(ServerPlayer player, String groupName) {
		int changed = 0;
		for (GuardEntity guard : getOwnedGuards(player.createCommandSourceStack().getServer(), player.getUUID())) {
			if (guard.getGroupName().equalsIgnoreCase(groupName)) {
				guard.setStaying(false);
				guard.setFollowOverride(true);
				guard.clearCombatTarget();
				changed++;
			}
		}
		return changed;
	}

	private static int setBehavior(ServerPlayer player, GuardBehavior behavior) {
		int changed = 0;
		for (GuardEntity guard : getOwnedGuards(player.createCommandSourceStack().getServer(), player.getUUID())) {
			guard.setBehavior(behavior);
			guard.clearCombatTarget();
			changed++;
		}
		player.sendOverlayMessage(Component.literal("Set " + changed + " guards to " + behavior.name().toLowerCase() + "."));
		return changed;
	}

	private static int setBehaviorRandom(ServerPlayer player) {
		List<GuardEntity> ownedGuards = getOwnedGuards(player.createCommandSourceStack().getServer(), player.getUUID());
		if (ownedGuards.isEmpty()) {
			player.sendOverlayMessage(Component.literal("No owned guards found."));
			return 0;
		}
		Collections.shuffle(ownedGuards, new java.util.Random(player.level().getRandom().nextLong()));
		GuardBehavior[] behaviors = GuardBehavior.values();
		for (int i = 0; i < ownedGuards.size(); i++) {
			GuardEntity guard = ownedGuards.get(i);
			GuardBehavior behavior = behaviors[i % behaviors.length];
			guard.setBehavior(behavior);
			guard.clearCombatTarget();
		}
		player.sendOverlayMessage(Component.literal("Distributed " + ownedGuards.size() + " guards evenly across behaviors."));
		return ownedGuards.size();
	}

	private static int assignZone(ServerPlayer player, int radius) {
		int changed = 0;
		BlockPos home = player.blockPosition();
		for (GuardEntity guard : player.level().getEntitiesOfClass(
				GuardEntity.class,
				player.getBoundingBox().inflate(96.0D),
				entity -> entity.isOwnedBy(player.getUUID()))) {
			guard.setHome(home, radius);
			guard.clearCombatTarget();
			changed++;
		}
		return changed;
	}

	private static int addGroup(ServerPlayer player) {
		MinecraftServer server = player.createCommandSourceStack().getServer();
		if (server == null) {
			return -1;
		}
		GuardTacticsState state = GuardTacticsManager.getState(server);
		GuardTacticsState.PlayerTactics tactics = state.getOrCreate(player.getUUID());
		int row = tactics.addGroup();
		if (row < 0) {
			return -1;
		}
		state.setDirty();
		syncOwnedGuardRoster(player);
		return row;
	}

	private static boolean renameGroup(ServerPlayer player, int row, String requestedName) {
		if (row < 0 || requestedName == null || requestedName.isBlank()) {
			return false;
		}
		MinecraftServer server = player.createCommandSourceStack().getServer();
		if (server == null) {
			return false;
		}
		GuardTacticsState state = GuardTacticsManager.getState(server);
		GuardTacticsState.PlayerTactics tactics = state.getOrCreate(player.getUUID());
		tactics.setGroupName(row, requestedName);
		String normalizedName = tactics.getGroupName(row);
		for (GuardEntity guard : getOwnedGuards(server, player.getUUID())) {
			if (guard.getGroupIndex() == row) {
				guard.setGroupName(normalizedName);
			}
		}
		state.setDirty();
		syncOwnedGuardRoster(player);
		return true;
	}

	private static int assignGuardToGroup(ServerPlayer player, String uuidStr, int groupRow) {
		UUID guardUuid;
		try {
			guardUuid = UUID.fromString(uuidStr);
		} catch (IllegalArgumentException e) {
			return 0;
		}
		MinecraftServer server = player.createCommandSourceStack().getServer();
		if (server == null) {
			return 0;
		}
		for (GuardEntity guard : getOwnedGuards(server, player.getUUID())) {
			if (guard.getUUID().equals(guardUuid)) {
				guard.setGroupIndex(groupRow);
				if (groupRow < 0) {
					guard.setGroupName("Unassigned");
				} else {
					GuardTacticsState state = GuardTacticsManager.getState(server);
					GuardTacticsState.PlayerTactics tactics = state.getOrCreate(player.getUUID());
					String groupName = tactics.getGroupName(groupRow);
					if (groupName != null && !groupName.isBlank()) {
						guard.setGroupName(groupName);
					}
				}
				guard.updateGroupNameplate();
				syncOwnedGuardRoster(player);
				return 1;
			}
		}
		return 0;
	}

	private static int setReputationValue(ServerPlayer admin, ServerPlayer target, String input) {
		Double parsedValue = parseReputationInput(input);
		if (parsedValue == null) {
			admin.sendSystemMessage(Component.literal(
					"Invalid reputation value \"" + input + "\". Use 0.00 to 1.00 with up to 2 decimal places.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		GuardReputationManager.setReputation(admin.level(), target.getUUID(), parsedValue);
		String valueText = formatReputation(parsedValue);
		if (admin.getUUID().equals(target.getUUID())) {
			admin.sendSystemMessage(Component.literal("Set your guard reputation to " + valueText + "."));
		} else {
			admin.sendSystemMessage(
					Component.literal("Set " + target.getName().getString() + "'s guard reputation to " + valueText + "."));
			target.sendSystemMessage(Component.literal("Your guard reputation was set to " + valueText + " by an operator."));
		}
		return Command.SINGLE_SUCCESS;
	}

	private static Double parseReputationInput(String input) {
		if (input == null) {
			return null;
		}
		String normalized = input.trim();
		if (!REPUTATION_INPUT_PATTERN.matcher(normalized).matches()) {
			return null;
		}
		try {
			BigDecimal value = new BigDecimal(normalized);
			if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0 || value.scale() > 2) {
				return null;
			}
			return value.setScale(2, RoundingMode.UNNECESSARY).doubleValue();
		} catch (NumberFormatException | ArithmeticException exception) {
			return null;
		}
	}

	private static String formatReputation(double value) {
		BigDecimal normalized = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
		return normalized.toPlainString();
	}

	private static boolean hasOperatorPermission(CommandSourceStack source) {
		if (source == null) {
			return false;
		}
		if (!(source.permissions() instanceof LevelBasedPermissionSet leveled)) {
			return false;
		}
		return leveled.level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
	}

	private static void refreshOpenTacticsScreen(ServerPlayer player) {
		if (player.containerMenu instanceof GuardTacticsScreenHandler tacticsScreenHandler) {
			tacticsScreenHandler.refreshInventory();
		}
	}

	public static void syncOwnedGuardRoster(ServerPlayer player) {
		MinecraftServer server = player.createCommandSourceStack().getServer();
		if (server == null) {
			return;
		}
		if (!ServerPlayNetworking.canSend(player, GuardRosterSyncPayload.ID)) {
			return;
		}

		GuardTacticsState state = GuardTacticsManager.getState(server);
		GuardTacticsState.PlayerTactics tactics = state.getOrDefault(player.getUUID());
		int groupCount = Math.min(tactics.groupCount(), GuardRosterSyncPayload.MAX_GROUPS);
		List<String> groupNames = new ArrayList<>(groupCount);
		for (int row = 0; row < groupCount; row++) {
			groupNames.add(tactics.getGroupName(row));
		}

		List<GuardEntity> ownedGuards = getOwnedGuards(server, player.getUUID());
		int guardCount = Math.min(ownedGuards.size(), GuardRosterSyncPayload.MAX_GUARDS);
		List<GuardRosterSyncPayload.GuardSummary> guards = new ArrayList<>(guardCount);
		for (int i = 0; i < guardCount; i++) {
			GuardEntity guard = ownedGuards.get(i);
			int groupIndex = guard.getGroupIndex();
			String groupName = resolveRosterGroupName(groupNames, guard, groupIndex);
			double distance = guard.level() == player.level()
					? Math.sqrt(guard.distanceToSqr(player))
					: -1.0D;
			guards.add(new GuardRosterSyncPayload.GuardSummary(
					guard.getUUID(),
					guard.getName().getString(),
					guard.getLevel(),
					guard.getHealth(),
					guard.getMaxHealth(),
					guard.getExperience(),
					distance,
					groupIndex,
					groupName,
					guard.getMainHandItem(),
					guard.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD),
					guard.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST),
					guard.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS),
					guard.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)));
		}

		ServerPlayNetworking.send(player, new GuardRosterSyncPayload(groupNames, guards));
	}

	private static String resolveRosterGroupName(List<String> groupNames, GuardEntity guard, int groupIndex) {
		if (groupIndex >= 0 && groupIndex < groupNames.size()) {
			return groupNames.get(groupIndex);
		}
		return guard.getGroupName();
	}

	private static int countOwnedGuards(MinecraftServer server, UUID ownerUuid) {
		return GuardOwnershipIndex.countOwnedGuards(server, ownerUuid);
	}

	private static List<GuardEntity> getOwnedGuards(MinecraftServer server, UUID ownerUuid) {
		return GuardOwnershipIndex.getOwnedGuards(server, ownerUuid);
	}

	public record PurchaseBatchResult(GuardPurchaseResult result, int spawnedCount, List<String> guardNames) {
		public PurchaseBatchResult {
			guardNames = List.copyOf(guardNames);
		}
	}

	public static BlockPos findGuardSpawnPos(ServerLevel world, BlockPos origin, int verticalRange) {
		int bottomY = world.getMinY() + 1;
		int topY = world.getMaxY() - 1;
		int clampedY = Math.max(bottomY, Math.min(topY, origin.getY()));
		int range = Math.max(0, verticalRange);
		int x = origin.getX();
		int z = origin.getZ();

		BlockPos base = new BlockPos(x, clampedY, z);
		if (canGuardSpawnAt(world, base)) {
			return base;
		}

		for (int step = 1; step <= range; step++) {
			int upY = clampedY + step;
			int downY = clampedY - step;
			if (upY <= topY) {
				BlockPos up = new BlockPos(x, upY, z);
				if (canGuardSpawnAt(world, up)) {
					return up;
				}
			}
			if (downY >= bottomY) {
				BlockPos down = new BlockPos(x, downY, z);
				if (canGuardSpawnAt(world, down)) {
					return down;
				}
			}
		}
		return null;
	}

	public static BlockPos findNearbyGuardSpawnPos(ServerLevel world, BlockPos origin, int verticalRange) {
		int range = Math.max(0, verticalRange);
		BlockPos inPlace = findGuardSpawnPos(world, origin, range);
		if (inPlace != null) {
			return inPlace;
		}

		int horizontalRange = Math.max(2, range);
		for (int radius = 1; radius <= horizontalRange; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}

					BlockPos candidate = findGuardSpawnPos(world, origin.offset(dx, 0, dz), range);
					if (candidate != null) {
						return candidate;
					}
				}
			}
		}
		return null;
	}

	public static boolean canGuardSpawnAt(ServerLevel world, BlockPos feetPos) {
		int y = feetPos.getY();
		if (y <= world.getMinY() || y >= world.getMaxY()) {
			return false;
		}

		BlockPos below = feetPos.below();
		BlockPos head = feetPos.above();
		if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) {
			return false;
		}
		if (!world.getBlockState(feetPos).getCollisionShape(world, feetPos).isEmpty()) {
			return false;
		}
		if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
			return false;
		}
		if (!world.getFluidState(feetPos).isEmpty() || !world.getFluidState(head).isEmpty()) {
			return false;
		}

		AABB spawnBox = GUARD_ENTITY_TYPE.getDimensions().makeBoundingBox(
				feetPos.getX() + 0.5D,
				feetPos.getY(),
				feetPos.getZ() + 0.5D);
		return world.noCollision(null, spawnBox);
	}

	private static int toggleDebug(ServerPlayer player, int requestedRange) {
		MinecraftServer server = player.createCommandSourceStack().getServer();
		if (server == null) {
			return 0;
		}

		UUID playerId = player.getUUID();
		boolean currentlyEnabled = GuardDebugManager.isEnabled(server, playerId);
		if (currentlyEnabled) {
			GuardDebugManager.setEnabled(server, playerId, false);
			DEBUG_PATH_HASH_CACHE.remove(playerId);
			sendDebugSync(player, false, 0.0D);
			clearDebugData(player);
			player.sendSystemMessage(Component.literal("Debug disabled."));
			return Command.SINGLE_SUCCESS;
		}

		double configuredRange = requestedRange < 0 ? -1.0D : clampRequestedDebugRange(player, requestedRange);
		GuardDebugManager.setRange(server, playerId, configuredRange);
		GuardDebugManager.setEnabled(server, playerId, true);
		double effectiveRange = GuardDebugManager.getEffectiveRange(player);
		sendDebugSync(player, true, effectiveRange);
		player.sendSystemMessage(Component.literal("Debug enabled. Range: " + formatDebugRange(effectiveRange) + " blocks."));
		return Command.SINGLE_SUCCESS;
	}

	private static double clampRequestedDebugRange(ServerPlayer player, int requestedRange) {
		int maxRange = GuardDebugManager.getRangeCapBlocks(player);
		return Math.max(1.0D, Math.min(requestedRange, maxRange));
	}

	private static String formatDebugRange(double range) {
		return Integer.toString((int) Math.round(range));
	}

	private static void sendDebugSync(ServerPlayer player, boolean enabled, double range) {
		ServerPlayNetworking.send(player, new GuardDebugSyncPayload(enabled, range));
	}

	private static void clearDebugData(ServerPlayer player) {
		ServerPlayNetworking.send(player, new GuardDebugDataPayload(List.of()));
	}

	private void registerEvents() {
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
					|| !(entity instanceof LivingEntity livingTarget)) {
				return InteractionResult.PASS;
			}

			ServerLevel serverWorld = serverPlayer.level();
			if (livingTarget instanceof Villager || livingTarget instanceof IronGolem) {
				GuardReputationManager.recordVillagerHarm(serverWorld, serverPlayer.getUUID());
			}

			if (livingTarget instanceof GuardEntity targetGuard && targetGuard.isOwnedBy(serverPlayer.getUUID())) {
				return InteractionResult.PASS;
			}
			if (!canOwnerDirectGuardAt(serverPlayer, livingTarget)) {
				return InteractionResult.PASS;
			}

			try {
				List<GuardEntity> ownedGuards = GuardOwnershipIndex
						.getOwnedGuards(serverPlayer.createCommandSourceStack().getServer(), serverPlayer.getUUID());
				long alertTick = serverWorld.getGameTime();
				for (GuardEntity guard : ownedGuards) {
					if (!guard.isAlive()) {
						continue;
					}
					double alertRange = Math.max(8.0D,
							guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE));
					if (guard.distanceToSqr(livingTarget) > alertRange * alertRange) {
						continue;
					}
					guard.receiveOwnerAttackAlert(livingTarget, alertTick);
				}
			} catch (RuntimeException exception) {
				LOGGER.error("Failed to dispatch manual target from {}", serverPlayer.getName().getString(), exception);
			}

			return InteractionResult.PASS;
		});

		ServerTickEvents.END_LEVEL_TICK.register(world -> {
			try {
				syncGuardDebug(world);
				VillageManagerHandler.maintainVillageGuards(world);
				if (world.dimension() == Level.OVERWORLD) {
					GuardReputationManager.tickDecay(world);
				}
				if (world.getGameTime() % 200 == 0) {
					for (ServerPlayer player : world.players()) {
						boolean golemAggro = !world.getEntitiesOfClass(
								IronGolem.class,
								player.getBoundingBox().inflate(24.0D),
								golem -> golem.getTarget() == player).isEmpty();
						if (golemAggro) {
							GuardReputationManager.applyReputationDelta(world, player.getUUID(), -2);
						}
					}
				}
			} catch (RuntimeException exception) {
				LOGGER.error("Village manager tick failed in world {}", world.dimension().identifier(), exception);
			}
		});

		// Owner defense: covers BOTH owner-as-victim and owner-as-attacker
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (!(entity.level() instanceof ServerLevel serverWorld)) {
				return;
			}
			LivingEntity attacker = source.getEntity() instanceof LivingEntity la ? la : null;
			if (attacker == null) {
				return;
			}

			// Case A: owner is the victim — alert guards to target the attacker
			if (entity instanceof ServerPlayer ownerVictim) {
				try {
					List<GuardEntity> guards = GuardOwnershipIndex
							.getOwnedGuards(ownerVictim.createCommandSourceStack().getServer(), ownerVictim.getUUID());
					long tick = serverWorld.getGameTime();
					for (GuardEntity guard : guards) {
						if (!guard.isAlive() || guard.isAlly(attacker)) {
							continue;
						}
						double range = Math.max(8.0D,
								guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE));
						if (guard.distanceToSqr(attacker) > range * range) {
							continue;
						}
						guard.receiveOwnerDamagedAlert(attacker, tick);
					}
				} catch (RuntimeException ex) {
					LOGGER.error("Failed owner-defense alert (victim) for {}", ownerVictim.getName().getString(), ex);
				}
			}

			// Case B: owner is the attacker — alert guards to target the victim
			if (attacker instanceof ServerPlayer ownerAttacker && entity instanceof LivingEntity target) {
				if (target instanceof GuardEntity guardTarget && guardTarget.isOwnedBy(ownerAttacker.getUUID())) {
					return; // Don't target own guards
				}
				if (!canOwnerDirectGuardAt(ownerAttacker, target)) {
					return;
				}
				try {
					List<GuardEntity> guards = GuardOwnershipIndex
							.getOwnedGuards(ownerAttacker.createCommandSourceStack().getServer(), ownerAttacker.getUUID());
					long tick = serverWorld.getGameTime();
					for (GuardEntity guard : guards) {
						if (!guard.isAlive() || guard.isAlly(target)) {
							continue;
						}
						double range = Math.max(8.0D,
								guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE));
						if (guard.distanceToSqr(target) > range * range) {
							continue;
						}
						guard.receiveOwnerAttackAlert(target, tick);
					}
				} catch (RuntimeException ex) {
					LOGGER.error("Failed owner-defense alert (attacker) for {}", ownerAttacker.getName().getString(),
							ex);
				}
			}
		});
	}

	private static boolean canOwnerDirectGuardAt(ServerPlayer owner, LivingEntity target) {
		if (owner == null || target == null || target == owner) {
			return false;
		}
		if (target instanceof Player targetPlayer) {
			if (!owner.level().isPvpAllowed()) {
				return false;
			}
			if (!owner.canHarmPlayer(targetPlayer)) {
				return false;
			}
		}
		return true;
	}

	private static void syncGuardDebug(ServerLevel world) {
		if (world.getGameTime() % DEBUG_SYNC_INTERVAL_TICKS != 0) {
			return;
		}

		MinecraftServer server = world.getServer();
		for (ServerPlayer player : world.players()) {
			UUID playerId = player.getUUID();
			if (!GuardDebugManager.isEnabled(server, playerId)) {
				continue;
			}
			if (!hasOperatorPermission(player.createCommandSourceStack())) {
				GuardDebugManager.setEnabled(server, playerId, false);
				DEBUG_PATH_HASH_CACHE.remove(playerId);
				sendDebugSync(player, false, 0.0D);
				clearDebugData(player);
				continue;
			}

			double effectiveRange = GuardDebugManager.getEffectiveRange(player);
			sendDebugSync(player, true, effectiveRange);
			double maxDistanceSq = effectiveRange * effectiveRange;
			Map<Integer, Integer> previousHashes = DEBUG_PATH_HASH_CACHE.getOrDefault(playerId, Map.of());
			Map<Integer, Integer> observedHashes = new HashMap<>(previousHashes.size());
			Map<Integer, GuardDebugDataPayload.GuardDebugEntry> updatedEntries = new HashMap<>();
			List<GuardDebugDataPayload.GuardDebugEntry> removedEntries = new ArrayList<>();

			for (GuardEntity guard : world.getEntitiesOfClass(
					GuardEntity.class,
					player.getBoundingBox().inflate(effectiveRange),
					entity -> entity.distanceToSqr(player) <= maxDistanceSq)) {
				int guardId = guard.getId();
				GuardEntity.GuardDebugSnapshot snapshot = guard.getDebugSnapshot(DEBUG_MAX_PATH_NODES);
				int hash = hashDebugSnapshot(snapshot);
				observedHashes.put(guardId, hash);
				Integer previousHash = previousHashes.get(guardId);
				if (previousHash == null || previousHash != hash) {
					updatedEntries.put(guardId, new GuardDebugDataPayload.GuardDebugEntry(
							guardId,
							snapshot.pathNodes(),
							snapshot.currentPathIndex(),
							snapshot.targetEntityId()));
				}
			}

			for (Integer previousGuardId : previousHashes.keySet()) {
				if (!observedHashes.containsKey(previousGuardId)) {
					removedEntries.add(new GuardDebugDataPayload.GuardDebugEntry(previousGuardId, List.of(), -1, -1));
				}
			}

			Map<Integer, Integer> nextHashes = new HashMap<>(previousHashes);
			List<GuardDebugDataPayload.GuardDebugEntry> changedEntries = new ArrayList<>(DEBUG_MAX_GUARD_DELTAS);
			for (GuardDebugDataPayload.GuardDebugEntry removedEntry : removedEntries) {
				if (changedEntries.size() >= DEBUG_MAX_GUARD_DELTAS) {
					break;
				}
				changedEntries.add(removedEntry);
				nextHashes.remove(removedEntry.entityId());
			}
			for (GuardDebugDataPayload.GuardDebugEntry updatedEntry : updatedEntries.values()) {
				if (changedEntries.size() >= DEBUG_MAX_GUARD_DELTAS) {
					break;
				}
				changedEntries.add(updatedEntry);
				Integer observedHash = observedHashes.get(updatedEntry.entityId());
				if (observedHash != null) {
					nextHashes.put(updatedEntry.entityId(), observedHash);
				}
			}

			DEBUG_PATH_HASH_CACHE.put(playerId, nextHashes);
			if (!changedEntries.isEmpty()) {
				ServerPlayNetworking.send(player, new GuardDebugDataPayload(changedEntries));
			}
		}

		if (world.dimension() == Level.OVERWORLD) {
			DEBUG_PATH_HASH_CACHE.keySet().removeIf(playerId -> server.getPlayerList().getPlayer(playerId) == null
					|| !GuardDebugManager.isEnabled(server, playerId));
		}
	}

	private static int hashDebugSnapshot(GuardEntity.GuardDebugSnapshot snapshot) {
		int hash = Objects.hash(snapshot.currentPathIndex(), snapshot.targetEntityId());
		for (BlockPos node : snapshot.pathNodes()) {
			hash = 31 * hash + node.hashCode();
		}
		return hash;
	}

}
