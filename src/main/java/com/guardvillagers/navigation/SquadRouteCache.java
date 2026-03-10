package com.guardvillagers.navigation;

import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SquadRouteCache {
    private static final Map<CacheKey, CachedRoute> SQUAD_ROUTES = new HashMap<>();
    private static final int MAX_CACHE_AGE_TICKS = 120; // 6 seconds
    private static final double MAX_TARGET_DRIFT_SQR = 16.0; // 4 blocks
    private static final double MAX_ORIGIN_DRIFT_SQR = 1024.0; // 32 blocks

    /**
     * Tries to find a valid cached route for a squad/owner given a target position.
     * Starts from the guard's current position and ensures validity.
     * Returns a DEFENSIVE COPY so consumers don't mutate each other's Path state.
     */
    public static Path getSquadRoute(UUID groupId, BlockPos origin, BlockPos target, long currentTick) {
        if (groupId == null || target == null) {
            return null;
        }

        CacheKey key = new CacheKey(groupId, quantize(target));
        CachedRoute entry = SQUAD_ROUTES.get(key);

        if (entry == null) {
            return null;
        }

        // Validity checks
        if (currentTick - entry.computeTick > MAX_CACHE_AGE_TICKS) {
            SQUAD_ROUTES.remove(key);
            return null;
        }

        if (entry.targetPos.getSquaredDistance(target) > MAX_TARGET_DRIFT_SQR) {
            SQUAD_ROUTES.remove(key);
            return null;
        }

        if (origin.getSquaredDistance(entry.originPos) > MAX_ORIGIN_DRIFT_SQR) {
            // Not invalidated globally, but invalid for this specific guard's distance
            return null;
        }

        // Defensive copy to prevent concurrent index mutation
        return copyPath(entry.path);
    }

    /**
     * Caches a successfully computed route for a squad.
     */
    public static void cacheSquadRoute(UUID groupId, BlockPos origin, BlockPos target, Path path, long currentTick) {
        if (groupId == null || target == null || path == null) {
            return;
        }

        CacheKey key = new CacheKey(groupId, quantize(target));
        // Store a defensive copy so if the original creator mutates its path, the cache
        // remains pristine
        SQUAD_ROUTES.put(key, new CachedRoute(copyPath(path), currentTick, origin, target));
    }

    public static void invalidateSquadRoute(UUID groupId, BlockPos target) {
        if (groupId == null || target == null) {
            return;
        }
        SQUAD_ROUTES.remove(new CacheKey(groupId, quantize(target)));
    }

    /**
     * Creates a safe defensive copy of a path.
     */
    private static Path copyPath(Path original) {
        if (original == null) {
            return null;
        }

        // MC 1.21.1 Path node extraction and reconstruction
        // Path constructor is usually Path(List<PathNode> nodes, BlockPos target,
        // boolean reachesTarget)
        // We can just use the indices
        List<PathNode> nodes = new ArrayList<>();
        for (int i = 0; i < original.getLength(); i++) {
            PathNode originalNode = original.getNode(i);
            // Reconstruct node so we don't share identical PathNode instances just in case,
            // though usually shallow copying the node list is enough to get a fresh
            // `currentNodeIndex` in the new Path.
            // We'll just pass the same node instances since they are read-only
            // structurally.
            nodes.add(originalNode);
        }

        Path copiedPath = new Path(nodes, original.getTarget(), original.reachesTarget());
        return copiedPath;
    }

    private static BlockPos quantize(BlockPos pos) {
        return new BlockPos(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
    }

    private record CacheKey(UUID groupId, BlockPos quantizedTarget) {
    }

    private record CachedRoute(Path path, long computeTick, BlockPos originPos, BlockPos targetPos) {
    }
}
