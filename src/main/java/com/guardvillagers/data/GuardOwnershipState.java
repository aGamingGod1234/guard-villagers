package com.guardvillagers.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * restarts without scanning worlds. Guards refresh their row while loaded; rows for guards that vanished without a
 * destroy event (deleted region, external edits) expire after {@link #EXPIRY_TICKS} of game time unseen.
 */
public final class GuardOwnershipState extends SavedData {
	/** Only re-mark a loaded guard's row dirty this often, so tracking does not rewrite the file every autosave. */
	public static final long REFRESH_TICKS = 20L * 60L * 20L;
	/** 100 in-game days (about 33 hours of server uptime). */
	public static final long EXPIRY_TICKS = 24_000L * 100L;
	public static final int MAX_ENTRIES = 65_536;
	public static final int MAX_NAME_LENGTH = 64;

	private static final Codec<Map<UUID, Entry>> ENTRY_MAP_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC);
	public static final Codec<GuardOwnershipState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		ENTRY_MAP_CODEC.optionalFieldOf("guards", Map.of()).forGetter(GuardOwnershipState::entriesForCodec)
	).apply(instance, GuardOwnershipState::new));

	public static final SavedDataType<GuardOwnershipState> TYPE = new SavedDataType<>(
		GuardSavedData.id("ownership"),
		GuardOwnershipState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<UUID, Entry> guards = new HashMap<>();
	private final Map<UUID, Set<UUID>> byOwner = new HashMap<>();

	public GuardOwnershipState() {
	}

	private GuardOwnershipState(Map<UUID, Entry> entries) {
		for (Map.Entry<UUID, Entry> entry : entries.entrySet()) {
			if (this.guards.size() >= MAX_ENTRIES) {
				break;
			}
			this.put(entry.getKey(), entry.getValue());
		}
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
		if (existing != null && existing.owner().equals(ownerId)
				&& existing.name().equals(safeName) && existing.groupIndex() == groupIndex) {
			long sinceSeen = tick - existing.lastSeenTick();
			if (sinceSeen >= 0 && sinceSeen < REFRESH_TICKS) {
				return;
			}
		} else if (existing == null && this.guards.size() >= MAX_ENTRIES) {
			this.evictLeastRecentlySeen();
		}
		this.put(guardId, new Entry(ownerId, tick, safeName, groupIndex));
		this.setDirty();
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

	/** Guards owned by {@code ownerId} that were seen within the expiry window; expired rows are dropped. */
	public int countOwned(UUID ownerId, long now) {
		Set<UUID> owned = this.byOwner.get(ownerId);
		if (owned == null) {
			return 0;
		}
		int count = 0;
		for (Iterator<UUID> iterator = owned.iterator(); iterator.hasNext(); ) {
			UUID guardId = iterator.next();
			Entry entry = this.guards.get(guardId);
			if (entry != null && !isExpired(entry, now)) {
				count++;
				continue;
			}
			iterator.remove();
			this.guards.remove(guardId);
			this.setDirty();
		}
		if (owned.isEmpty()) {
			this.byOwner.remove(ownerId);
		}
		return count;
	}

	public Set<UUID> guardsOf(UUID ownerId) {
		Set<UUID> owned = this.byOwner.get(ownerId);
		return owned == null ? Set.of() : Collections.unmodifiableSet(owned);
	}

	public int size() {
		return this.guards.size();
	}

	static boolean isExpired(Entry entry, long now) {
		// If game time went backwards (copied world, edited level.dat) the difference is negative and the row stays.
		return now - entry.lastSeenTick() > EXPIRY_TICKS;
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

	private void evictLeastRecentlySeen() {
		UUID oldest = null;
		long oldestTick = Long.MAX_VALUE;
		for (Map.Entry<UUID, Entry> entry : this.guards.entrySet()) {
			if (entry.getValue().lastSeenTick() < oldestTick) {
				oldestTick = entry.getValue().lastSeenTick();
				oldest = entry.getKey();
			}
		}
		if (oldest != null) {
			this.remove(oldest);
		}
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
