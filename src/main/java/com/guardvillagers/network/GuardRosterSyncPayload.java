package com.guardvillagers.network;

import com.guardvillagers.GuardVillagersMod;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Server -> client snapshot of the player's group names and owned guards, loaded ones first, then guards in unloaded
 * chunks (from the persistent ownership index, {@code loaded == false}, with only their last known name and group).
 */
public record GuardRosterSyncPayload(List<String> groupNames, List<GuardSummary> guards, long snapshotId, int offset, boolean last) implements CustomPacketPayload {
	public static final int MAX_GROUPS = 64;
	/** Per-packet count, not an ownership or roster limit. */
	public static final int MAX_GUARDS = 1_024;
	/** Leave ample room below vanilla's 1 MiB clientbound custom payload limit. */
	public static final int MAX_PACKET_BYTES = 512 * 1024;
	public static final int MAX_GROUP_NAME_LENGTH = 24;
	public static final int MAX_GUARD_NAME_LENGTH = 64;
	public static final int MAX_ENUM_NAME_LENGTH = 32;
	private static final int WIRE_MAX_GROUPS = 256;
	private static final int WIRE_MAX_GUARDS = MAX_GUARDS;

	/** v5 adds chunk identity, offset and completion; the id changes so an older peer never decodes newer bytes. */
	public static final Type<GuardRosterSyncPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(GuardVillagersMod.MOD_ID, "guard_roster_sync_v5"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GuardRosterSyncPayload> CODEC = CustomPacketPayload.codec(
			GuardRosterSyncPayload::write,
			GuardRosterSyncPayload::new);

	public GuardRosterSyncPayload {
		List<String> trimmedGroups = new ArrayList<>(Math.min(MAX_GROUPS, groupNames.size()));
		for (int i = 0; i < groupNames.size() && i < MAX_GROUPS; i++) {
			trimmedGroups.add(trim(groupNames.get(i), MAX_GROUP_NAME_LENGTH));
		}
		groupNames = List.copyOf(trimmedGroups);
		guards = List.copyOf(guards);
		if (offset < 0) throw new IllegalArgumentException("Negative roster offset");
	}

	public GuardRosterSyncPayload(List<String> groupNames, List<GuardSummary> guards) {
		this(groupNames, guards, 0L, 0, true);
	}

	/** Exact encoded byte accounting includes arbitrary enchanted/custom equipment components. */
	public List<GuardRosterSyncPayload> chunks(net.minecraft.core.RegistryAccess registries, long id) {
		List<GuardRosterSyncPayload> packets = new ArrayList<>();
		RegistryFriendlyByteBuf scratch = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registries);
		try {
			new GuardRosterSyncPayload(this.groupNames, List.of()).write(scratch);
			int overhead = scratch.readableBytes() + 5; // offset varint can grow
			int bytes = overhead;
			int start = 0;
			List<GuardSummary> page = new ArrayList<>();
			for (GuardSummary original : this.guards) {
				GuardSummary row = original;
				scratch.clear();
				row.write(scratch);
				if (scratch.readableBytes() + overhead > MAX_PACKET_BYTES) {
					// A single item can carry huge custom components. Retain the guard, omit its gear preview.
					row = row.withoutEquipment();
					scratch.clear();
					row.write(scratch);
				}
				int size = scratch.readableBytes();
				if (!page.isEmpty() && (bytes + size > MAX_PACKET_BYTES || page.size() == MAX_GUARDS)) {
					packets.add(new GuardRosterSyncPayload(this.groupNames, page, id, start, false));
					start += page.size();
					page = new ArrayList<>();
					bytes = overhead;
				}
				page.add(row);
				bytes += size;
			}
			packets.add(new GuardRosterSyncPayload(this.groupNames, page, id, start, true));
			return List.copyOf(packets);
		} finally {
			scratch.release();
		}
	}

	private GuardRosterSyncPayload(RegistryFriendlyByteBuf buf) {
		this(readGroupNames(buf), readGuardSummaries(buf), buf.readLong(), buf.readVarInt(), buf.readBoolean());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		if (this.guards.size() > MAX_GUARDS) throw new IllegalArgumentException("Split roster before encoding");
		buf.writeInt(this.groupNames.size());
		for (String groupName : this.groupNames) {
			buf.writeUtf(groupName, MAX_GROUP_NAME_LENGTH);
		}
		buf.writeInt(this.guards.size());
		for (GuardSummary guard : this.guards) {
			guard.write(buf);
		}
		buf.writeLong(this.snapshotId);
		buf.writeVarInt(this.offset);
		buf.writeBoolean(this.last);
	}

	private static List<String> readGroupNames(RegistryFriendlyByteBuf buf) {
		int rawGroupCount = buf.readInt();
		if (rawGroupCount < 0 || rawGroupCount > WIRE_MAX_GROUPS) {
			throw new IllegalArgumentException("Guard roster group count out of range: " + rawGroupCount);
		}
		List<String> groupNames = new ArrayList<>(Math.min(MAX_GROUPS, rawGroupCount));
		for (int i = 0; i < rawGroupCount; i++) {
			String name = buf.readUtf(MAX_GROUP_NAME_LENGTH);
			if (i < MAX_GROUPS) {
				groupNames.add(name);
			}
		}
		return groupNames;
	}

	private static List<GuardSummary> readGuardSummaries(RegistryFriendlyByteBuf buf) {
		int guardCount = buf.readInt();
		if (guardCount < 0 || guardCount > WIRE_MAX_GUARDS) {
			throw new IllegalArgumentException("Guard roster guard count out of range: " + guardCount);
		}
		List<GuardSummary> guards = new ArrayList<>(guardCount);
		for (int i = 0; i < guardCount; i++) {
			guards.add(GuardSummary.read(buf));
		}
		return guards;
	}

	/** Change detection for periodic re-sends; distance is compared in whole blocks. */
	public int contentHash() {
		int hash = this.groupNames.hashCode();
		for (GuardSummary guard : this.guards) {
			hash = 31 * hash + guard.contentHash();
		}
		return hash;
	}

	static String trim(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	/**
	 * One owned guard.
	 *
	 * @param loaded       false for guards in unloaded chunks: only uuid, displayName and groupIndex are meaningful;
	 *                     numbers are -1, stacks empty, enum names blank, flags false
	 * @param behavior     {@code GuardBehavior} enum name, blank when unknown
	 * @param formation    {@code FormationType} enum name, blank when unknown
	 * @param role         {@code GuardRole} enum name ({@code SWORDSMAN} / {@code BOWMAN}), blank when unknown
	 * @param staying      ordered to stay (or hold) in place
	 * @param following    follow override active (follows the owner even when zoned)
	 * @param holding      hold-position mode; always false until GuardEntity exposes one
	 * @param hasHome      has a home zone
	 * @param rank         {@code GuardRank} id (0 soldier, 1 captain, 2 commander); 0 for unloaded guards
	 */
	public record GuardSummary(
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
			boolean loaded,
			String behavior,
			String formation,
			String role,
			boolean staying,
			boolean following,
			boolean holding,
			boolean hasHome,
			int rank) {
		public GuardSummary {
			displayName = trim(displayName, MAX_GUARD_NAME_LENGTH);
			groupName = trim(groupName, MAX_GROUP_NAME_LENGTH);
			behavior = trim(behavior, MAX_ENUM_NAME_LENGTH);
			formation = trim(formation, MAX_ENUM_NAME_LENGTH);
			role = trim(role, MAX_ENUM_NAME_LENGTH);
			mainHand = mainHand.copy();
			helmet = helmet.copy();
			chest = chest.copy();
			legs = legs.copy();
			boots = boots.copy();
		}

		public GuardSummary withoutEquipment() {
			return new GuardSummary(guardUuid, displayName, level, health, maxHealth, experience, distance,
					groupIndex, groupName, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
					ItemStack.EMPTY, loaded, behavior, formation, role, staying, following, holding, hasHome, rank);
		}

		/** Hash of everything the client renders; ItemStack has identity equality, so stacks hash item + components + count. */
		public int contentHash() {
			int hash = Objects.hash(this.guardUuid, this.displayName, this.level, this.health, this.maxHealth, this.experience,
					Math.round(this.distance), this.groupIndex, this.groupName, this.loaded, this.behavior, this.formation, this.role,
					this.staying, this.following, this.holding, this.hasHome, this.rank);
			for (ItemStack stack : List.of(this.mainHand, this.helmet, this.chest, this.legs, this.boots)) {
				hash = 31 * hash + ItemStack.hashItemAndComponents(stack) + 7 * stack.getCount();
			}
			return hash;
		}

		/** Placeholder row for an owned guard whose chunk is not loaded. */
		public static GuardSummary unloaded(UUID guardUuid, String lastKnownName, int groupIndex, String groupName) {
			return new GuardSummary(guardUuid, lastKnownName, -1, -1.0F, -1.0F, -1, -1.0D, groupIndex, groupName,
					ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
					false, "", "", "", false, false, false, false, 0);
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeUUID(this.guardUuid);
			buf.writeUtf(this.displayName, MAX_GUARD_NAME_LENGTH);
			buf.writeInt(this.level);
			buf.writeFloat(this.health);
			buf.writeFloat(this.maxHealth);
			buf.writeInt(this.experience);
			buf.writeDouble(this.distance);
			buf.writeInt(this.groupIndex);
			buf.writeUtf(this.groupName, MAX_GROUP_NAME_LENGTH);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, this.mainHand);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, this.helmet);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, this.chest);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, this.legs);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, this.boots);
			buf.writeBoolean(this.loaded);
			buf.writeUtf(this.behavior, MAX_ENUM_NAME_LENGTH);
			buf.writeUtf(this.formation, MAX_ENUM_NAME_LENGTH);
			buf.writeUtf(this.role, MAX_ENUM_NAME_LENGTH);
			int flags = (this.staying ? 1 : 0) | (this.following ? 2 : 0) | (this.holding ? 4 : 0) | (this.hasHome ? 8 : 0);
			buf.writeByte(flags);
			buf.writeByte(this.rank);
		}

		private static GuardSummary read(RegistryFriendlyByteBuf buf) {
			UUID guardUuid = buf.readUUID();
			String displayName = buf.readUtf(MAX_GUARD_NAME_LENGTH);
			int level = buf.readInt();
			float health = buf.readFloat();
			float maxHealth = buf.readFloat();
			int experience = buf.readInt();
			double distance = buf.readDouble();
			int groupIndex = buf.readInt();
			String groupName = buf.readUtf(MAX_GROUP_NAME_LENGTH);
			ItemStack mainHand = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
			ItemStack helmet = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
			ItemStack chest = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
			ItemStack legs = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
			ItemStack boots = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
			boolean loaded = buf.readBoolean();
			String behavior = buf.readUtf(MAX_ENUM_NAME_LENGTH);
			String formation = buf.readUtf(MAX_ENUM_NAME_LENGTH);
			String role = buf.readUtf(MAX_ENUM_NAME_LENGTH);
			int flags = buf.readByte();
			int rank = buf.readByte();
			return new GuardSummary(guardUuid, displayName, level, health, maxHealth, experience, distance, groupIndex,
					groupName, mainHand, helmet, chest, legs, boots, loaded, behavior, formation, role,
					(flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, rank);
		}
	}
}
