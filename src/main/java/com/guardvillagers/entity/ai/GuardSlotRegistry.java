package com.guardvillagers.entity.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hands out dense, stable slot indices to guards that share one physical anchor (an owner, a home
 * point, a rally point...). Every guard asking for a slot around the same anchor gets a distinct
 * index, so formation offsets never overlap. Members that stop asking expire after
 * {@code memberTtlTicks}; rosters nobody uses are swept, and the roster count is capped.
 *
 * <p>No world queries are involved, which replaces the old per-call 96x48x96 entity scan + sort.
 */
public final class GuardSlotRegistry<K> {
	private static final Comparator<Member> MEMBER_ORDER = Comparator
			.comparingLong((Member member) -> member.sortKey)
			.thenComparing(member -> member.id);

	private final int memberTtlTicks;
	private final int maxRosters;
	private final Map<K, Roster> rosters = new HashMap<>();
	private long lastSweepTick = Long.MIN_VALUE;
	private long latestTick = Long.MIN_VALUE;

	public GuardSlotRegistry(int memberTtlTicks, int maxRosters) {
		this.memberTtlTicks = Math.max(1, memberTtlTicks);
		this.maxRosters = Math.max(1, maxRosters);
	}

	/** Registers (or refreshes) {@code member} under {@code key} and returns its slot index. */
	public synchronized int indexOf(K key, UUID member, long sortKey, long tick) {
		this.observe(tick);
		this.maybeSweep(tick);
		Roster roster = this.rosters.get(key);
		if (roster == null) {
			roster = new Roster();
			this.rosters.put(key, roster);
			if (this.rosters.size() > this.maxRosters) {
				this.evictLeastRecentlyUsed(roster);
			}
		}
		roster.lastUsedTick = tick;
		return roster.touch(member, sortKey, tick, this.memberTtlTicks);
	}

	/** Number of live members under {@code key}. */
	public synchronized int size(K key, long tick) {
		this.observe(tick);
		Roster roster = this.rosters.get(key);
		if (roster == null) {
			return 0;
		}
		roster.expire(tick, this.memberTtlTicks);
		return roster.members.size();
	}

	public synchronized void release(K key, UUID member) {
		Roster roster = this.rosters.get(key);
		if (roster != null && roster.members.remove(member) != null) {
			roster.dirty = true;
			if (roster.members.isEmpty()) {
				this.rosters.remove(key);
			}
		}
	}

	public synchronized int rosterCount() {
		return this.rosters.size();
	}

	public synchronized void clear() {
		this.rosters.clear();
		this.lastSweepTick = Long.MIN_VALUE;
		this.latestTick = Long.MIN_VALUE;
	}

	/**
	 * Game time only moves backwards when a different world is loaded into the same JVM. Every
	 * stored tick then lies in the future, so nothing would ever expire; start over instead.
	 */
	private void observe(long tick) {
		if (this.latestTick != Long.MIN_VALUE && tick < this.latestTick) {
			this.clear();
		}
		this.latestTick = tick;
	}

	private void maybeSweep(long tick) {
		if (this.lastSweepTick != Long.MIN_VALUE && tick - this.lastSweepTick < this.memberTtlTicks) {
			return;
		}
		this.lastSweepTick = tick;
		Iterator<Roster> iterator = this.rosters.values().iterator();
		while (iterator.hasNext()) {
			Roster roster = iterator.next();
			if (tick - roster.lastUsedTick > this.memberTtlTicks) {
				iterator.remove();
			}
		}
	}

	private void evictLeastRecentlyUsed(Roster keep) {
		K oldestKey = null;
		long oldestTick = Long.MAX_VALUE;
		for (Map.Entry<K, Roster> entry : this.rosters.entrySet()) {
			if (entry.getValue() != keep && entry.getValue().lastUsedTick < oldestTick) {
				oldestTick = entry.getValue().lastUsedTick;
				oldestKey = entry.getKey();
			}
		}
		if (oldestKey != null) {
			this.rosters.remove(oldestKey);
		}
	}

	private static final class Member {
		private final UUID id;
		private long sortKey;
		private long lastSeenTick;
		private int index;

		private Member(UUID id, long sortKey) {
			this.id = id;
			this.sortKey = sortKey;
		}
	}

	private static final class Roster {
		private final Map<UUID, Member> members = new HashMap<>();
		private final List<Member> ordered = new ArrayList<>();
		private boolean dirty;
		private long lastUsedTick;
		private long lastExpiryTick = Long.MIN_VALUE;

		private int touch(UUID id, long sortKey, long tick, int ttl) {
			Member member = this.members.get(id);
			if (member == null) {
				member = new Member(id, sortKey);
				this.members.put(id, member);
				this.dirty = true;
			} else if (member.sortKey != sortKey) {
				member.sortKey = sortKey;
				this.dirty = true;
			}
			member.lastSeenTick = tick;
			this.expire(tick, ttl);
			if (this.dirty) {
				this.ordered.clear();
				this.ordered.addAll(this.members.values());
				this.ordered.sort(MEMBER_ORDER);
				for (int i = 0; i < this.ordered.size(); i++) {
					this.ordered.get(i).index = i;
				}
				this.dirty = false;
			}
			return member.index;
		}

		private void expire(long tick, int ttl) {
			if (this.lastExpiryTick != Long.MIN_VALUE && tick - this.lastExpiryTick < 10) {
				return;
			}
			this.lastExpiryTick = tick;
			if (this.members.values().removeIf(member -> tick - member.lastSeenTick > ttl)) {
				this.dirty = true;
			}
		}
	}
}
