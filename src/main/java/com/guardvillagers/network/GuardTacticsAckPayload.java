package com.guardvillagers.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-tick acknowledgement; command errors and group results retain their existing feedback paths. */
public record GuardTacticsAckPayload() implements CustomPacketPayload {
	public static final Type<GuardTacticsAckPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("guardvillagers", "tactics_ack"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GuardTacticsAckPayload> CODEC = CustomPacketPayload.codec(
			(payload, buf) -> {}, buf -> new GuardTacticsAckPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
