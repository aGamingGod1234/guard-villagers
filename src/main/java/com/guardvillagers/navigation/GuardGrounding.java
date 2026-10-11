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
 * {@link PathType#WALKABLE} or a safe dry water border, the head block is open, and the floor
 * is not a fence/wall-height block. Vertical search prefers the level closest to the reference height, so a slot next to a
 * house picks the street rather than the roof. Unloaded chunks are never touched.
 */
public final class GuardGrounding {
	private GuardGrounding() {
	}

	public static boolean isStandable(Mob mob, BlockPos pos) {
		return isStandable(mob, new PathfindingContext(mob.level(), mob), new BlockPos.MutableBlockPos(),
				pos.getX(), pos.getY(), pos.getZ());
	}

	/** Scans one column around {@code refY}: refY, +1, -1, +2, -2, ... within the given bounds. */
	public static BlockPos findStandable(Mob mob, int x, int z, int refY, int maxUp, int maxDown) {
		Level level = mob.level();
		PathfindingContext context = new PathfindingContext(level, mob);
		BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
		return findInColumn(mob, context, scratch, x, z, refY, maxUp, maxDown);
	}

	/** Searches nearby columns at each height before considering a more distant vertical level. */
	public static BlockPos findStandableNear(Mob mob, int x, int z, int refY, int maxUp, int maxDown,
			int horizontalRadius) {
		PathfindingContext context = new PathfindingContext(mob.level(), mob);
		BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
		for (int step = 0; step <= Math.max(maxUp, maxDown); step++) {
			if (step <= maxUp) {
				BlockPos found = findAtHeight(mob, context, scratch, x, z, refY + step, horizontalRadius);
				if (found != null) {
					return found;
				}
			}
			if (step > 0 && step <= maxDown) {
				BlockPos found = findAtHeight(mob, context, scratch, x, z, refY - step, horizontalRadius);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static BlockPos findAtHeight(Mob mob, PathfindingContext context, BlockPos.MutableBlockPos scratch,
			int x, int z, int y, int horizontalRadius) {
		for (int radius = 0; radius <= horizontalRadius; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == radius
							&& isStandable(mob, context, scratch, x + dx, y, z + dz)) {
						return new BlockPos(x + dx, y, z + dz);
					}
				}
			}
		}
		return null;
	}

	private static BlockPos findInColumn(Mob mob, PathfindingContext context, BlockPos.MutableBlockPos scratch,
			int x, int z, int refY, int maxUp, int maxDown) {
		if (!hasLoadedNeighbours(mob.level(), x, z)) {
			return null;
		}
		int limit = Math.max(maxUp, maxDown);
		for (int step = 0; step <= limit; step++) {
			if (step <= maxUp && isStandable(mob, context, scratch, x, refY + step, z)) {
				return new BlockPos(x, refY + step, z);
			}
			if (step > 0 && step <= maxDown && isStandable(mob, context, scratch, x, refY - step, z)) {
				return new BlockPos(x, refY - step, z);
			}
		}
		return null;
	}

	private static boolean isStandable(Mob mob, PathfindingContext context, BlockPos.MutableBlockPos scratch,
			int x, int y, int z) {
		Level level = mob.level();
		if (y <= level.getMinY() + 1 || y >= level.getMaxY() - 1 || !hasLoadedNeighbours(level, x, z)) {
			return false;
		}
		PathType feet = WalkNodeEvaluator.getPathTypeStatic(context, scratch.set(x, y, z));
		if ((feet != PathType.WALKABLE && feet != PathType.WATER_BORDER) || mob.getPathfindingMalus(feet) < 0.0F) {
			return false;
		}
		if (WalkNodeEvaluator.getPathTypeStatic(context, scratch.set(x, y + 1, z)) != PathType.OPEN) {
			return false;
		}
		scratch.set(x, y - 1, z);
		VoxelShape floor = level.getBlockState(scratch).getCollisionShape(level, scratch);
		return !floor.isEmpty() && floor.max(Direction.Axis.Y) <= 1.0D;
	}

	private static boolean hasLoadedNeighbours(Level level, int x, int z) {
		// The walk classifier reads a 3x3 neighbourhood, including across chunk borders.
		// Check every contributing chunk without loading it before any live-level block reads.
		for (int chunkX = (x - 1) >> 4; chunkX <= (x + 1) >> 4; chunkX++) {
			for (int chunkZ = (z - 1) >> 4; chunkZ <= (z + 1) >> 4; chunkZ++) {
				if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
					return false;
				}
			}
		}
		return true;
	}
}
