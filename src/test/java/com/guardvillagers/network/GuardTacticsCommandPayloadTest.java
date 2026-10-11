package com.guardvillagers.network;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class GuardTacticsCommandPayloadTest {
	private static final String GUARD = "12345678-1234-1234-1234-123456789abc";

	@Test
	void onlyBoundedTacticsOperationsUseTheNonChatTransport() {
		for (String command : List.of("guards follow", "guards stay", "guards groups add", "guards groups follow 64",
				"guards groups stay 1", "guards groups rename 1 Alpha /say hi", "guards appoint captain " + GUARD,
				"guards appoint commander " + GUARD, "guards groups assignmany 0 " + GUARD + " " + GUARD)) {
			assertTrue(GuardTacticsCommandPayload.isAllowed(command), command);
		}
		for (String command : List.of("op somebody", "guards voice reload", "guards say hold", "guards trust add Alice",
				"guards follow Alpha", "guards groups add\nsay hi", "guards groups rename 1 " + "x".repeat(25),
				"guards groups assignmany 1 " + (GUARD + " ").repeat(64))) {
			assertFalse(GuardTacticsCommandPayload.isAllowed(command), command);
		}
	}

	@Test
	void commandAndAcknowledgementRoundTrip() {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			GuardTacticsCommandPayload payload = new GuardTacticsCommandPayload("guards groups assignmany 1 " + GUARD);
			GuardTacticsCommandPayload.CODEC.encode(buf, payload);
			assertEquals(payload, GuardTacticsCommandPayload.CODEC.decode(buf));
			GuardTacticsAckPayload.CODEC.encode(buf, new GuardTacticsAckPayload());
			assertEquals(new GuardTacticsAckPayload(), GuardTacticsAckPayload.CODEC.decode(buf));
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}
}
