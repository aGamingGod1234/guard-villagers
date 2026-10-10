package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

final class GuardDiplomacyStateTest {
	private final UUID owner = UUID.randomUUID();
	private final UUID friend = UUID.randomUUID();

	@Test
	void addRemoveAndSelf() {
		GuardDiplomacyState state = new GuardDiplomacyState();
		assertEquals(GuardDiplomacyState.Change.ADDED, state.addToWhitelist(this.owner, this.friend));
		assertEquals(GuardDiplomacyState.Change.ALREADY_PRESENT, state.addToWhitelist(this.owner, this.friend));
		assertTrue(state.isWhitelisted(this.owner, this.friend));
		assertEquals(GuardDiplomacyState.Change.SELF, state.addToWhitelist(this.owner, this.owner));

		assertEquals(GuardDiplomacyState.Change.REMOVED, state.removeFromWhitelist(this.owner, this.friend));
		assertEquals(GuardDiplomacyState.Change.NOT_PRESENT, state.removeFromWhitelist(this.owner, this.friend));
		assertEquals(0, state.ownerCount(), "owners with empty lists are not stored");
	}

	@Test
	void whitelistAndBlacklistAreExclusive() {
		GuardDiplomacyState state = new GuardDiplomacyState();
		state.addToBlacklist(this.owner, this.friend);
		state.addToWhitelist(this.owner, this.friend);
		assertTrue(state.isWhitelisted(this.owner, this.friend));
		assertFalse(state.isBlacklisted(this.owner, this.friend));
	}

	@Test
	void listsAndOwnersAreBounded() {
		GuardDiplomacyState state = new GuardDiplomacyState();
		for (int i = 0; i < GuardDiplomacyState.MAX_LIST_SIZE; i++) {
			assertEquals(GuardDiplomacyState.Change.ADDED, state.addToWhitelist(this.owner, UUID.randomUUID()));
		}
		assertEquals(GuardDiplomacyState.Change.LIST_FULL, state.addToWhitelist(this.owner, UUID.randomUUID()));

		for (int i = 1; i < GuardDiplomacyState.MAX_OWNERS; i++) {
			state.addToWhitelist(UUID.randomUUID(), this.friend);
		}
		assertEquals(GuardDiplomacyState.Change.STATE_FULL, state.addToWhitelist(UUID.randomUUID(), this.friend));
		assertEquals(GuardDiplomacyState.Change.ADDED, state.addToBlacklist(this.owner, this.friend), "existing owners still work");
	}

	@Test
	void roundTripsThroughCodec() {
		GuardDiplomacyState state = new GuardDiplomacyState();
		state.addToWhitelist(this.owner, this.friend);
		GuardDiplomacyState decoded = GuardDiplomacyState.CODEC.parse(NbtOps.INSTANCE,
				GuardDiplomacyState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
		assertTrue(decoded.isWhitelisted(this.owner, this.friend));
	}
}
