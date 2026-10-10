package com.guardvillagers.tactics;

import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardTacticsManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.network.GuardRosterSyncPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	/** Must match the writeUtf limits in GuardRosterSyncPayload; longer strings make encoding throw and drop the player. */
	private static final int WIRE_MAX_GUARD_NAME_LENGTH = 64;
	private static final int WIRE_MAX_GROUP_NAME_LENGTH = 24;
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
	}

	public static void clearSessionState() {
		LAST_OPEN_TICK.clear();
	}

	public static void syncOwnedGuardRoster(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, GuardRosterSyncPayload.ID)) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		GuardTacticsState.PlayerTactics tactics = GuardTacticsManager.getState(server).getOrDefault(player.getUUID());
		int groupCount = Math.min(tactics.groupCount(), GuardRosterSyncPayload.MAX_GROUPS);
		List<String> groupNames = new ArrayList<>(groupCount);
		for (int row = 0; row < groupCount; row++) {
			groupNames.add(clamp(tactics.getGroupName(row), WIRE_MAX_GROUP_NAME_LENGTH));
		}

		List<GuardEntity> ownedGuards = GuardOwnershipIndex.getOwnedGuards(server, player.getUUID());
		int guardCount = Math.min(ownedGuards.size(), GuardRosterSyncPayload.MAX_GUARDS);
		List<GuardRosterSyncPayload.GuardSummary> guards = new ArrayList<>(guardCount);
		for (int i = 0; i < guardCount; i++) {
			GuardEntity guard = ownedGuards.get(i);
			int groupIndex = guard.getGroupIndex();
			String groupName = groupIndex >= 0 && groupIndex < groupNames.size() ? groupNames.get(groupIndex) : guard.getGroupName();
			double distance = guard.level() == player.level() ? Math.sqrt(guard.distanceToSqr(player)) : -1.0D;
			guards.add(new GuardRosterSyncPayload.GuardSummary(
					guard.getUUID(),
					clamp(guard.getName().getString(), WIRE_MAX_GUARD_NAME_LENGTH),
					guard.getLevel(),
					guard.getHealth(),
					guard.getMaxHealth(),
					guard.getExperience(),
					distance,
					groupIndex,
					clamp(groupName, WIRE_MAX_GROUP_NAME_LENGTH),
					guard.getMainHandItem(),
					guard.getItemBySlot(EquipmentSlot.HEAD),
					guard.getItemBySlot(EquipmentSlot.CHEST),
					guard.getItemBySlot(EquipmentSlot.LEGS),
					guard.getItemBySlot(EquipmentSlot.FEET)));
		}

		ServerPlayNetworking.send(player, new GuardRosterSyncPayload(groupNames, guards));
	}

	static String clamp(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
