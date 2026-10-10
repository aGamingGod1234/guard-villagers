package com.guardvillagers.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
		"guardvillagers_diplomacy",
		GuardDiplomacyState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<UUID, DiplomacyLists> entries;

	public GuardDiplomacyState() {
		this(Map.of());
	}

	private GuardDiplomacyState(Map<UUID, DiplomacyLists> entries) {
		this.entries = new HashMap<>();
		for (Map.Entry<UUID, DiplomacyLists> entry : entries.entrySet()) {
			this.entries.put(entry.getKey(), entry.getValue().copy());
		}
	}

	public DiplomacyLists getOrCreate(UUID owner) {
		return this.entries.computeIfAbsent(owner, ignored -> {
			this.setDirty();
			return new DiplomacyLists(new HashSet<>(), new HashSet<>());
		});
	}

	public boolean toggleWhitelist(UUID owner, UUID target) {
		DiplomacyLists lists = this.getOrCreate(owner);
		boolean added;
		if (lists.whitelist().contains(target)) {
			lists.whitelist().remove(target);
			added = false;
		} else {
			lists.whitelist().add(target);
			lists.blacklist().remove(target);
			added = true;
		}
		this.setDirty();
		return added;
	}

	public boolean toggleBlacklist(UUID owner, UUID target) {
		DiplomacyLists lists = this.getOrCreate(owner);
		boolean added;
		if (lists.blacklist().contains(target)) {
			lists.blacklist().remove(target);
			added = false;
		} else {
			lists.blacklist().add(target);
			lists.whitelist().remove(target);
			added = true;
		}
		this.setDirty();
		return added;
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

		public DiplomacyLists copy() {
			return new DiplomacyLists(new HashSet<>(this.whitelist), new HashSet<>(this.blacklist));
		}
	}
}
