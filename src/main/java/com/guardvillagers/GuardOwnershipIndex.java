package com.guardvillagers;

import com.guardvillagers.data.GuardOwnershipState;
import com.guardvillagers.entity.GuardEntity;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Owner -> guard lookup without world scans.
 *
 * <ul>
 *   <li>In memory: weak references to loaded guards, used to command and alert them.</li>
 *   <li>Persisted ({@link GuardOwnershipState}): every owned guard including unloaded ones, used for limits and counts.</li>
 * </ul>
 * GuardEntity calls {@link #track} on load, on owner change and periodically while ticking, and {@link #untrack}
 * when it is destroyed (killed or discarded, not chunk unloads or dimension changes).
 */
public final class GuardOwnershipIndex {
	private static final ConcurrentHashMap<UUID, Set<UUID>> OWNER_TO_GUARDS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, UUID> GUARD_TO_OWNER = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, WeakReference<GuardEntity>> GUARD_REFERENCES = new ConcurrentHashMap<>();

	private GuardOwnershipIndex() {
	}

	public static void track(GuardEntity guard) {
		if (guard == null) {
			return;
		}
		UUID guardId = guard.getUUID();
		UUID ownerUuid = guard.getOwnerUuid();
		if (ownerUuid == null) {
			removeLoaded(guardId);
			persisted(guard).ifPresent(state -> state.remove(guardId));
			return;
		}
		if (guard.isRemoved()) {
			// After a dimension change the new copy (same UUID) is tracked before the old one is
			// removed; only drop the entry if it still points at this instance.
			WeakReference<GuardEntity> reference = GUARD_REFERENCES.get(guardId);
			GuardEntity current = reference == null ? null : reference.get();
			if (current == null || current == guard) {
				removeLoaded(guardId);
			}
			return;
		}

		UUID previousOwner = GUARD_TO_OWNER.put(guardId, ownerUuid);
		if (previousOwner != null && !previousOwner.equals(ownerUuid)) {
			removeFromOwner(previousOwner, guardId);
		}
		OWNER_TO_GUARDS.computeIfAbsent(ownerUuid, ignored -> ConcurrentHashMap.newKeySet()).add(guardId);
		WeakReference<GuardEntity> reference = GUARD_REFERENCES.get(guardId);
		if (reference == null || reference.get() != guard) {
			GUARD_REFERENCES.put(guardId, new WeakReference<>(guard));
		}
		persisted(guard).ifPresent(state -> state.record(guardId, ownerUuid, guard.level().getGameTime(),
				guard.getName().getString(), Math.max(-1, Math.min(63, guard.getGroupIndex()))));
	}

	public static void untrack(GuardEntity guard) {
		if (guard == null) {
			return;
		}
		removeLoaded(guard.getUUID());
		persisted(guard).ifPresent(state -> state.remove(guard.getUUID()));
	}

	/** Loaded, living-or-not-yet-removed guards owned by {@code ownerUuid}. Never scans worlds. */
	public static List<GuardEntity> getOwnedGuards(MinecraftServer server, UUID ownerUuid) {
		if (server == null || ownerUuid == null) {
			return List.of();
		}
		Set<UUID> indexedIds = OWNER_TO_GUARDS.get(ownerUuid);
		if (indexedIds == null || indexedIds.isEmpty()) {
			return List.of();
		}

		List<GuardEntity> resolved = new ArrayList<>(indexedIds.size());
		for (UUID guardId : List.copyOf(indexedIds)) {
			GuardEntity guard = resolveLoadedGuard(server, guardId);
			if (guard == null) {
				continue;
			}
			if (!ownerUuid.equals(guard.getOwnerUuid())) {
				removeLoaded(guardId);
				continue;
			}
			resolved.add(guard);
		}
		return resolved;
	}

	/** Every guard owned by {@code ownerUuid}, loaded or not. This is what the personal guard limit is checked against. */
	public static int countOwnedGuards(MinecraftServer server, UUID ownerUuid) {
		if (server == null || ownerUuid == null) {
			return 0;
		}
		GuardOwnershipState state = state(server);
		int persisted = state.countOwned(ownerUuid, server.overworld().getGameTime());
		// Loaded guards always have a fresh row; the max only matters if the state was reset under us.
		return Math.max(persisted, getOwnedGuards(server, ownerUuid).size());
	}

	/**
	 * Owned guards that are not currently loaded, with their last known name and group, oldest-seen last.
	 * Excludes expired rows.
	 */
	public static List<UnloadedGuard> getUnloadedOwnedGuards(MinecraftServer server, UUID ownerUuid, Set<UUID> loadedIds) {
		if (server == null || ownerUuid == null) {
			return List.of();
		}
		GuardOwnershipState state = state(server);
		long now = server.overworld().getGameTime();
		state.countOwned(ownerUuid, now);
		List<UnloadedGuard> unloaded = new ArrayList<>();
		for (UUID guardId : state.guardsOf(ownerUuid)) {
			if (loadedIds.contains(guardId)) {
				continue;
			}
			state.get(guardId).ifPresent(entry -> unloaded.add(new UnloadedGuard(guardId, entry.name(), entry.groupIndex(), entry.lastSeenTick())));
		}
		unloaded.sort((a, b) -> Long.compare(b.lastSeenTick(), a.lastSeenTick()));
		return unloaded;
	}

	public record UnloadedGuard(UUID guardId, String name, int groupIndex, long lastSeenTick) {
	}

	public static void clearSessionState() {
		OWNER_TO_GUARDS.clear();
		GUARD_TO_OWNER.clear();
		GUARD_REFERENCES.clear();
	}

	private static GuardOwnershipState state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(GuardOwnershipState.TYPE);
	}

	private static Optional<GuardOwnershipState> persisted(GuardEntity guard) {
		return guard.level() instanceof ServerLevel serverLevel
				? Optional.of(state(serverLevel.getServer()))
				: Optional.empty();
	}

	/**
	 * Resolves through the cached reference, then by UUID in each level (a hash lookup per level, not a scan).
	 * Guards in unloaded chunks resolve to {@code null} but keep their index entry for when they load again.
	 */
	private static GuardEntity resolveLoadedGuard(MinecraftServer server, UUID guardId) {
		WeakReference<GuardEntity> reference = GUARD_REFERENCES.get(guardId);
		GuardEntity cached = reference == null ? null : reference.get();
		if (cached != null && !cached.isRemoved()) {
			return cached;
		}
		for (ServerLevel world : server.getAllLevels()) {
			Entity entity = world.getEntity(guardId);
			if (entity instanceof GuardEntity guard && !guard.isRemoved()) {
				GUARD_REFERENCES.put(guardId, new WeakReference<>(guard));
				return guard;
			}
		}
		if (cached == null && reference != null) {
			GUARD_REFERENCES.remove(guardId, reference);
		}
		return null;
	}

	private static void removeLoaded(UUID guardId) {
		UUID ownerUuid = GUARD_TO_OWNER.remove(guardId);
		if (ownerUuid != null) {
			removeFromOwner(ownerUuid, guardId);
		}
		GUARD_REFERENCES.remove(guardId);
	}

	private static void removeFromOwner(UUID ownerUuid, UUID guardId) {
		Set<UUID> ownerSet = OWNER_TO_GUARDS.get(ownerUuid);
		if (ownerSet == null) {
			return;
		}
		ownerSet.remove(guardId);
		if (ownerSet.isEmpty()) {
			OWNER_TO_GUARDS.remove(ownerUuid, ownerSet);
		}
	}
}
