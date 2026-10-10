package com.guardvillagers.entity.ai;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.navigation.GuardGrounding;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Assigns each guard a distinct standing slot around a shared anchor.
 *
 * <p>Why the old version clumped: slots were computed per "movement group" (owner + group column),
 * but every group used the same ring radii around the same owner, and rotations that were
 * multiples of 45 degrees on 8-slot rings. Three columns therefore mapped onto the same eight
 * positions, and the 12-guard "cohort" rings were centred 4.4 blocks out, overlapping ring one.
 * Now every guard that uses an anchor shares one roster ({@link GuardSlotRegistry}), so indices
 * are unique and the ring layout ({@link FormationGeometry}) never overlaps.
 *
 * <p>Slots are grounded with {@link GuardGrounding} (pathfinder walkability, closest to the anchor
 * height) and cached per guard until the anchor block or the slot index changes.
 */
public final class GuardMovementSlotResolver {
	public static final double DEFAULT_FIRST_RADIUS = 2.0D;
	public static final double DEFAULT_RING_STEP = 1.6D;
	public static final double DEFAULT_ARC_SPACING = 1.5D;
	private static final int MEMBER_TTL_TICKS = 60;
	private static final int MAX_ROSTERS = 2048;
	private static final int CACHE_MAX_AGE_TICKS = 60;
	private static final int GROUND_MAX_UP = 3;
	private static final int GROUND_MAX_DOWN = 3;
	private static final int GROUND_NEIGHBOUR_RADIUS = 1;

	private static final GuardSlotRegistry<AnchorKey> REGISTRY = new GuardSlotRegistry<>(MEMBER_TTL_TICKS, MAX_ROSTERS);

	public enum AnchorKind {
		FOLLOW_OWNER,
		HOME,
		RALLY,
		CROWD,
		RAID,
		RETREAT,
		SPAWN_SPREAD
	}

	/** Identity of a shared anchor; follow anchors are keyed by owner so they move with the player. */
	public record AnchorKey(AnchorKind kind, ResourceKey<Level> dimension, UUID owner, long anchor) {
		public static AnchorKey follow(UUID owner) {
			return new AnchorKey(AnchorKind.FOLLOW_OWNER, null, owner, 0L);
		}

		public static AnchorKey at(AnchorKind kind, ResourceKey<Level> dimension, BlockPos anchor) {
			return new AnchorKey(kind, dimension, null, anchor.asLong());
		}
	}

	/** Per-guard memo of the last resolved slot. */
	public static final class SlotCache {
		private AnchorKey key;
		private BlockPos anchor;
		private int index = -1;
		private BlockPos result;
		private Vec3 exact;
		private long computedTick;

		/**
		 * Exact ring position of the last resolved slot when it landed in the requested column,
		 * otherwise the centre of the grounded block. Lets guards stand on the ring rather than on
		 * block centres (which put neighbours as close as one block apart).
		 */
		public Vec3 exactPosition() {
			return this.exact;
		}

		public void clear() {
			this.key = null;
			this.anchor = null;
			this.index = -1;
			this.result = null;
			this.exact = null;
		}
	}

	private GuardMovementSlotResolver() {
	}

	/**
	 * Grounded slot for {@code guard} around {@code anchor}. Falls back to a neighbouring column,
	 * then half-way toward the anchor, then the anchor itself.
	 */
	public static BlockPos resolve(GuardEntity guard, SlotCache cache, AnchorKey key, BlockPos anchor,
			boolean allowCenter, long tick) {
		int index = REGISTRY.indexOf(key, guard.getUUID(), sortKey(guard), tick);
		if (cache.result != null && key.equals(cache.key) && anchor.equals(cache.anchor) && index == cache.index
				&& tick - cache.computedTick < CACHE_MAX_AGE_TICKS) {
			return cache.result;
		}

		double[] offset = new double[2];
		FormationGeometry.slotOffset(index, allowCenter, DEFAULT_FIRST_RADIUS, DEFAULT_RING_STEP, DEFAULT_ARC_SPACING,
				rotationFor(key), offset);
		BlockPos grounded = groundOffset(guard, anchor, offset[0], offset[1]);
		if (grounded == null) {
			grounded = groundOffset(guard, anchor, offset[0] * 0.5D, offset[1] * 0.5D);
		}
		if (grounded == null) {
			grounded = GuardGrounding.findStandableNear(guard, anchor.getX(), anchor.getZ(), anchor.getY(), GROUND_MAX_UP,
					GROUND_MAX_DOWN, GROUND_NEIGHBOUR_RADIUS);
		}
		if (grounded == null) {
			grounded = anchor.immutable();
		}

		cache.key = key;
		cache.anchor = anchor.immutable();
		cache.index = index;
		cache.result = grounded;
		double exactX = anchor.getX() + 0.5D + offset[0];
		double exactZ = anchor.getZ() + 0.5D + offset[1];
		boolean sameColumn = (int) Math.floor(exactX) == grounded.getX() && (int) Math.floor(exactZ) == grounded.getZ();
		cache.exact = sameColumn ? new Vec3(exactX, grounded.getY(), exactZ) : Vec3.atBottomCenterOf(grounded);
		cache.computedTick = tick;
		return grounded;
	}

	/** Number of guards currently sharing {@code key}. */
	public static int occupancy(AnchorKey key, long tick) {
		return REGISTRY.size(key, tick);
	}

	public static void release(AnchorKey key, UUID guard) {
		if (key != null) {
			REGISTRY.release(key, guard);
		}
	}

	/** Groups stay contiguous in the formation: assigned groups first by index, then by column. */
	static long sortKey(GuardEntity guard) {
		return ((long) (guard.getGroupIndex() + 1) << 8) | (guard.getGroupColumn() & 0xFF);
	}

	private static BlockPos groundOffset(GuardEntity guard, BlockPos anchor, double offsetX, double offsetZ) {
		int x = (int) Math.floor(anchor.getX() + 0.5D + offsetX);
		int z = (int) Math.floor(anchor.getZ() + 0.5D + offsetZ);
		return GuardGrounding.findStandableNear(guard, x, z, anchor.getY(), GROUND_MAX_UP, GROUND_MAX_DOWN,
				GROUND_NEIGHBOUR_RADIUS);
	}

	private static double rotationFor(AnchorKey key) {
		// Fixed per anchor so the layout does not spin when the roster changes.
		return Math.floorMod(key.hashCode(), 360) * (Math.PI / 180.0D);
	}
}
