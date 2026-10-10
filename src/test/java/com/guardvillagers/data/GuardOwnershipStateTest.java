package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

final class GuardOwnershipStateTest {
	private final UUID owner = UUID.randomUUID();
	private final UUID otherOwner = UUID.randomUUID();

	@Test
	void countsUnloadedGuardsUntilTheyExpire() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID guard = UUID.randomUUID();
		state.record(guard, this.owner, 1_000L);

		assertEquals(1, state.countOwned(this.owner, 1_000L + GuardOwnershipState.EXPIRY_TICKS));
		assertEquals(0, state.countOwned(this.owner, 1_001L + GuardOwnershipState.EXPIRY_TICKS));
		assertEquals(0, state.size(), "expired rows are pruned");
	}

	@Test
	void refreshOnlyMarksDirtyAfterTheRefreshWindow() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID guard = UUID.randomUUID();
		state.record(guard, this.owner, 0L);
		state.setDirty(false);

		state.record(guard, this.owner, GuardOwnershipState.REFRESH_TICKS - 1);
		assertFalse(state.isDirty());
		state.record(guard, this.owner, GuardOwnershipState.REFRESH_TICKS);
		assertTrue(state.isDirty());
	}

	@Test
	void ownerChangeMovesTheGuardAndRemoveForgetsIt() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID guard = UUID.randomUUID();
		state.record(guard, this.owner, 0L);
		state.record(guard, this.otherOwner, 0L);

		assertEquals(0, state.countOwned(this.owner, 0L));
		assertEquals(1, state.countOwned(this.otherOwner, 0L));
		state.remove(guard);
		assertEquals(0, state.countOwned(this.otherOwner, 0L));
	}

	@Test
	void roundTripsThroughCodec() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID guard = UUID.randomUUID();
		state.record(guard, this.owner, 42L);

		Tag encoded = GuardOwnershipState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
		GuardOwnershipState decoded = GuardOwnershipState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

		assertEquals(1, decoded.countOwned(this.owner, 42L));
		assertTrue(decoded.guardsOf(this.owner).contains(guard));
	}

	@Test
	void boundedByEvictingTheLeastRecentlySeenGuard() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID oldest = UUID.randomUUID();
		state.record(oldest, this.owner, 0L);
		for (int i = 1; i < GuardOwnershipState.MAX_ENTRIES; i++) {
			state.record(UUID.randomUUID(), this.otherOwner, i);
		}
		state.record(UUID.randomUUID(), this.otherOwner, GuardOwnershipState.MAX_ENTRIES);

		assertEquals(GuardOwnershipState.MAX_ENTRIES, state.size());
		assertEquals(0, state.countOwned(this.owner, GuardOwnershipState.MAX_ENTRIES));
	}
}
