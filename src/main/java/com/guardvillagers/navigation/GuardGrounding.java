package com.guardvillagers.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Finds standing spots using the same block classification the walk pathfinder uses, instead of
 * heightmap tops (roofs, tree canopies) or spawn checks. A spot is standable when the feet block is
 * {@link PathType#WALKABLE} or {@link PathType#WATER_BORDER}, the head block is open, and the floor
 * is not a fence/wall-height block. Vertical search prefers the level closest to the reference height, so a slot next to a
 * house picks the street rather than the roof. Unloaded chunks are never touched.
 */
public final class GuardGrounding {
	private GuardGrounding() {
	}

	public static boolean isStandable(Mob mob, BlockPos pos) {
		return isStandable(mob.level(), new PathfindingContext(mob.level(), mob), new BlockPos.MutableBlockPos(),
				pos.getX(), pos.getY(), pos.getZ());
	}

	/** Scans one column around {@code refY}: refY, +1, -1, +2, -2, ... within the given bounds. */
	public static BlockPos findStandable(Mob mob, int x, int z, int refY, int maxUp, int maxDown) {
		Level level = mob.level();
		PathfindingContext context = new PathfindingContext(level, mob);
		BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
		return findInColumn(level, context, scratch, x, z, refY, maxUp, maxDown);
	}

	/**
	 * Like {@link #findStandable}, considering all nearby columns at each height before moving
	 * farther vertically. This prefers a neighbouring street over the requested column's roof.
	 */
	public static BlockPos findStandableNear(Mob mob, int x, int z, int refY, int maxUp, int maxDown,
			int horizontalRadius) {
		Level level = mob.level();
		PathfindingContext context = new PathfindingContext(level, mob);
		BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
		for (int step = 0; step <= Math.max(maxUp, maxDown); step++) {
			for (int dy : step == 0 ? new int[] {0} : new int[] {step, -step}) {
				if (dy > maxUp || -dy > maxDown) {
					continue;
				}
				for (int radius = 0; radius <= horizontalRadius; radius++) {
					for (int dx = -radius; dx <= radius; dx++) {
						for (int dz = -radius; dz <= radius; dz++) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) == radius
									&& level.isLoaded(scratch.set(x + dx, refY + dy, z + dz))
									&& isStandable(level, context, scratch, x + dx, refY + dy, z + dz)) {
								return new BlockPos(x + dx, refY + dy, z + dz);
							}
						}
					}
				}
			}
		}
		return null;
	}

	private static BlockPos findInColumn(Level level, PathfindingContext context, BlockPos.MutableBlockPos scratch,
			int x, int z, int refY, int maxUp, int maxDown) {
		if (!level.isLoaded(scratch.set(x, refY, z))) {
			return null;
		}
		int limit = Math.max(maxUp, maxDown);
		for (int step = 0; step <= limit; step++) {
			if (step <= maxUp && isStandable(level, context, scratch, x, refY + step, z)) {
				return new BlockPos(x, refY + step, z);
			}
			if (step > 0 && step <= maxDown && isStandable(level, context, scratch, x, refY - step, z)) {
				return new BlockPos(x, refY - step, z);
			}
		}
		return null;
	}

	private static boolean isStandable(Level level, PathfindingContext context, BlockPos.MutableBlockPos scratch,
			int x, int y, int z) {
		if (y <= level.getMinY() + 1 || y >= level.getMaxY() - 1) {
			return false;
		}
		PathType feet = WalkNodeEvaluator.getPathTypeStatic(context, scratch.set(x, y, z));
		if (feet != PathType.WALKABLE && feet != PathType.WATER_BORDER) {
			return false;
		}
		if (WalkNodeEvaluator.getPathTypeStatic(context, scratch.set(x, y + 1, z)) != PathType.OPEN) {
			return false;
		}
		scratch.set(x, y - 1, z);
		VoxelShape floor = level.getBlockState(scratch).getCollisionShape(level, scratch);
		return !floor.isEmpty() && floor.max(Direction.Axis.Y) <= 1.0D;
	}
}
