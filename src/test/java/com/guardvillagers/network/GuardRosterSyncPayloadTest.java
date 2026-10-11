package com.guardvillagers.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class GuardRosterSyncPayloadTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void roundTripsLoadedAndUnloadedGuardsIncludingEmptySlots() {
		UUID loadedId = UUID.randomUUID();
		UUID unloadedId = UUID.randomUUID();
		GuardRosterSyncPayload.GuardSummary loaded = new GuardRosterSyncPayload.GuardSummary(
				loadedId, "Guard Alice", 3, 18.0F, 20.0F, 40, 12.5D, 0, "Alpha",
				ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
				true, "DEFENSIVE", "FOLLOW", "BOWMAN", true, false, false, true, 2);
		GuardRosterSyncPayload.GuardSummary unloaded = GuardRosterSyncPayload.GuardSummary.unloaded(unloadedId, "Guard Bob", 0, "Alpha");
		GuardRosterSyncPayload payload = new GuardRosterSyncPayload(List.of("Alpha"), List.of(loaded, unloaded));

		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		GuardRosterSyncPayload.CODEC.encode(buf, payload);
		GuardRosterSyncPayload decoded = GuardRosterSyncPayload.CODEC.decode(buf);

		assertEquals(List.of("Alpha"), decoded.groupNames());
		GuardRosterSyncPayload.GuardSummary first = decoded.guards().get(0);
		assertTrue(first.loaded());
		assertTrue(first.staying());
		assertFalse(first.following());
		assertTrue(first.hasHome());
		assertEquals("DEFENSIVE", first.behavior());
		assertEquals("BOWMAN", first.role());
		assertEquals(2, first.rank());
		assertTrue(first.helmet().isEmpty(), "empty equipment must encode (the old STREAM_CODEC threw)");
		assertEquals(payload.contentHash(), decoded.contentHash());
		GuardRosterSyncPayload.GuardSummary second = decoded.guards().get(1);
		assertFalse(second.loaded());
		assertEquals(unloadedId, second.guardUuid());
		assertEquals("Guard Bob", second.displayName());
		assertEquals(0, second.rank());
	}

	@Test
	void constructorTrimsStringsToWireLimits() {
		GuardRosterSyncPayload.GuardSummary summary = GuardRosterSyncPayload.GuardSummary.unloaded(
				UUID.randomUUID(), "n".repeat(500), 0, "g".repeat(500));
		GuardRosterSyncPayload payload = new GuardRosterSyncPayload(List.of("g".repeat(500)), List.of(summary));

		assertEquals(GuardRosterSyncPayload.MAX_GUARD_NAME_LENGTH, payload.guards().getFirst().displayName().length());
		assertEquals(GuardRosterSyncPayload.MAX_GROUP_NAME_LENGTH, payload.guards().getFirst().groupName().length());
		assertEquals(GuardRosterSyncPayload.MAX_GROUP_NAME_LENGTH, payload.groupNames().getFirst().length());
	}

	@Test
	void rosterBeyondOldCountLimitIsSplitWithoutLosingRows() {
		var rows = java.util.stream.IntStream.range(0, 2500).mapToObj(i ->
				GuardRosterSyncPayload.GuardSummary.unloaded(new UUID(0, i), "Guard " + i, 0, "Alpha")).toList();
		var payload = new GuardRosterSyncPayload(List.of("Alpha"), rows);
		var packets = payload.chunks(RegistryAccess.EMPTY, 42);
		assertEquals(3, packets.size());
		int received = 0;
		for (var packet : packets) {
			var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
			try {
				GuardRosterSyncPayload.CODEC.encode(buf, packet);
				assertTrue(buf.readableBytes() <= GuardRosterSyncPayload.MAX_PACKET_BYTES);
				var decoded = GuardRosterSyncPayload.CODEC.decode(buf);
				assertEquals(received, decoded.offset());
				assertEquals(42, decoded.snapshotId());
				received += decoded.guards().size();
				assertEquals(received == rows.size(), decoded.last());
			} finally { buf.release(); }
		}
		assertEquals(2500, received);
	}

	@Test
	void groupResultRoundTrips() {
		GuardGroupResultPayload result = new GuardGroupResultPayload(GuardGroupResultPayload.Action.ASSIGN, 2, 3, 1,
				"commands.guardvillagers.groups.guard_not_found");
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		GuardGroupResultPayload.CODEC.encode(buf, result);
		assertEquals(result, GuardGroupResultPayload.CODEC.decode(buf));
	}
}
