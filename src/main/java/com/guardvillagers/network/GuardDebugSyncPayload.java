package com.guardvillagers.network;

import com.guardvillagers.GuardVillagersMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record GuardDebugSyncPayload(boolean enabled, double range) implements CustomPacketPayload {
	public static final Type<GuardDebugSyncPayload> ID = new Type<>(GuardVillagersMod.id("debug_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GuardDebugSyncPayload> CODEC = CustomPacketPayload.codec(
		GuardDebugSyncPayload::write,
		GuardDebugSyncPayload::new
	);

	private GuardDebugSyncPayload(RegistryFriendlyByteBuf buf) {
		this(buf.readBoolean(), buf.readDouble());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeBoolean(this.enabled);
		buf.writeDouble(this.range);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
