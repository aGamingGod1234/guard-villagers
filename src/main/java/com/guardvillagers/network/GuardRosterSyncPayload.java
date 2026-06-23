package com.guardvillagers.network;

import com.guardvillagers.GuardVillagersMod;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record GuardRosterSyncPayload(List<String> groupNames, List<GuardSummary> guards) implements CustomPayload {
	public static final int MAX_GROUPS = 64;
	public static final int MAX_GUARDS = 1_024;
	private static final int WIRE_MAX_GROUPS = 256;
	private static final int WIRE_MAX_GUARDS = MAX_GUARDS;
	private static final int MAX_GROUP_NAME_LENGTH = 24;
	private static final int MAX_GUARD_NAME_LENGTH = 64;

	public static final Id<GuardRosterSyncPayload> ID = new Id<>(GuardVillagersMod.id("guard_roster_sync_v2"));
	public static final PacketCodec<RegistryByteBuf, GuardRosterSyncPayload> CODEC = CustomPayload.codecOf(
			GuardRosterSyncPayload::write,
			GuardRosterSyncPayload::new);

	public GuardRosterSyncPayload {
		groupNames = List.copyOf(groupNames.subList(0, Math.min(MAX_GROUPS, groupNames.size())));
		guards = List.copyOf(guards.subList(0, Math.min(MAX_GUARDS, guards.size())));
	}

	private GuardRosterSyncPayload(RegistryByteBuf buf) {
		this(readGroupNames(buf), readGuardSummaries(buf));
	}

	private void write(RegistryByteBuf buf) {
		int groupCount = Math.min(MAX_GROUPS, this.groupNames.size());
		buf.writeInt(groupCount);
		for (int i = 0; i < groupCount; i++) {
			buf.writeString(this.groupNames.get(i), MAX_GROUP_NAME_LENGTH);
		}

		int guardCount = Math.min(MAX_GUARDS, this.guards.size());
		buf.writeInt(guardCount);
		for (int i = 0; i < guardCount; i++) {
			this.guards.get(i).write(buf);
		}
	}

	private static List<String> readGroupNames(RegistryByteBuf buf) {
		int rawGroupCount = buf.readInt();
		if (rawGroupCount > WIRE_MAX_GROUPS) {
			throw new IllegalArgumentException("Guard roster group count exceeds wire cap: " + rawGroupCount);
		}

		int groupCount = Math.max(0, Math.min(MAX_GROUPS, rawGroupCount));
		List<String> groupNames = new ArrayList<>(groupCount);
		for (int i = 0; i < groupCount; i++) {
			groupNames.add(buf.readString(MAX_GROUP_NAME_LENGTH));
		}
		for (int i = groupCount; i < rawGroupCount; i++) {
			buf.readString(MAX_GROUP_NAME_LENGTH);
		}
		return groupNames;
	}

	private static List<GuardSummary> readGuardSummaries(RegistryByteBuf buf) {
		int rawGuardCount = buf.readInt();
		if (rawGuardCount > WIRE_MAX_GUARDS) {
			throw new IllegalArgumentException("Guard roster guard count exceeds wire cap: " + rawGuardCount);
		}

		int guardCount = Math.max(0, rawGuardCount);
		List<GuardSummary> guards = new ArrayList<>(guardCount);
		for (int i = 0; i < guardCount; i++) {
			guards.add(GuardSummary.read(buf));
		}
		return guards;
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}

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
			ItemStack boots) {
		public GuardSummary {
			displayName = displayName == null ? "" : displayName;
			groupName = groupName == null ? "" : groupName;
			mainHand = mainHand.copy();
			helmet = helmet.copy();
			chest = chest.copy();
			legs = legs.copy();
			boots = boots.copy();
		}

		public GuardSummary(
				UUID guardUuid,
				String displayName,
				int level,
				int groupIndex,
				String groupName,
				ItemStack mainHand,
				ItemStack helmet,
				ItemStack chest,
				ItemStack legs,
				ItemStack boots) {
			this(
					guardUuid,
					displayName,
					level,
					-1.0F,
					-1.0F,
					-1,
					-1.0D,
					groupIndex,
					groupName,
					mainHand,
					helmet,
					chest,
					legs,
					boots);
		}

		private void write(RegistryByteBuf buf) {
			buf.writeUuid(this.guardUuid);
			buf.writeString(this.displayName, MAX_GUARD_NAME_LENGTH);
			buf.writeInt(this.level);
			buf.writeFloat(this.health);
			buf.writeFloat(this.maxHealth);
			buf.writeInt(this.experience);
			buf.writeDouble(this.distance);
			buf.writeInt(this.groupIndex);
			buf.writeString(this.groupName, MAX_GROUP_NAME_LENGTH);
			ItemStack.PACKET_CODEC.encode(buf, this.mainHand);
			ItemStack.PACKET_CODEC.encode(buf, this.helmet);
			ItemStack.PACKET_CODEC.encode(buf, this.chest);
			ItemStack.PACKET_CODEC.encode(buf, this.legs);
			ItemStack.PACKET_CODEC.encode(buf, this.boots);
		}

		private static GuardSummary read(RegistryByteBuf buf) {
			return new GuardSummary(
					buf.readUuid(),
					buf.readString(MAX_GUARD_NAME_LENGTH),
					buf.readInt(),
					buf.readFloat(),
					buf.readFloat(),
					buf.readInt(),
					buf.readDouble(),
					buf.readInt(),
					buf.readString(MAX_GROUP_NAME_LENGTH),
					ItemStack.PACKET_CODEC.decode(buf),
					ItemStack.PACKET_CODEC.decode(buf),
					ItemStack.PACKET_CODEC.decode(buf),
					ItemStack.PACKET_CODEC.decode(buf),
					ItemStack.PACKET_CODEC.decode(buf));
		}
	}
}
