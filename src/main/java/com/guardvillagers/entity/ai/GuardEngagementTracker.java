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
	private final Map<UUID, TargetEngagements> byTarget = new HashMap<>();
	private long lastSweepTick = Long.MIN_VALUE;
	private long latestTick = Long.MIN_VALUE;

	public GuardEngagementTracker(int entryTtlTicks, int maxTargets) {
		this.entryTtlTicks = Math.max(1, entryTtlTicks);
		this.maxTargets = Math.max(1, maxTargets);
	}

	/**
	 * Records that {@code guard} is engaging {@code target} at {@code distanceSq} and returns the
	 * guard's rank: how many other live engagers are closer to the target.
	 */
	public synchronized int report(UUID target, UUID guard, double distanceSq, long tick) {
		return this.report(target, guard, distanceSq, true, tick);
	}

	/** General engagement counts include archers and held guards, but melee ranks only use eligible frontliners. */
	public synchronized int report(UUID target, UUID guard, double distanceSq, boolean frontlineEligible, long tick) {
		this.observe(tick);
		this.maybeSweep(tick);
		TargetEngagements engagers = this.byTarget.get(target);
		if (engagers == null) {
			if (this.byTarget.size() >= this.maxTargets) {
				this.sweep(tick, true);
			}
			engagers = new TargetEngagements();
			this.byTarget.put(target, engagers);
		}
		engagers.refreshCount(tick, this.entryTtlTicks);
		Engagement engagement = engagers.get(guard);
		if (engagement == null || tick - engagement.tick > this.entryTtlTicks) engagers.liveCount++;
		if (engagement == null) {
			engagement = new Engagement();
			engagers.put(guard, engagement);
		}
		engagement.distanceSq = distanceSq;
		engagement.frontlineEligible = frontlineEligible;
		engagement.tick = tick;

		int rank = 0;
		for (Map.Entry<UUID, Engagement> entry : engagers.entrySet()) {
			Engagement other = entry.getValue();
			if (!other.frontlineEligible || entry.getKey().equals(guard) || tick - other.tick > this.entryTtlTicks) {
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
		this.observe(tick);
		TargetEngagements engagers = this.byTarget.get(target);
		if (engagers == null) {
			return 0;
		}
		engagers.refreshCount(tick, this.entryTtlTicks);
		Engagement excluded = engagers.get(exclude);
		return engagers.liveCount - (excluded != null && tick - excluded.tick <= this.entryTtlTicks ? 1 : 0);
	}

	public synchronized void release(UUID target, UUID guard) {
		if (target == null) {
			return;
		}
		TargetEngagements engagers = this.byTarget.get(target);
		if (engagers != null) {
			Engagement removed = engagers.remove(guard);
			if (removed != null && engagers.countedTick != Long.MIN_VALUE
					&& engagers.countedTick - removed.tick <= this.entryTtlTicks) engagers.liveCount--;
			if (engagers.isEmpty()) {
				this.byTarget.remove(target);
			}
		}
	}

	public synchronized int targetCount() {
		return this.byTarget.size();
	}

	public synchronized void clear() {
		this.byTarget.clear();
		this.lastSweepTick = Long.MIN_VALUE;
		this.latestTick = Long.MIN_VALUE;
	}

	/** A different world loaded into the same JVM can start at a lower game time; drop everything then. */
	private void observe(long tick) {
		if (this.latestTick != Long.MIN_VALUE && tick < this.latestTick) {
			this.clear();
		}
		this.latestTick = tick;
	}

	private void maybeSweep(long tick) {
		if (this.lastSweepTick == Long.MIN_VALUE || tick - this.lastSweepTick >= 100) {
			this.sweep(tick, false);
		}
	}

	private void sweep(long tick, boolean forceCapacity) {
		this.lastSweepTick = tick;
		Iterator<TargetEngagements> iterator = this.byTarget.values().iterator();
		while (iterator.hasNext()) {
			TargetEngagements engagers = iterator.next();
			engagers.countedTick = Long.MIN_VALUE;
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

	/** Recount once per target/tick; reports and releases keep that count exact within the tick. */
	private static final class TargetEngagements extends HashMap<UUID, Engagement> {
		private long countedTick = Long.MIN_VALUE;
		private int liveCount;

		private void refreshCount(long tick, int ttl) {
			if (this.countedTick == tick) return;
			this.countedTick = tick;
			this.liveCount = 0;
			for (Engagement engagement : this.values()) {
				if (tick - engagement.tick <= ttl) this.liveCount++;
			}
		}
	}

	private static final class Engagement {
		private double distanceSq;
		private boolean frontlineEligible;
		private long tick;
	}
}
