package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuardReputationDecayTest {
	@Test
	void decayMovesBothSidesTowardNeutral() {
		assertEquals(0.69D, GuardReputationState.decayTowardNeutral(0.70D, 0.01D), 1e-12);
		assertEquals(0.11D, GuardReputationState.decayTowardNeutral(0.10D, 0.01D), 1e-12);
		assertEquals(0.50D, GuardReputationState.decayTowardNeutral(0.505D, 0.01D));
		assertEquals(0.50D, GuardReputationState.decayTowardNeutral(0.495D, 0.01D));
	}

	@Test
	void idlePlayersNeverBecomeHostileAndNeutralRowsAreDropped() {
		GuardReputationState state = new GuardReputationState();
		UUID trader = UUID.randomUUID();
		UUID griefer = UUID.randomUUID();
		state.set(trader, 0.5025D);
		state.set(griefer, 0.0D);

		for (int i = 0; i < 100; i++) {
			state.decayAll(0.01D);
		}

		assertEquals(GuardReputationState.DEFAULT_REPUTATION, state.get(trader));
		assertEquals(GuardReputationState.DEFAULT_REPUTATION, state.get(griefer));
		assertEquals(0, state.trackedPlayerCount());
	}

	@Test
	void ignoresNonPositiveOrNonFiniteSteps() {
		GuardReputationState state = new GuardReputationState();
		UUID player = UUID.randomUUID();
		state.set(player, 0.9D);
		state.decayAll(0.0D);
		state.decayAll(-1.0D);
		state.decayAll(Double.NaN);
		assertEquals(0.9D, state.get(player));
	}
}
