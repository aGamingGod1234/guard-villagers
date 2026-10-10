package com.guardvillagers.client;

import com.guardvillagers.network.GuardGroupResultPayload;
import com.guardvillagers.network.GuardRosterSyncPayload;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

public final class ClientGuardRosterStore {
	private static final ClientGuardRosterStore INSTANCE = new ClientGuardRosterStore();

	private final Map<String, List<GuardRosterEntry>> rostersByWorld = new HashMap<>();
	private int revision;
	// Results of group commands, drained by the open tactics screen; capped so nothing piles up while it is closed.
	private final ArrayDeque<GuardGroupResultPayload> groupResults = new ArrayDeque<>();
	private static final int MAX_QUEUED_RESULTS = 32;

	private ClientGuardRosterStore() {
	}

	public static ClientGuardRosterStore getInstance() {
		return INSTANCE;
	}

	public void applyPayload(ClientTacticsDataStore.WorldContext context, GuardRosterSyncPayload payload) {
		if (context == null || payload == null) {
			return;
		}

		List<GuardRosterEntry> entries = payload.guards().stream()
				.map(ClientGuardRosterStore::toEntry)
				.toList();
		this.rostersByWorld.put(this.key(context), entries);
		this.revision++;
	}

	public void pushGroupResult(GuardGroupResultPayload result) {
		if (result == null) {
			return;
		}
		if (this.groupResults.size() >= MAX_QUEUED_RESULTS) {
			this.groupResults.removeFirst();
		}
		this.groupResults.addLast(result);
	}

	/** Next unread group-command result, or null. */
	public GuardGroupResultPayload pollGroupResult() {
		return this.groupResults.pollFirst();
	}

	public void clearGroupResults() {
		this.groupResults.clear();
	}

	/** Incremented on every roster sync so screens can rebuild cached views and confirm saved changes. */
	public int revision() {
		return this.revision;
	}

	public boolean hasRoster(ClientTacticsDataStore.WorldContext context) {
		return context != null && this.rostersByWorld.containsKey(this.key(context));
	}

	public List<GuardRosterEntry> roster(ClientTacticsDataStore.WorldContext context) {
		if (context == null) {
			return List.of();
		}
		return this.rostersByWorld.getOrDefault(this.key(context), List.of());
	}

	public void clear() {
		this.rostersByWorld.clear();
		this.groupResults.clear();
		this.revision++;
	}

	private static GuardRosterEntry toEntry(GuardRosterSyncPayload.GuardSummary summary) {
		return new GuardRosterEntry(
				summary.guardUuid(),
				summary.displayName(),
				summary.level(),
				summary.health(),
				summary.maxHealth(),
				summary.experience(),
				summary.distance(),
				summary.groupIndex(),
				summary.groupName(),
				summary.mainHand(),
				summary.helmet(),
				summary.chest(),
				summary.legs(),
				summary.boots(),
				summary.loaded(),
				summary.behavior(),
				summary.formation(),
				summary.role(),
				summary.staying(),
				summary.following(),
				summary.holding(),
				summary.hasHome(),
				summary.rank());
	}

	private String key(ClientTacticsDataStore.WorldContext context) {
		return context.worldId() + "|" + context.dimensionId();
	}

	public record GuardRosterEntry(
			UUID guardUuid,
			String displayName,
			int level,
			float health,
			float maxHealth,
			int experience,
			double distance,
			int groupIndex,
			String groupName,
			ItemStack mainHand,
			ItemStack helmet,
			ItemStack chest,
			ItemStack legs,
			ItemStack boots,
			// v3 fields. Enum names ("" when unknown). Unloaded guards send -1 numbers and empty stacks.
			boolean loaded,
			String behavior,
			String formation,
			String role,
			boolean staying,
			boolean following,
			boolean holding,
			boolean hasHome,
			// v4: GuardRank id, 0 soldier / 1 captain / 2 commander.
			int rank) {
		public GuardRosterEntry {
			displayName = displayName == null ? "" : displayName;
			groupName = groupName == null ? "" : groupName;
			behavior = behavior == null ? "" : behavior;
			formation = formation == null ? "" : formation;
			role = role == null ? "" : role;
			mainHand = mainHand.copy();
			helmet = helmet.copy();
			chest = chest.copy();
			legs = legs.copy();
			boots = boots.copy();
		}
	}
}
