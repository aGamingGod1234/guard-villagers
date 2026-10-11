package com.guardvillagers.network;

import com.guardvillagers.GuardVillagersMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client result of a {@code /guards groups add|rename|assign|assignmany} command, so the tactics screen can
 * confirm or roll back optimistic edits instead of guessing from timeouts. Sent once per command, success or failure.
 *
 * @param action    which command produced this result
 * @param groupRow  zero-based row the command targeted; {@code -1} for unassign or when no row applies
 * @param applied   number of changes applied (groups added/renamed, guards assigned)
 * @param rejected  number of requested changes that were not applied
 * @param reasonKey translation key explaining the first rejection, blank on full success
 */
public record GuardGroupResultPayload(Action action, int groupRow, int applied, int rejected, String reasonKey)
		implements CustomPacketPayload {
	public static final int MAX_REASON_KEY_LENGTH = 96;
	public static final Type<GuardGroupResultPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(GuardVillagersMod.MOD_ID, "group_result"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GuardGroupResultPayload> CODEC = CustomPacketPayload.codec(
			GuardGroupResultPayload::write,
			GuardGroupResultPayload::new);

	public enum Action {
		ADD,
		RENAME,
		ASSIGN
	}

	public GuardGroupResultPayload {
		action = action == null ? Action.ASSIGN : action;
		applied = Math.max(0, applied);
		rejected = Math.max(0, rejected);
		reasonKey = GuardRosterSyncPayload.trim(reasonKey, MAX_REASON_KEY_LENGTH);
	}

	private GuardGroupResultPayload(RegistryFriendlyByteBuf buf) {
		this(readAction(buf.readByte()), buf.readInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_REASON_KEY_LENGTH));
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeByte(this.action.ordinal());
		buf.writeInt(this.groupRow);
		buf.writeVarInt(this.applied);
		buf.writeVarInt(this.rejected);
		buf.writeUtf(this.reasonKey, MAX_REASON_KEY_LENGTH);
	}

	private static Action readAction(int ordinal) {
		Action[] values = Action.values();
		if (ordinal < 0 || ordinal >= values.length) {
			throw new IllegalArgumentException("Unknown group result action " + ordinal);
		}
		return values[ordinal];
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
