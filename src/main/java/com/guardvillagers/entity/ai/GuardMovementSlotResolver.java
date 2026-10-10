package com.guardvillagers.entity.ai;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class GuardMovementSlotResolver {
	private static final double GROUP_QUERY_RADIUS = 48.0D;
	private static final double GROUP_QUERY_HEIGHT = 24.0D;
	private static final int GROUND_SEARCH_VERTICAL_RANGE = 6;
	private static final int MAX_MOVEMENT_COHORT_SIZE = 12;
	private static final int FOLLOW_LANE_COUNT = 3;
	private static final double FOLLOW_MIN_TRAIL_DISTANCE = 2.5D;
	private static final double FOLLOW_ROW_SPACING = 1.5D;
	private static final double COHORT_ANCHOR_SPACING_MULTIPLIER = 2.5D;
	private static final Comparator<GuardEntity> SLOT_ORDER = Comparator
			.comparingInt(GuardEntity::getGroupIndex)
			.thenComparingInt(GuardEntity::getGroupColumn)
			.thenComparing(entity -> entity.getUUID());

	private GuardMovementSlotResolver() {
	}

	public static Vec3 resolveDynamicSlot(ServerLevel world, GuardEntity guard, Vec3 anchor, double spacing,
			boolean allowCenter) {
		int slotOrder = resolveSlotOrder(world, guard, anchor);
		double rotation = rotationForGuard(guard);
		int cohortIndex = slotOrder / MAX_MOVEMENT_COHORT_SIZE;
		int localSlotOrder = slotOrder % MAX_MOVEMENT_COHORT_SIZE;
		Vec3 offset = offsetForSlot(localSlotOrder, spacing, allowCenter, rotation);
		offset = offset.add(cohortAnchorOffset(cohortIndex, spacing, rotation));
		return anchor.add(offset);
	}

	public static BlockPos resolveGroundSlot(ServerLevel world, GuardEntity guard, BlockPos anchor, double spacing,
			boolean allowCenter) {
		Vec3 slot = resolveDynamicSlot(world, guard, Vec3.atBottomCenterOf(anchor), spacing, allowCenter);
		BlockPos candidateOrigin = BlockPos.containing(slot.x, anchor.getY(), slot.z);
		return groundSlotNear(world, candidateOrigin, anchor);
	}

	public static BlockPos resolveFollowCatchUpSlot(ServerLevel world, GuardEntity guard, Vec3 anchor, double spacing) {
		int slotOrder = resolveSlotOrder(world, guard, anchor);
		int cohortIndex = slotOrder / MAX_MOVEMENT_COHORT_SIZE;
		int localSlotOrder = slotOrder % MAX_MOVEMENT_COHORT_SIZE;
		int laneIndex = Math.floorMod(localSlotOrder, FOLLOW_LANE_COUNT) - (FOLLOW_LANE_COUNT / 2);
		int rowIndex = localSlotOrder / FOLLOW_LANE_COUNT;
		double rotation = rotationForGuard(guard);

		Vec3 trailDirection = guard.position().subtract(anchor);
		trailDirection = new Vec3(trailDirection.x, 0.0D, trailDirection.z);
		if (trailDirection.lengthSqr() < 1.0E-4D) {
			trailDirection = new Vec3(Math.cos(rotation), 0.0D, Math.sin(rotation));
		} else {
			trailDirection = trailDirection.normalize();
		}

		Vec3 right = new Vec3(-trailDirection.z, 0.0D, trailDirection.x);
		double trailDistance = FOLLOW_MIN_TRAIL_DISTANCE + rowIndex * FOLLOW_ROW_SPACING;
		Vec3 slot = anchor.add(trailDirection.scale(trailDistance))
				.add(right.scale(laneIndex * spacing))
				.add(cohortAnchorOffset(cohortIndex, spacing, rotation));
		BlockPos anchorPos = BlockPos.containing(anchor.x, anchor.y, anchor.z);
		BlockPos candidateOrigin = BlockPos.containing(slot.x, anchor.y, slot.z);
		return groundSlotNear(world, candidateOrigin, anchorPos);
	}

	private static List<GuardEntity> collectNearbyGroup(ServerLevel world, GuardEntity guard, Vec3 anchor) {
		AABB searchBox = AABB.ofSize(anchor, GROUP_QUERY_RADIUS * 2.0D, GROUP_QUERY_HEIGHT * 2.0D, GROUP_QUERY_RADIUS * 2.0D);
		List<GuardEntity> guards = world.getEntitiesOfClass(
				GuardEntity.class,
				searchBox,
				entity -> entity.isAlive() && guard.isSameMovementGroup(entity));
		guards.sort(SLOT_ORDER);
		return guards;
	}

	private static int resolveSlotOrder(ServerLevel world, GuardEntity guard, Vec3 anchor) {
		List<GuardEntity> nearbyGroup = collectNearbyGroup(world, guard, anchor);
		int slotOrder = nearbyGroup.indexOf(guard);
		return slotOrder >= 0 ? slotOrder : Math.floorMod(guard.getUUID().hashCode(), MAX_MOVEMENT_COHORT_SIZE * 4);
	}

	private static Vec3 offsetForSlot(int slotOrder, double spacing, boolean allowCenter, double rotation) {
		if (allowCenter && slotOrder == 0) {
			return Vec3.ZERO;
		}

		int remaining = allowCenter ? slotOrder - 1 : slotOrder;
		int ring = 1;
		int ringCapacity = 8;
		while (remaining >= ringCapacity) {
			remaining -= ringCapacity;
			ring++;
			ringCapacity = ring * 8;
		}

		double radius = Math.max(1.25D, ring * spacing);
		double angle = rotation + (Math.PI * 2.0D * remaining / ringCapacity);
		return new Vec3(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
	}

	private static Vec3 cohortAnchorOffset(int cohortIndex, double spacing, double rotation) {
		if (cohortIndex <= 0) {
			return Vec3.ZERO;
		}
		return offsetForSlot(
				cohortIndex - 1,
				Math.max(spacing * COHORT_ANCHOR_SPACING_MULTIPLIER, spacing + 1.0D),
				false,
				rotation + (Math.PI / 8.0D));
	}

	private static BlockPos groundSlotNear(ServerLevel world, BlockPos candidateOrigin, BlockPos fallbackAnchor) {
		BlockPos grounded = GuardVillagersMod.findNearbyGuardSpawnPos(world, candidateOrigin, GROUND_SEARCH_VERTICAL_RANGE);
		if (grounded != null) {
			return grounded.immutable();
		}
		BlockPos fallback = GuardVillagersMod.findNearbyGuardSpawnPos(world, fallbackAnchor, GROUND_SEARCH_VERTICAL_RANGE);
		return fallback != null ? fallback.immutable() : fallbackAnchor.immutable();
	}

	private static double rotationForGuard(GuardEntity guard) {
		int seed = Math.floorMod(guard.getGroupIndex() * 3 + guard.getGroupColumn(), 8);
		return seed * (Math.PI / 4.0D);
	}
}
