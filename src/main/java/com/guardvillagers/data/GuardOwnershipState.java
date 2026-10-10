package com.guardvillagers.data;

import com.guardvillagers.GuardSecurityLimits;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persisted guard -> owner index, so ownership limits and counts include guards in unloaded chunks and survive
 * restarts without scanning worlds. Guards refresh their row while loaded. A row is only removed when its guard is
 * destroyed (killed or discarded) or changes owner; how long a guard has gone unseen only affects roster ordering,
 * because an old row is usually a guard parked in a far-away chunk, not a missing one.
 *
 * <p>Size is bounded per owner ({@link #MAX_ROWS_PER_OWNER}), not globally: a global cap would have to drop some
 * owner's rows and let them hire past the personal limit. The per-owner cap is four times the personal limit, so
 * eviction only ever touches owners who already hold far more guards than they can hire.
 */
public final class GuardOwnershipState extends SavedData {
	/** Only re-mark a loaded guard's row dirty this often, so tracking does not rewrite the file every autosave. */
	public static final long REFRESH_TICKS = 20L * 60L * 20L;
	public static final int MAX_ROWS_PER_OWNER = GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER * 4;
	public static final int MAX_NAME_LENGTH = 64;

	private static final Codec<Map<UUID, Entry>> ENTRY_MAP_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC);
	public static final Codec<GuardOwnershipState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		ENTRY_MAP_CODEC.optionalFieldOf("guards", Map.of()).forGetter(GuardOwnershipState::entriesForCodec),
		Codec.BOOL.optionalFieldOf("backfilled", false).forGetter(GuardOwnershipState::isBackfilled)
	).apply(instance, GuardOwnershipState::new));

	public static final SavedDataType<GuardOwnershipState> TYPE = new SavedDataType<>(
		GuardSavedData.id("ownership"),
		GuardOwnershipState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<UUID, Entry> guards = new HashMap<>();
	private final Map<UUID, Set<UUID>> byOwner = new HashMap<>();
	/** True once {@link GuardOwnershipBackfill} has imported guards saved before this index existed. */
	private boolean backfilled;

	public GuardOwnershipState() {
	}

	private GuardOwnershipState(Map<UUID, Entry> entries, boolean backfilled) {
		for (Map.Entry<UUID, Entry> entry : entries.entrySet()) {
			this.put(entry.getKey(), entry.getValue());
		}
		this.backfilled = backfilled;
	}

	/** Records that {@code guardId} is owned by {@code ownerId} and was seen at {@code tick}, keeping its last label. */
	public void record(UUID guardId, UUID ownerId, long tick) {
		Entry existing = this.guards.get(guardId);
		this.record(guardId, ownerId, tick,
				existing == null ? "" : existing.name(),
				existing == null ? -1 : existing.groupIndex());
	}

	/**
	 * Records ownership plus the last known name and group, shown for the guard while it is unloaded. Only marks the
	 * state dirty when something visible changed or the last-seen time is older than {@link #REFRESH_TICKS}.
	 */
	public void record(UUID guardId, UUID ownerId, long tick, String name, int groupIndex) {
		String safeName = name == null ? "" : name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
		Entry existing = this.guards.get(guardId);
		boolean sameOwner = existing != null && existing.owner().equals(ownerId);
		if (sameOwner && existing.name().equals(safeName) && existing.groupIndex() == groupIndex) {
			long sinceSeen = tick - existing.lastSeenTick();
			if (sinceSeen >= 0 && sinceSeen < REFRESH_TICKS) {
				return;
			}
		}
		if (!sameOwner) {
			this.makeRoomFor(ownerId);
		}
		this.put(guardId, new Entry(ownerId, tick, safeName, groupIndex));
		this.setDirty();
	}

	/**
	 * Adds a row for a guard found in saved entity data, unless the guard already has one (a live row is newer than
	 * anything read from disk).
	 */
	public void recordIfAbsent(UUID guardId, UUID ownerId, long tick, String name, int groupIndex) {
		if (!this.guards.containsKey(guardId)) {
			this.record(guardId, ownerId, tick, name, groupIndex);
		}
	}

	public Optional<Entry> get(UUID guardId) {
		return Optional.ofNullable(this.guards.get(guardId));
	}

	public void remove(UUID guardId) {
		Entry removed = this.guards.remove(guardId);
		if (removed != null) {
			this.unlinkOwner(removed.owner(), guardId);
			this.setDirty();
		}
	}

	/** Every guard row owned by {@code ownerId}, however long ago it was last seen. */
	public int countOwned(UUID ownerId) {
		Set<UUID> owned = this.byOwner.get(ownerId);
		return owned == null ? 0 : owned.size();
	}

	public Set<UUID> guardsOf(UUID ownerId) {
		Set<UUID> owned = this.byOwner.get(ownerId);
		return owned == null ? Set.of() : Collections.unmodifiableSet(owned);
	}

	public int size() {
		return this.guards.size();
	}

	public boolean isBackfilled() {
		return this.backfilled;
	}

	public void markBackfilled() {
		if (!this.backfilled) {
			this.backfilled = true;
			this.setDirty();
		}
	}

	private void put(UUID guardId, Entry entry) {
		Entry previous = this.guards.put(guardId, entry);
		if (previous != null && !previous.owner().equals(entry.owner())) {
			this.unlinkOwner(previous.owner(), guardId);
		}
		this.byOwner.computeIfAbsent(entry.owner(), ignored -> new HashSet<>()).add(guardId);
	}

	private void unlinkOwner(UUID ownerId, UUID guardId) {
		Set<UUID> owned = this.byOwner.get(ownerId);
		if (owned != null && owned.remove(guardId) && owned.isEmpty()) {
			this.byOwner.remove(ownerId);
		}
	}

	/**
	 * Keeps {@code ownerId} under {@link #MAX_ROWS_PER_OWNER} by dropping that owner's least recently seen row. The
	 * owner keeps far more rows than the personal limit, so this never lets anyone hire past it.
	 */
	private void makeRoomFor(UUID ownerId) {
		Set<UUID> owned = this.byOwner.get(ownerId);
		if (owned == null || owned.size() < MAX_ROWS_PER_OWNER) {
			return;
		}
		UUID oldest = null;
		long oldestTick = Long.MAX_VALUE;
		for (UUID guardId : owned) {
			long lastSeen = this.guards.get(guardId).lastSeenTick();
			if (oldest == null || lastSeen < oldestTick) {
				oldestTick = lastSeen;
				oldest = guardId;
			}
		}
		this.remove(oldest);
	}

	private Map<UUID, Entry> entriesForCodec() {
		return Collections.unmodifiableMap(this.guards);
	}

	public record Entry(UUID owner, long lastSeenTick, String name, int groupIndex) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Entry::owner),
			Codec.LONG.optionalFieldOf("last_seen", 0L).forGetter(Entry::lastSeenTick),
			Codec.string(0, MAX_NAME_LENGTH).optionalFieldOf("name", "").forGetter(Entry::name),
			Codec.intRange(-1, 63).optionalFieldOf("group", -1).forGetter(Entry::groupIndex)
		).apply(instance, Entry::new));

		public Entry {
			name = name == null ? "" : name;
		}
	}
}
