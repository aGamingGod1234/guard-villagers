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
	private static long nextSnapshot;
	private static final Map<UUID, Integer> LAST_ROSTER_HASH = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> LAST_SENT_TICK = new ConcurrentHashMap<>();
	private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();
	/** Next periodic refresh, spread out for large rosters to bound serialization work. */
	private static final Map<UUID, Integer> NEXT_ROSTER_TICK = new ConcurrentHashMap<>();
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
			sendRoster(player, true);
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
		NEXT_ROSTER_TICK.remove(playerId);
		PENDING.remove(playerId);
		LAST_SENT_TICK.remove(playerId);
		LAST_ROSTER_HASH.remove(playerId);
	}

	public static void clearSessionState() {
		LAST_OPEN_TICK.clear();
		NEXT_ROSTER_TICK.clear();
		PENDING.clear();
		LAST_SENT_TICK.clear();
		LAST_ROSTER_HASH.clear();
	}

	/** Coalesce command bursts into at most two snapshots per second per owner. */
	public static void syncOwnedGuardRoster(ServerPlayer player) {
		PENDING.add(player.getUUID());
	}

	private static void sendRoster(ServerPlayer player, boolean force) {
		if (!ServerPlayNetworking.canSend(player, GuardRosterSyncPayload.ID)) return;
		GuardRosterSyncPayload roster = buildRoster(player);
		// Large rosters are checked at most once per five seconds instead of hashing every second.
		int interval = Math.max(REFRESH_INTERVAL_TICKS, Math.min(100, roster.guards().size() / 10));
		NEXT_ROSTER_TICK.put(player.getUUID(), player.level().getServer().getTickCount() + interval);
		int hash = roster.contentHash();
		Integer previous = LAST_ROSTER_HASH.put(player.getUUID(), hash);
		if (!force && previous != null && previous == hash) return;
		for (GuardRosterSyncPayload packet : roster.chunks(player.registryAccess(), ++nextSnapshot)) {
			ServerPlayNetworking.send(player, packet);
		}
		LAST_SENT_TICK.put(player.getUUID(), player.level().getServer().getTickCount());
	}

	public static void tickOpenRosters(MinecraftServer server) {
		NEXT_ROSTER_TICK.keySet().removeIf(id -> {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			return player == null || !(player.containerMenu instanceof GuardTacticsScreenHandler);
		});
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean requested = PENDING.contains(player.getUUID())
					&& server.getTickCount() - LAST_SENT_TICK.getOrDefault(player.getUUID(), -MIN_OPEN_INTERVAL_TICKS) >= MIN_OPEN_INTERVAL_TICKS;
			if (requested) PENDING.remove(player.getUUID());
			if (requested || (player.containerMenu instanceof GuardTacticsScreenHandler
					&& server.getTickCount() >= NEXT_ROSTER_TICK.getOrDefault(player.getUUID(), 0))) {
				sendRoster(player, requested);
			}
		}
	}

	public static void sendGroupResult(ServerPlayer player, GuardGroupResultPayload.Action action, int groupRow,
			int applied, int rejected, String reasonKey) {
		if (ServerPlayNetworking.canSend(player, GuardGroupResultPayload.ID)) {
			ServerPlayNetworking.send(player, new GuardGroupResultPayload(action, groupRow, applied, rejected, reasonKey));
		}
	}

	public static GuardRosterSyncPayload buildRoster(ServerPlayer player) {
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
					distance >= 0 && distance <= 64 ? guard.getMainHandItem() : net.minecraft.world.item.ItemStack.EMPTY,
					distance >= 0 && distance <= 64 ? guard.getItemBySlot(EquipmentSlot.HEAD) : net.minecraft.world.item.ItemStack.EMPTY,
					distance >= 0 && distance <= 64 ? guard.getItemBySlot(EquipmentSlot.CHEST) : net.minecraft.world.item.ItemStack.EMPTY,
					distance >= 0 && distance <= 64 ? guard.getItemBySlot(EquipmentSlot.LEGS) : net.minecraft.world.item.ItemStack.EMPTY,
					distance >= 0 && distance <= 64 ? guard.getItemBySlot(EquipmentSlot.FEET) : net.minecraft.world.item.ItemStack.EMPTY,
					true,
					guard.getBehavior().name(),
					guard.getFormationType().name(),
					guard.getRole().name(),
					guard.isStaying(),
					guard.hasFollowOverride(),
					guard.isHoldingPosition(),
					guard.getHome().isPresent(),
					guard.getRank().id()));
		}
		for (GuardOwnershipIndex.UnloadedGuard unloaded : GuardOwnershipIndex.getUnloadedOwnedGuards(server, player.getUUID(), loadedIds)) {
			guards.add(GuardRosterSyncPayload.GuardSummary.unloaded(unloaded.guardId(), unloaded.name(), unloaded.groupIndex(),
					groupName(groupNames, unloaded.groupIndex(), "")));
		}

		return new GuardRosterSyncPayload(groupNames, guards);
	}

	private static String groupName(List<String> groupNames, int groupIndex, String fallback) {
		return groupIndex >= 0 && groupIndex < groupNames.size() ? groupNames.get(groupIndex) : fallback;
	}
}
