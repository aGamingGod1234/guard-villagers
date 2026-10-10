package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guardvillagers.GuardSecurityLimits;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

final class GuardOwnershipStateTest {
	private final UUID owner = UUID.randomUUID();
	private final UUID otherOwner = UUID.randomUUID();

	@Test
	void unloadedGuardsCountNoMatterHowLongTheyWereUnseen() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID guard = UUID.randomUUID();
		state.record(guard, this.owner, 1_000L);

		assertEquals(1, state.countOwned(this.owner));
		assertTrue(state.guardsOf(this.owner).contains(guard), "a guard parked for years in an unloaded chunk still counts");
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

		assertEquals(0, state.countOwned(this.owner));
		assertEquals(1, state.countOwned(this.otherOwner));
		state.remove(guard);
		assertEquals(0, state.countOwned(this.otherOwner));
	}

	@Test
	void roundTripsThroughCodec() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID guard = UUID.randomUUID();
		state.record(guard, this.owner, 42L);

		Tag encoded = GuardOwnershipState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
		GuardOwnershipState decoded = GuardOwnershipState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

		assertEquals(1, decoded.countOwned(this.owner));
		assertTrue(decoded.guardsOf(this.owner).contains(guard));
	}

	@Test
	void keepsLastKnownLabelAndMarksDirtyWhenItChanges() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID guard = UUID.randomUUID();
		state.record(guard, this.owner, 0L, "Bob", 2);
		state.setDirty(false);

		state.record(guard, this.owner, 10L, "Bob", 2);
		assertFalse(state.isDirty());
		state.record(guard, this.owner, 20L, "Bob", 3);
		assertTrue(state.isDirty());
		state.record(guard, this.owner, 30L);
		assertEquals(3, state.get(guard).orElseThrow().groupIndex(), "a refresh without a label keeps the old one");

		state.record(guard, this.owner, 40L, "x".repeat(200), 3);
		Tag encoded = GuardOwnershipState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
		GuardOwnershipState decoded = GuardOwnershipState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		assertEquals(GuardOwnershipState.MAX_NAME_LENGTH, decoded.get(guard).orElseThrow().name().length());
	}

	@Test
	void manyOwnersNeverEvictEachOther() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID oldest = UUID.randomUUID();
		state.record(oldest, this.owner, 0L);
		// More rows than the old global cap (65,536), spread over owners who are all at the personal limit.
		int perOwner = GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER;
		for (int ownerIndex = 0; ownerIndex < 4_200; ownerIndex++) {
			UUID someOwner = new UUID(0L, ownerIndex);
			for (int i = 0; i < perOwner; i++) {
				state.record(UUID.randomUUID(), someOwner, 1_000L + ownerIndex);
			}
		}

		assertEquals(1, state.countOwned(this.owner), "the least recently seen guard of an owner under the limit is kept");
		assertEquals(4_200 * perOwner + 1, state.size());
	}

	@Test
	void perOwnerCapOnlyEvictsThatOwnersOldestRow() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID otherGuard = UUID.randomUUID();
		state.record(otherGuard, this.otherOwner, 0L);
		UUID oldest = UUID.randomUUID();
		state.record(oldest, this.owner, 1L);
		for (int i = 1; i < GuardOwnershipState.MAX_ROWS_PER_OWNER; i++) {
			state.record(UUID.randomUUID(), this.owner, 10L + i);
		}
		assertEquals(GuardOwnershipState.MAX_ROWS_PER_OWNER, state.countOwned(this.owner));

		UUID newest = UUID.randomUUID();
		state.record(newest, this.owner, 1_000L);

		assertEquals(GuardOwnershipState.MAX_ROWS_PER_OWNER, state.countOwned(this.owner));
		assertFalse(state.guardsOf(this.owner).contains(oldest));
		assertTrue(state.guardsOf(this.owner).contains(newest));
		assertEquals(1, state.countOwned(this.otherOwner));
		assertTrue(GuardOwnershipState.MAX_ROWS_PER_OWNER > GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER,
				"eviction must only ever hit owners already past the personal limit");
	}

	@Test
	void ownerChangeIntoAFullOwnerMakesRoom() {
		GuardOwnershipState state = new GuardOwnershipState();
		for (int i = 0; i < GuardOwnershipState.MAX_ROWS_PER_OWNER; i++) {
			state.record(UUID.randomUUID(), this.owner, i);
		}
		UUID moved = UUID.randomUUID();
		state.record(moved, this.otherOwner, 0L);
		state.record(moved, this.owner, 500L);

		assertEquals(GuardOwnershipState.MAX_ROWS_PER_OWNER, state.countOwned(this.owner));
		assertEquals(0, state.countOwned(this.otherOwner));
	}

	@Test
	void recordIfAbsentKeepsTheLiveRow() {
		GuardOwnershipState state = new GuardOwnershipState();
		UUID guard = UUID.randomUUID();
		state.record(guard, this.owner, 500L, "Live", 2);
		state.recordIfAbsent(guard, this.otherOwner, 0L, "Disk", 1);

		GuardOwnershipState.Entry entry = state.get(guard).orElseThrow();
		assertEquals(this.owner, entry.owner());
		assertEquals("Live", entry.name());
	}

	@Test
	void backfilledFlagRoundTripsAndOldFilesDefaultToNotBackfilled() {
		GuardOwnershipState state = new GuardOwnershipState();
		assertFalse(state.isBackfilled());
		state.setDirty(false);
		state.markBackfilled();
		assertTrue(state.isDirty());

		Tag encoded = GuardOwnershipState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
		assertTrue(GuardOwnershipState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow().isBackfilled());
		assertFalse(GuardOwnershipState.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow().isBackfilled());
	}
}
