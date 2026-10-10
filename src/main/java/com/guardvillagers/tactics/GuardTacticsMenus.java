package com.guardvillagers.tactics;

import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardTacticsManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.network.GuardGroupResultPayload;
import com.guardvillagers.network.GuardRosterSyncPayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;

/** Opens the tactics/groups screens and pushes the owned-guard roster the client screens render from. */
public final class GuardTacticsMenus {
	/**
	 * Each open rebuilds and sends the full roster (item stacks included). A modified client can send item-use
	 * packets for the whistle every tick, so opens are limited to two per second per player.
	 */
	private static final int MIN_OPEN_INTERVAL_TICKS = 10;
	private static final int REFRESH_INTERVAL_TICKS = 20;
	/** Content hash of the last roster sent to each player with the screen open. */
	private static final Map<UUID, Integer> LAST_ROSTER_HASH = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> LAST_OPEN_TICK = new ConcurrentHashMap<>();

	private GuardTacticsMenus() {
	}

	public static void openTactics(ServerPlayer player) {
		open(player, "screen.guardvillagers.tactics");
	}

	public static void openGroups(ServerPlayer player) {
		open(player, "screen.guardvillagers.groups");
	}

	private static void open(ServerPlayer player, String titleKey) {
		int now = player.level().getServer().getTickCount();
		Integer last = LAST_OPEN_TICK.get(player.getUUID());
		if (last != null && now >= last && now - last < MIN_OPEN_INTERVAL_TICKS) {
			return;
		}
		LAST_OPEN_TICK.put(player.getUUID(), now);
		try {
			syncOwnedGuardRoster(player);
			player.openMenu(new SimpleMenuProvider(
					(syncId, playerInventory, ignoredPlayer) -> new GuardTacticsScreenHandler(syncId, playerInventory, player),
					Component.translatable(titleKey)));
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.error("Failed to open {} for {}", titleKey, player.getName().getString(), exception);
			player.sendSystemMessage(Component.translatable("message.guardvillagers.screen_failed"));
		}
	}

	public static void refreshOpenScreen(ServerPlayer player) {
		if (player.containerMenu instanceof GuardTacticsScreenHandler tacticsScreenHandler) {
			tacticsScreenHandler.refreshInventory();
		}
	}

	public static void forget(UUID playerId) {
		LAST_OPEN_TICK.remove(playerId);
		LAST_ROSTER_HASH.remove(playerId);
	}

	public static void clearSessionState() {
		LAST_OPEN_TICK.clear();
		LAST_ROSTER_HASH.clear();
	}

	/** Sends the roster now (screen open, after a group command). */
	public static void syncOwnedGuardRoster(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, GuardRosterSyncPayload.ID)) {
			return;
		}
		GuardRosterSyncPayload payload = buildRoster(player);
		LAST_ROSTER_HASH.put(player.getUUID(), payload.contentHash());
		ServerPlayNetworking.send(player, payload);
	}

	/**
	 * Once a second, re-sends the roster to players looking at the tactics/groups screen, only when what they would
	 * see changed. Players who closed the screen are forgotten, so nothing is built for them.
	 */
	public static void tickOpenRosters(MinecraftServer server) {
		if (server.getTickCount() % REFRESH_INTERVAL_TICKS != 0) {
			return;
		}
		LAST_ROSTER_HASH.keySet().removeIf(id -> {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			return player == null || !(player.containerMenu instanceof GuardTacticsScreenHandler);
		});
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player.containerMenu instanceof GuardTacticsScreenHandler)
					|| !ServerPlayNetworking.canSend(player, GuardRosterSyncPayload.ID)) {
				continue;
			}
			GuardRosterSyncPayload payload = buildRoster(player);
			int hash = payload.contentHash();
			Integer previous = LAST_ROSTER_HASH.put(player.getUUID(), hash);
			if (previous == null || previous != hash) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	public static void sendGroupResult(ServerPlayer player, GuardGroupResultPayload.Action action, int groupRow,
			int applied, int rejected, String reasonKey) {
		if (ServerPlayNetworking.canSend(player, GuardGroupResultPayload.ID)) {
			ServerPlayNetworking.send(player, new GuardGroupResultPayload(action, groupRow, applied, rejected, reasonKey));
		}
	}

	private static GuardRosterSyncPayload buildRoster(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		GuardTacticsState.PlayerTactics tactics = GuardTacticsManager.getState(server).getOrDefault(player.getUUID());
		int groupCount = Math.min(tactics.groupCount(), GuardRosterSyncPayload.MAX_GROUPS);
		List<String> groupNames = new ArrayList<>(groupCount);
		for (int row = 0; row < groupCount; row++) {
			groupNames.add(tactics.getGroupName(row));
		}

		List<GuardEntity> ownedGuards = GuardOwnershipIndex.getOwnedGuards(server, player.getUUID());
		List<GuardRosterSyncPayload.GuardSummary> guards = new ArrayList<>();
		Set<UUID> loadedIds = new HashSet<>();
		for (GuardEntity guard : ownedGuards) {
			if (guards.size() >= GuardRosterSyncPayload.MAX_GUARDS) {
				break;
			}
			loadedIds.add(guard.getUUID());
			int groupIndex = guard.getGroupIndex();
			double distance = guard.level() == player.level() ? Math.sqrt(guard.distanceToSqr(player)) : -1.0D;
			guards.add(new GuardRosterSyncPayload.GuardSummary(
					guard.getUUID(),
					guard.getName().getString(),
					guard.getLevel(),
					guard.getHealth(),
					guard.getMaxHealth(),
					guard.getExperience(),
					distance,
					groupIndex,
					groupName(groupNames, groupIndex, guard.getGroupName()),
					guard.getMainHandItem(),
					guard.getItemBySlot(EquipmentSlot.HEAD),
					guard.getItemBySlot(EquipmentSlot.CHEST),
					guard.getItemBySlot(EquipmentSlot.LEGS),
					guard.getItemBySlot(EquipmentSlot.FEET),
					true,
					guard.getBehavior().name(),
					guard.getFormationType().name(),
					guard.getRole().name(),
					guard.isStaying(),
					guard.hasFollowOverride(),
					false,
					guard.getHome().isPresent(),
					guard.getRank().id()));
		}
		for (GuardOwnershipIndex.UnloadedGuard unloaded : GuardOwnershipIndex.getUnloadedOwnedGuards(server, player.getUUID(), loadedIds)) {
			if (guards.size() >= GuardRosterSyncPayload.MAX_GUARDS) {
				break;
			}
			guards.add(GuardRosterSyncPayload.GuardSummary.unloaded(unloaded.guardId(), unloaded.name(), unloaded.groupIndex(),
					groupName(groupNames, unloaded.groupIndex(), "")));
		}

		return new GuardRosterSyncPayload(groupNames, guards);
	}

	private static String groupName(List<String> groupNames, int groupIndex, String fallback) {
		return groupIndex >= 0 && groupIndex < groupNames.size() ? groupNames.get(groupIndex) : fallback;
	}
}
