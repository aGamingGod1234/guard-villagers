package com.guardvillagers.network;

import com.guardvillagers.GuardVillagersMod;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

public record GuardRosterSyncPayload(List<String> groupNames, List<GuardSummary> guards) implements CustomPacketPayload {
	public static final int MAX_GROUPS = 64;
	public static final int MAX_GUARDS = 1_024;
	private static final int WIRE_MAX_GROUPS = 256;
	private static final int WIRE_MAX_GUARDS = MAX_GUARDS;
	private static final int MAX_GROUP_NAME_LENGTH = 24;
	private static final int MAX_GUARD_NAME_LENGTH = 64;

	public static final Type<GuardRosterSyncPayload> ID = new Type<>(GuardVillagersMod.id("guard_roster_sync_v2"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GuardRosterSyncPayload> CODEC = CustomPacketPayload.codec(
			GuardRosterSyncPayload::write,
			GuardRosterSyncPayload::new);

	public GuardRosterSyncPayload {
		groupNames = List.copyOf(groupNames.subList(0, Math.min(MAX_GROUPS, groupNames.size())));
		guards = List.copyOf(guards.subList(0, Math.min(MAX_GUARDS, guards.size())));
	}

	private GuardRosterSyncPayload(RegistryFriendlyByteBuf buf) {
		this(readGroupNames(buf), readGuardSummaries(buf));
	}

	private void write(RegistryFriendlyByteBuf buf) {
		int groupCount = Math.min(MAX_GROUPS, this.groupNames.size());
		buf.writeInt(groupCount);
		for (int i = 0; i < groupCount; i++) {
			buf.writeUtf(this.groupNames.get(i), MAX_GROUP_NAME_LENGTH);
		}

		int guardCount = Math.min(MAX_GUARDS, this.guards.size());
		buf.writeInt(guardCount);
		for (int i = 0; i < guardCount; i++) {
			this.guards.get(i).write(buf);
		}
	}

	private static List<String> readGroupNames(RegistryFriendlyByteBuf buf) {
		int rawGroupCount = buf.readInt();
		if (rawGroupCount < 0) {
			throw new IllegalArgumentException("Guard roster group count cannot be negative: " + rawGroupCount);
		}
		if (rawGroupCount > WIRE_MAX_GROUPS) {
			throw new IllegalArgumentException("Guard roster group count exceeds wire cap: " + rawGroupCount);
		}

		int groupCount = Math.min(MAX_GROUPS, rawGroupCount);
		List<String> groupNames = new ArrayList<>(groupCount);
		for (int i = 0; i < groupCount; i++) {
			groupNames.add(buf.readUtf(MAX_GROUP_NAME_LENGTH));
		}
		for (int i = groupCount; i < rawGroupCount; i++) {
			buf.readUtf(MAX_GROUP_NAME_LENGTH);
		}
		return groupNames;
	}

	private static List<GuardSummary> readGuardSummaries(RegistryFriendlyByteBuf buf) {
		int rawGuardCount = buf.readInt();
		if (rawGuardCount < 0) {
			throw new IllegalArgumentException("Guard roster guard count cannot be negative: " + rawGuardCount);
		}
		if (rawGuardCount > WIRE_MAX_GUARDS) {
			throw new IllegalArgumentException("Guard roster guard count exceeds wire cap: " + rawGuardCount);
		}

		int guardCount = rawGuardCount;
		List<GuardSummary> guards = new ArrayList<>(guardCount);
		for (int i = 0; i < guardCount; i++) {
			guards.add(GuardSummary.read(buf));
		}
		return guards;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
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
			ItemStack.STREAM_CODEC.encode(buf, this.mainHand);
			ItemStack.STREAM_CODEC.encode(buf, this.helmet);
			ItemStack.STREAM_CODEC.encode(buf, this.chest);
			ItemStack.STREAM_CODEC.encode(buf, this.legs);
			ItemStack.STREAM_CODEC.encode(buf, this.boots);
		}

		private static GuardSummary read(RegistryFriendlyByteBuf buf) {
			return new GuardSummary(
					buf.readUUID(),
					buf.readUtf(MAX_GUARD_NAME_LENGTH),
					buf.readInt(),
					buf.readFloat(),
					buf.readFloat(),
					buf.readInt(),
					buf.readDouble(),
					buf.readInt(),
					buf.readUtf(MAX_GROUP_NAME_LENGTH),
					ItemStack.STREAM_CODEC.decode(buf),
					ItemStack.STREAM_CODEC.decode(buf),
					ItemStack.STREAM_CODEC.decode(buf),
					ItemStack.STREAM_CODEC.decode(buf),
					ItemStack.STREAM_CODEC.decode(buf));
		}
	}
}
