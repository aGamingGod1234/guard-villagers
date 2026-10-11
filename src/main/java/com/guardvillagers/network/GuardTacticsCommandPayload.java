package com.guardvillagers.network;

import com.guardvillagers.GuardVillagersMod;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** UI actions use a separate channel from chat; the server validates and paces them. */
public record GuardTacticsCommandPayload(String command) implements CustomPacketPayload {
	public static final int MAX_LENGTH = 256;
	private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
	private static final String GROUP_ROW = "(?:[1-9]|[1-5][0-9]|6[0-4])";
	private static final String ROW_OR_UNASSIGNED = "(?:0|" + GROUP_ROW + ")";
	private static final Pattern ALLOWED = Pattern.compile("guards (?:follow|stay|appoint (?:commander|captain) " + UUID_PATTERN
			+ "|groups (?:add|(?:follow|stay) " + ROW_OR_UNASSIGNED + "|rename " + GROUP_ROW + " [^\\p{Cntrl}]{1,24}"
			+ "|assign " + UUID_PATTERN + " " + ROW_OR_UNASSIGNED
			+ "|assignmany " + ROW_OR_UNASSIGNED + " " + UUID_PATTERN + "(?: " + UUID_PATTERN + ")*))");
	public static final Type<GuardTacticsCommandPayload> ID = new Type<>(
			Identifier.fromNamespaceAndPath(GuardVillagersMod.MOD_ID, "tactics_command"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GuardTacticsCommandPayload> CODEC = CustomPacketPayload.codec(
			GuardTacticsCommandPayload::write, GuardTacticsCommandPayload::new);

	public GuardTacticsCommandPayload {
		Objects.requireNonNull(command, "command");
		if (command.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("Tactics command is too long");
		}
	}

	private GuardTacticsCommandPayload(RegistryFriendlyByteBuf buf) {
		this(buf.readUtf(MAX_LENGTH));
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(this.command, MAX_LENGTH);
	}

	public static boolean isAllowed(String command) {
		return command != null && command.length() <= MAX_LENGTH && ALLOWED.matcher(command).matches();
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
