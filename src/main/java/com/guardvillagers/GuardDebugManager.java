package com.guardvillagers;

import com.guardvillagers.data.GuardDebugState;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.network.GuardDebugDataPayload;
import com.guardvillagers.network.GuardDebugSyncPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Operator-only path/target overlay. State is persisted per player; the sync runs every few ticks per level. */
public final class GuardDebugManager {
	private static final int SYNC_INTERVAL_TICKS = 5;
	private static final int MAX_PATH_NODES = 64;
	private static final int MAX_GUARD_DELTAS = 256;
	public static final int MAX_REQUESTED_RANGE = (int) GuardSecurityLimits.MAX_CLIENT_DEBUG_RANGE;

	/** Per debugging player: entity id -> hash of the last snapshot sent, so only changes are re-sent. */
	private static final Map<UUID, Map<Integer, Integer>> PATH_HASH_CACHE = new ConcurrentHashMap<>();
	/** Last range sent per player, so the enable/range packet is not repeated every sync. */
	private static final Map<UUID, Double> LAST_SENT_RANGE = new ConcurrentHashMap<>();

	private GuardDebugManager() {
	}

	public static GuardDebugState getState(MinecraftServer server) {
		return Objects.requireNonNull(server, "server").getDataStorage().computeIfAbsent(GuardDebugState.TYPE);
	}

	public static boolean isEnabled(MinecraftServer server, UUID playerId) {
		return getState(server).isEnabled(playerId);
	}

	public static void setEnabled(MinecraftServer server, UUID playerId, boolean enabled) {
		getState(server).setEnabled(playerId, enabled);
	}

	public static void setRange(MinecraftServer server, UUID playerId, double range) {
		getState(server).setRange(playerId, range);
	}

	public static double getRange(MinecraftServer server, UUID playerId) {
		return getState(server).getRange(playerId);
	}

	public static boolean isOperator(ServerPlayer player) {
		return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	public static int getRangeCapBlocks(ServerPlayer player) {
		int viewDistanceChunks = Math.max(2, player.requestedViewDistance());
		return Math.max(1, (viewDistanceChunks * 16) / 2);
	}

	public static double getEffectiveRange(ServerPlayer player) {
		double configuredRange = getRange(player.level().getServer(), player.getUUID());
		int cap = getRangeCapBlocks(player);
		if (configuredRange < 0.0D) {
			return cap;
		}
		return Math.max(1.0D, Math.min(configuredRange, cap));
	}

	/**
	 * Turns the overlay on, or off when it is already on and no range was given.
	 *
	 * @param requestedRange blocks, or a negative value to use the view-distance cap
	 * @return the effective range when enabled, or {@code -1} when this call disabled debug
	 */
	public static double toggle(ServerPlayer player, int requestedRange) {
		MinecraftServer server = player.level().getServer();
		UUID playerId = player.getUUID();
		if (requestedRange < 0 && isEnabled(server, playerId)) {
			disable(player);
			return -1.0D;
		}

		double configuredRange = requestedRange < 0 ? -1.0D : Math.max(1, Math.min(requestedRange, getRangeCapBlocks(player)));
		setRange(server, playerId, configuredRange);
		setEnabled(server, playerId, true);
		double effectiveRange = getEffectiveRange(player);
		sendSync(player, true, effectiveRange);
		return effectiveRange;
	}

	public static void disable(ServerPlayer player) {
		setEnabled(player.level().getServer(), player.getUUID(), false);
		forget(player.getUUID());
		sendSync(player, false, 0.0D);
		send(player, new GuardDebugDataPayload(List.of()));
	}

	/** Forget per-session caches for a player (disconnect, disable). Persisted settings are kept. */
	public static void forget(UUID playerId) {
		PATH_HASH_CACHE.remove(playerId);
		LAST_SENT_RANGE.remove(playerId);
	}

	public static void clearSessionState() {
		PATH_HASH_CACHE.clear();
		LAST_SENT_RANGE.clear();
	}

	public static void tick(ServerLevel world) {
		if (world.getGameTime() % SYNC_INTERVAL_TICKS != 0) {
			return;
		}
		MinecraftServer server = world.getServer();
		for (ServerPlayer player : world.players()) {
			if (!isEnabled(server, player.getUUID())) {
				continue;
			}
			if (!isOperator(player)) {
				// Lost operator status since enabling: turn it off server-side.
				disable(player);
				continue;
			}
			syncPlayer(world, player);
		}
	}

	private static void syncPlayer(ServerLevel world, ServerPlayer player) {
		UUID playerId = player.getUUID();
		double effectiveRange = getEffectiveRange(player);
		sendSync(player, true, effectiveRange);
		double maxDistanceSq = effectiveRange * effectiveRange;
		Map<Integer, Integer> previousHashes = PATH_HASH_CACHE.getOrDefault(playerId, Map.of());
		Map<Integer, Integer> observedHashes = new HashMap<>(previousHashes.size());
		List<GuardDebugDataPayload.GuardDebugEntry> changedEntries = new ArrayList<>();

		for (GuardEntity guard : world.getEntitiesOfClass(
				GuardEntity.class,
				player.getBoundingBox().inflate(effectiveRange),
				entity -> entity.distanceToSqr(player) <= maxDistanceSq)) {
			int guardId = guard.getId();
			GuardEntity.GuardDebugSnapshot snapshot = guard.getDebugSnapshot(MAX_PATH_NODES);
			int hash = hashSnapshot(snapshot);
			observedHashes.put(guardId, hash);
			Integer previousHash = previousHashes.get(guardId);
			if ((previousHash == null || previousHash != hash) && changedEntries.size() < MAX_GUARD_DELTAS) {
				changedEntries.add(new GuardDebugDataPayload.GuardDebugEntry(
						guardId, snapshot.pathNodes(), snapshot.currentPathIndex(), snapshot.targetEntityId()));
			}
		}

		// Removals first so the client never keeps stale overlays; updates that did not fit are retried next sync.
		Map<Integer, Integer> nextHashes = new HashMap<>(previousHashes);
		List<GuardDebugDataPayload.GuardDebugEntry> toSend = new ArrayList<>();
		for (Integer previousGuardId : previousHashes.keySet()) {
			if (!observedHashes.containsKey(previousGuardId) && toSend.size() < MAX_GUARD_DELTAS) {
				toSend.add(new GuardDebugDataPayload.GuardDebugEntry(previousGuardId, List.of(), -1, -1));
				nextHashes.remove(previousGuardId);
			}
		}
		for (GuardDebugDataPayload.GuardDebugEntry entry : changedEntries) {
			if (toSend.size() >= MAX_GUARD_DELTAS) {
				break;
			}
			toSend.add(entry);
			nextHashes.put(entry.entityId(), observedHashes.get(entry.entityId()));
		}

		PATH_HASH_CACHE.put(playerId, nextHashes);
		if (!toSend.isEmpty()) {
			send(player, new GuardDebugDataPayload(toSend));
		}
	}

	private static void sendSync(ServerPlayer player, boolean enabled, double range) {
		Double previous = LAST_SENT_RANGE.get(player.getUUID());
		double encoded = enabled ? range : -1.0D;
		if (previous != null && Double.compare(previous, encoded) == 0) {
			return;
		}
		if (send(player, new GuardDebugSyncPayload(enabled, range))) {
			if (enabled) {
				LAST_SENT_RANGE.put(player.getUUID(), encoded);
			} else {
				LAST_SENT_RANGE.remove(player.getUUID());
			}
		}
	}

	private static boolean send(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
		if (!ServerPlayNetworking.canSend(player, payload.type())) {
			return false;
		}
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	private static int hashSnapshot(GuardEntity.GuardDebugSnapshot snapshot) {
		int hash = Objects.hash(snapshot.currentPathIndex(), snapshot.targetEntityId());
		for (BlockPos node : snapshot.pathNodes()) {
			hash = 31 * hash + node.hashCode();
		}
		return hash;
	}
}
