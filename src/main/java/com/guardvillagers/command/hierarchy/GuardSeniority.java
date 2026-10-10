package com.guardvillagers.command.hierarchy;

import com.guardvillagers.entity.GuardEntity;
import java.util.Comparator;
import java.util.UUID;

/**
 * Who outranks whom, shared by village squad leader election and the owned-guard hierarchy. Greater means more senior:
 * higher level, then more XP, then longer service (earlier hire tick), then the larger UUID. Village guards were never
 * hired (hire tick 0 for all of them), so for them this is the old level, XP, UUID order.
 */
public final class GuardSeniority {
	/** Pure ordering key, so the rule can be unit-tested without Minecraft entities. */
	public record Key(int level, int experience, long hiredAtTick, UUID uuid) {
	}

	public static final Comparator<Key> KEY_ORDER = Comparator
			.comparingInt(Key::level)
			.thenComparingInt(Key::experience)
			.thenComparing(Key::hiredAtTick, Comparator.reverseOrder())
			.thenComparing(Key::uuid);

	public static final Comparator<GuardEntity> ORDER = (a, b) -> KEY_ORDER.compare(keyOf(a), keyOf(b));

	private GuardSeniority() {
	}

	public static Key keyOf(GuardEntity guard) {
		return new Key(guard.getLevel(), guard.getExperience(), guard.getHiredAtTick(), guard.getUUID());
	}

	/** True when {@code challenger} should replace {@code incumbent}: only a strictly higher level unseats a rank holder. */
	public static boolean outranksIncumbent(GuardEntity challenger, GuardEntity incumbent) {
		return challenger.getLevel() > incumbent.getLevel();
	}
}
