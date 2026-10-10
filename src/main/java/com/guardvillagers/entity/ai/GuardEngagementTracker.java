package com.guardvillagers.entity.ai;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which guards are currently fighting which target so target scoring can spread a large
 * group over several enemies and melee guards beyond the first few can hold back instead of
 * piling onto one mob. Entries expire on their own, so guards that unload or die without telling
 * the tracker cannot leak.
 */
public final class GuardEngagementTracker {
	private final int entryTtlTicks;
	private final int maxTargets;
	private final Map<UUID, Map<UUID, Engagement>> byTarget = new HashMap<>();
	private long lastSweepTick = Long.MIN_VALUE;

	public GuardEngagementTracker(int entryTtlTicks, int maxTargets) {
		this.entryTtlTicks = Math.max(1, entryTtlTicks);
		this.maxTargets = Math.max(1, maxTargets);
	}

	/**
	 * Records that {@code guard} is engaging {@code target} at {@code distanceSq} and returns the
	 * guard's rank: how many other live engagers are closer to the target.
	 */
	public synchronized int report(UUID target, UUID guard, double distanceSq, long tick) {
		this.maybeSweep(tick);
		Map<UUID, Engagement> engagers = this.byTarget.get(target);
		if (engagers == null) {
			if (this.byTarget.size() >= this.maxTargets) {
				this.sweep(tick, true);
			}
			engagers = new HashMap<>();
			this.byTarget.put(target, engagers);
		}
		Engagement engagement = engagers.get(guard);
		if (engagement == null) {
			engagement = new Engagement();
			engagers.put(guard, engagement);
		}
		engagement.distanceSq = distanceSq;
		engagement.tick = tick;

		int rank = 0;
		for (Map.Entry<UUID, Engagement> entry : engagers.entrySet()) {
			Engagement other = entry.getValue();
			if (entry.getKey().equals(guard) || tick - other.tick > this.entryTtlTicks) {
				continue;
			}
			if (other.distanceSq < distanceSq
					|| (other.distanceSq == distanceSq && entry.getKey().compareTo(guard) < 0)) {
				rank++;
			}
		}
		return rank;
	}

	/** Number of live engagers of {@code target}, not counting {@code exclude}. */
	public synchronized int count(UUID target, UUID exclude, long tick) {
		Map<UUID, Engagement> engagers = this.byTarget.get(target);
		if (engagers == null) {
			return 0;
		}
		int count = 0;
		for (Map.Entry<UUID, Engagement> entry : engagers.entrySet()) {
			if (!entry.getKey().equals(exclude) && tick - entry.getValue().tick <= this.entryTtlTicks) {
				count++;
			}
		}
		return count;
	}

	public synchronized void release(UUID target, UUID guard) {
		if (target == null) {
			return;
		}
		Map<UUID, Engagement> engagers = this.byTarget.get(target);
		if (engagers != null) {
			engagers.remove(guard);
			if (engagers.isEmpty()) {
				this.byTarget.remove(target);
			}
		}
	}

	public synchronized int targetCount() {
		return this.byTarget.size();
	}

	private void maybeSweep(long tick) {
		if (this.lastSweepTick == Long.MIN_VALUE || tick - this.lastSweepTick >= 100) {
			this.sweep(tick, false);
		}
	}

	private void sweep(long tick, boolean forceCapacity) {
		this.lastSweepTick = tick;
		Iterator<Map<UUID, Engagement>> iterator = this.byTarget.values().iterator();
		while (iterator.hasNext()) {
			Map<UUID, Engagement> engagers = iterator.next();
			engagers.values().removeIf(engagement -> tick - engagement.tick > this.entryTtlTicks);
			if (engagers.isEmpty()) {
				iterator.remove();
			}
		}
		if (forceCapacity && this.byTarget.size() >= this.maxTargets) {
			// Every target is live; drop an arbitrary one rather than grow without bound.
			Iterator<UUID> keys = this.byTarget.keySet().iterator();
			if (keys.hasNext()) {
				keys.next();
				keys.remove();
			}
		}
	}

	private static final class Engagement {
		private double distanceSq;
		private long tick;
	}
}
