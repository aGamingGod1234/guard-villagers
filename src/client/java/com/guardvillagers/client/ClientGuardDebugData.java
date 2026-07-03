package com.guardvillagers.client;

import com.guardvillagers.GuardSecurityLimits;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.network.GuardDebugDataPayload;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientGuardDebugData {
	private static final Map<Integer, GuardDebugSnapshot> SNAPSHOTS = new LinkedHashMap<>(16, 0.75F, true);
	private static int totalPathNodes;

	private ClientGuardDebugData() {
	}

	public static void applyPayload(GuardDebugDataPayload payload, ClientWorld world) {
		if (!ClientDebugState.isEnabled() || world == null) {
			clear();
			return;
		}
		for (GuardDebugDataPayload.GuardDebugEntry entry : payload.entries()) {
			if (entry.pathNodes().isEmpty() && entry.targetEntityId() < 0) {
				removeSnapshot(entry.entityId());
				continue;
			}
			if (!(world.getEntityById(entry.entityId()) instanceof GuardEntity)) {
				continue;
			}
			putSnapshot(entry.entityId(), new GuardDebugSnapshot(entry.pathNodes(), entry.currentPathIndex(), entry.targetEntityId()));
		}
		evictOverflow();
	}

	public static GuardDebugSnapshot get(int entityId) {
		return SNAPSHOTS.get(entityId);
	}

	public static void pruneMissing(ClientWorld world) {
		if (world == null) {
			clear();
			return;
		}
		Iterator<Map.Entry<Integer, GuardDebugSnapshot>> iterator = SNAPSHOTS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Integer, GuardDebugSnapshot> entry = iterator.next();
			if (world.getEntityById(entry.getKey()) == null) {
				totalPathNodes -= entry.getValue().pathNodes().size();
				iterator.remove();
			}
		}
	}

	public static void clear() {
		SNAPSHOTS.clear();
		totalPathNodes = 0;
	}

	public static int snapshotCount() {
		return SNAPSHOTS.size();
	}

	public static int totalPathNodeCount() {
		return totalPathNodes;
	}

	public record GuardDebugSnapshot(List<BlockPos> pathNodes, int currentPathIndex, int targetEntityId) {
		public GuardDebugSnapshot {
			pathNodes = List.copyOf(pathNodes);
		}
	}

	private static void putSnapshot(int entityId, GuardDebugSnapshot snapshot) {
		GuardDebugSnapshot previous = SNAPSHOTS.put(entityId, snapshot);
		if (previous != null) {
			totalPathNodes -= previous.pathNodes().size();
		}
		totalPathNodes += snapshot.pathNodes().size();
	}

	private static void removeSnapshot(int entityId) {
		GuardDebugSnapshot removed = SNAPSHOTS.remove(entityId);
		if (removed != null) {
			totalPathNodes -= removed.pathNodes().size();
		}
	}

	private static void evictOverflow() {
		Iterator<Map.Entry<Integer, GuardDebugSnapshot>> iterator = SNAPSHOTS.entrySet().iterator();
		while ((SNAPSHOTS.size() > GuardSecurityLimits.MAX_CLIENT_DEBUG_SNAPSHOTS
				|| totalPathNodes > GuardSecurityLimits.MAX_CLIENT_DEBUG_PATH_NODES)
				&& iterator.hasNext()) {
			Map.Entry<Integer, GuardDebugSnapshot> eldest = iterator.next();
			totalPathNodes -= eldest.getValue().pathNodes().size();
			iterator.remove();
		}
	}
}
