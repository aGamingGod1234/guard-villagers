package com.guardvillagers.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class GuardDiplomacyState extends SavedData {
	private static final Codec<Set<UUID>> UUID_SET_CODEC = UUIDUtil.STRING_CODEC.listOf().xmap(HashSet::new, ArrayList::new);
	private static final Codec<Map<UUID, DiplomacyLists>> DIPLOMACY_MAP_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, DiplomacyLists.CODEC);

	public static final Codec<GuardDiplomacyState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		DIPLOMACY_MAP_CODEC.optionalFieldOf("diplomacy", Map.of()).forGetter(GuardDiplomacyState::entriesForCodec)
	).apply(instance, GuardDiplomacyState::new));

	public static final SavedDataType<GuardDiplomacyState> TYPE = new SavedDataType<>(
		GuardSavedData.id("diplomacy"),
		GuardDiplomacyState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	/** Per owner, whitelist and blacklist each. */
	public static final int MAX_LIST_SIZE = 64;
	/** Owners with at least one entry. Empty owners are never stored, so only real lists count toward this. */
	public static final int MAX_OWNERS = 4096;

	public enum Change {
		ADDED,
		REMOVED,
		ALREADY_PRESENT,
		NOT_PRESENT,
		LIST_FULL,
		STATE_FULL,
		SELF
	}

	private final Map<UUID, DiplomacyLists> entries;

	public GuardDiplomacyState() {
		this(Map.of());
	}

	private GuardDiplomacyState(Map<UUID, DiplomacyLists> entries) {
		this.entries = new HashMap<>();
		for (Map.Entry<UUID, DiplomacyLists> entry : entries.entrySet()) {
			if (this.entries.size() >= MAX_OWNERS) {
				break;
			}
			DiplomacyLists lists = entry.getValue().copy();
			trim(lists.whitelist());
			trim(lists.blacklist());
			if (!lists.isEmpty()) {
				this.entries.put(entry.getKey(), lists);
			}
		}
	}

	/** Trusts {@code target} for {@code owner}'s guards (and removes it from the blacklist). */
	public Change addToWhitelist(UUID owner, UUID target) {
		return this.add(owner, target, true);
	}

	public Change removeFromWhitelist(UUID owner, UUID target) {
		return this.remove(owner, target, true);
	}

	public Change addToBlacklist(UUID owner, UUID target) {
		return this.add(owner, target, false);
	}

	public Change removeFromBlacklist(UUID owner, UUID target) {
		return this.remove(owner, target, false);
	}

	/** @return {@code true} when the target is now whitelisted */
	public boolean toggleWhitelist(UUID owner, UUID target) {
		if (this.isWhitelisted(owner, target)) {
			this.removeFromWhitelist(owner, target);
			return false;
		}
		return this.addToWhitelist(owner, target) == Change.ADDED;
	}

	/** @return {@code true} when the target is now blacklisted */
	public boolean toggleBlacklist(UUID owner, UUID target) {
		if (this.isBlacklisted(owner, target)) {
			this.removeFromBlacklist(owner, target);
			return false;
		}
		return this.addToBlacklist(owner, target) == Change.ADDED;
	}

	public int ownerCount() {
		return this.entries.size();
	}

	private Change add(UUID owner, UUID target, boolean whitelist) {
		if (owner.equals(target)) {
			return Change.SELF;
		}
		DiplomacyLists lists = this.entries.get(owner);
		if (lists == null) {
			if (this.entries.size() >= MAX_OWNERS) {
				return Change.STATE_FULL;
			}
			lists = new DiplomacyLists(new HashSet<>(), new HashSet<>());
		}
		Set<UUID> list = whitelist ? lists.whitelist() : lists.blacklist();
		if (list.contains(target)) {
			return Change.ALREADY_PRESENT;
		}
		if (list.size() >= MAX_LIST_SIZE) {
			return Change.LIST_FULL;
		}
		list.add(target);
		(whitelist ? lists.blacklist() : lists.whitelist()).remove(target);
		this.entries.put(owner, lists);
		this.setDirty();
		return Change.ADDED;
	}

	private Change remove(UUID owner, UUID target, boolean whitelist) {
		DiplomacyLists lists = this.entries.get(owner);
		if (lists == null || !(whitelist ? lists.whitelist() : lists.blacklist()).remove(target)) {
			return Change.NOT_PRESENT;
		}
		if (lists.isEmpty()) {
			this.entries.remove(owner);
		}
		this.setDirty();
		return Change.REMOVED;
	}

	private static void trim(Set<UUID> list) {
		if (list.size() <= MAX_LIST_SIZE) {
			return;
		}
		Iterator<UUID> iterator = list.iterator();
		for (int i = 0; iterator.hasNext(); i++) {
			iterator.next();
			if (i >= MAX_LIST_SIZE) {
				iterator.remove();
			}
		}
	}

	public boolean isBlacklisted(UUID owner, UUID target) {
		DiplomacyLists lists = this.entries.get(owner);
		return lists != null && lists.blacklist().contains(target);
	}

	public boolean isWhitelisted(UUID owner, UUID target) {
		DiplomacyLists lists = this.entries.get(owner);
		return lists != null && lists.whitelist().contains(target);
	}

	public Set<UUID> getBlacklist(UUID owner) {
		DiplomacyLists lists = this.entries.get(owner);
		return lists == null ? Set.of() : Collections.unmodifiableSet(lists.blacklist());
	}

	public Set<UUID> getWhitelist(UUID owner) {
		DiplomacyLists lists = this.entries.get(owner);
		return lists == null ? Set.of() : Collections.unmodifiableSet(lists.whitelist());
	}

	private Map<UUID, DiplomacyLists> entriesForCodec() {
		return Collections.unmodifiableMap(this.entries);
	}

	public record DiplomacyLists(Set<UUID> whitelist, Set<UUID> blacklist) {
		public static final Codec<DiplomacyLists> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUID_SET_CODEC.optionalFieldOf("whitelist", Set.of()).forGetter(DiplomacyLists::whitelist),
			UUID_SET_CODEC.optionalFieldOf("blacklist", Set.of()).forGetter(DiplomacyLists::blacklist)
		).apply(instance, (whitelist, blacklist) -> new DiplomacyLists(new HashSet<>(whitelist), new HashSet<>(blacklist))));

		public boolean isEmpty() {
			return this.whitelist.isEmpty() && this.blacklist.isEmpty();
		}

		public DiplomacyLists copy() {
			return new DiplomacyLists(new HashSet<>(this.whitelist), new HashSet<>(this.blacklist));
		}
	}
}
