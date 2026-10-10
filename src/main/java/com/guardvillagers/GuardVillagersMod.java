package com.guardvillagers;

import com.guardvillagers.command.GuardCommands;
import com.guardvillagers.data.GuardDebugState;
import com.guardvillagers.data.GuardDiplomacyState;
import com.guardvillagers.data.GuardOwnershipState;
import com.guardvillagers.data.GuardReputationState;
import com.guardvillagers.data.GuardSavedData;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.data.GuardUpgradeState;
import com.guardvillagers.data.GuardVillageState;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.item.GuardWhistleItem;
import com.guardvillagers.network.GuardDebugDataPayload;
import com.guardvillagers.network.GuardDebugSyncPayload;
import com.guardvillagers.network.GuardGroupResultPayload;
import com.guardvillagers.network.GuardRosterSyncPayload;
import com.guardvillagers.tactics.GuardTacticsScreenHandler;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GuardVillagersMod implements ModInitializer {
	public static final String MOD_ID = "guardvillagers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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

	public static final CreativeModeTab GUARD_KIT_GROUP = Registry.register(
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
			new MenuType<>(GuardTacticsScreenHandler::new, FeatureFlags.VANILLA_SET));

	@Override
	public void onInitialize() {
		warmupPersistentStateClasses();
		ServerLifecycleEvents.SERVER_STARTING.register(GuardSavedData::migrateLegacyFiles);
		registerPayloads();
		FabricDefaultAttributeRegistry.register(GUARD_ENTITY_TYPE, GuardEntity.createAttributes());
		registerDispenserBehavior();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> GuardCommands.register(dispatcher));
		GuardEvents.register();
		LOGGER.info("Guard Villagers initialized");
	}

	/** Loads the saved-data classes eagerly so a broken codec fails at startup instead of on first use in-game. */
	private static void warmupPersistentStateClasses() {
		Class<?>[] stateClasses = {
				GuardReputationState.class,
				GuardUpgradeState.class,
				GuardDebugState.class,
				GuardDiplomacyState.class,
				GuardOwnershipState.class,
				GuardVillageState.class,
				GuardTacticsState.class
		};
		for (Class<?> stateClass : stateClasses) {
			try {
				Class.forName(stateClass.getName(), true, GuardVillagersMod.class.getClassLoader());
			} catch (ClassNotFoundException | LinkageError error) {
				throw new IllegalStateException("Guard Villagers failed to load " + stateClass.getSimpleName(), error);
			}
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	private static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(GuardDebugSyncPayload.ID, GuardDebugSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GuardDebugDataPayload.ID, GuardDebugDataPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GuardRosterSyncPayload.ID, GuardRosterSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GuardGroupResultPayload.ID, GuardGroupResultPayload.CODEC);
	}

	private static void registerDispenserBehavior() {
		DispenserBlock.registerBehavior(GUARD_SPAWN_EGG, new DefaultDispenseItemBehavior() {
			@Override
			protected ItemStack execute(BlockSource pointer, ItemStack stack) {
				ServerLevel world = pointer.level();
				Direction direction = pointer.state().getValue(DispenserBlock.FACING);
				BlockPos spawnPos = pointer.pos().relative(direction);

				BlockPos spawn = findNearbyGuardSpawnPos(world, spawnPos, 8);
				if (spawn == null) {
					return stack;
				}
				GuardEntity guard = GUARD_ENTITY_TYPE.create(world, EntitySpawnReason.DISPENSER);
				if (guard == null) {
					return stack;
				}
				guard.snapTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, direction.toYRot(), 0.0F);
				guard.applyNaturalLoadout(world);
				guard.setBehavior(GuardBehavior.random(world.getRandom()));
				if (world.addFreshEntity(guard)) {
					stack.shrink(1);
				}
				return stack;
			}
		});
	}

	/** Shop price for the next hire; see {@link GuardHiring#getAdjustedGuardCost}. */
	public static int getAdjustedGuardCost(ServerPlayer player) {
		return GuardHiring.getAdjustedGuardCost(player);
	}

	/** Mutable, persisted upgrades. Creates a saved row, so only call this when about to change the upgrades. */
	public static GuardPlayerUpgrades getUpgrades(ServerPlayer player) {
		return upgradeState(player.level().getServer()).getOrCreate(player.getUUID());
	}

	/** Read-only snapshot; never creates a saved row. Mutating the returned copy has no effect. */
	public static GuardPlayerUpgrades getUpgradesView(ServerPlayer player) {
		return getUpgradesView(player.level().getServer(), player.getUUID());
	}

	public static GuardPlayerUpgrades getUpgradesView(ServerLevel world, UUID playerUuid) {
		return getUpgradesView(world.getServer(), playerUuid);
	}

	public static GuardPlayerUpgrades getUpgradesView(MinecraftServer server, UUID playerUuid) {
		return upgradeState(server).getOrDefault(playerUuid);
	}

	private static GuardUpgradeState upgradeState(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(GuardUpgradeState.TYPE);
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

	/**
	 * Searches the origin column, then square rings around it. Columns in chunks that are not loaded are skipped so a
	 * spawn search can never force chunk loads or generation.
	 */
	public static BlockPos findNearbyGuardSpawnPos(ServerLevel world, BlockPos origin, int verticalRange) {
		int range = Math.max(0, verticalRange);
		if (isColumnLoaded(world, origin)) {
			BlockPos inPlace = findGuardSpawnPos(world, origin, range);
			if (inPlace != null) {
				return inPlace;
			}
		}

		int horizontalRange = Math.max(2, range);
		for (int radius = 1; radius <= horizontalRange; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					BlockPos column = origin.offset(dx, 0, dz);
					if (!isColumnLoaded(world, column)) {
						continue;
					}
					BlockPos candidate = findGuardSpawnPos(world, column, range);
					if (candidate != null) {
						return candidate;
					}
				}
			}
		}
		return null;
	}

	private static boolean isColumnLoaded(ServerLevel world, BlockPos pos) {
		return world.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
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
}
