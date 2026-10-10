package com.guardvillagers.navigation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Shares reachable static routes between guards of the same squad heading to the same block.
 *
 * <p>A cached route is only handed to a guard that is standing on or right next to one of its
 * nodes; the copy starts at that node. The previous version handed out routes whose first node
 * could be up to 12 blocks away, which made the receiving guard walk straight (unpathed) to
 * another guard's start position. The cache is LRU-bounded and entries age out after
 * {@link #MAX_CACHE_AGE_TICKS}.
 */
public final class SquadRouteCache {
	static final int MAX_CACHE_AGE_TICKS = 40;
	static final int MAX_CACHE_ENTRIES = 256;
	private static final int MAX_JOIN_HORIZONTAL_DISTANCE_SQUARED = 2;

	private static final Map<CacheKey, CachedRoute> SQUAD_ROUTES = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedRoute> eldest) {
			return this.size() > MAX_CACHE_ENTRIES;
		}
	};

	private SquadRouteCache() {
	}

	/**
	 * Returns a private copy of a cached route to {@code target} that the guard at {@code origin}
	 * can join, already advanced to the join node, or {@code null}.
	 */
	public static Path getSquadRoute(ResourceKey<Level> worldKey, UUID groupId, BlockPos origin, BlockPos target,
			long currentTick) {
		if (worldKey == null || groupId == null || target == null || origin == null) {
			return null;
		}
		CacheKey key = new CacheKey(worldKey, groupId, target.immutable());
		Path cachedPath;
		synchronized (SQUAD_ROUTES) {
			CachedRoute entry = SQUAD_ROUTES.get(key);
			if (entry == null) {
				return null;
			}
			if (currentTick - entry.computeTick() > MAX_CACHE_AGE_TICKS || currentTick < entry.computeTick()) {
				SQUAD_ROUTES.remove(key);
				return null;
			}
			cachedPath = entry.path();
		}

		int joinIndex = findJoinIndex(cachedPath, origin);
		if (joinIndex < 0) {
			return null;
		}
		Path copy = copyPath(cachedPath);
		copy.setNextNodeIndex(joinIndex);
		return copy;
	}

	/** Caches a route. Only reachable routes are worth sharing; partial routes are origin specific. */
	public static void cacheSquadRoute(ResourceKey<Level> worldKey, UUID groupId, BlockPos target, Path path,
			long currentTick) {
		if (worldKey == null || groupId == null || target == null || path == null || !path.canReach()
				|| path.getNodeCount() == 0) {
			return;
		}
		CacheKey key = new CacheKey(worldKey, groupId, target.immutable());
		Path stored = copyPath(path);
		synchronized (SQUAD_ROUTES) {
			SQUAD_ROUTES.put(key, new CachedRoute(stored, currentTick));
		}
	}

	public static void invalidateSquadRoute(ResourceKey<Level> worldKey, UUID groupId, BlockPos target) {
		if (worldKey == null || groupId == null || target == null) {
			return;
		}
		synchronized (SQUAD_ROUTES) {
			SQUAD_ROUTES.remove(new CacheKey(worldKey, groupId, target.immutable()));
		}
	}

	static int size() {
		synchronized (SQUAD_ROUTES) {
			return SQUAD_ROUTES.size();
		}
	}

	static void clear() {
		synchronized (SQUAD_ROUTES) {
			SQUAD_ROUTES.clear();
		}
	}

	/**
	 * Index of the furthest node the guard at {@code origin} can step onto directly: same block
	 * height and at most one block away horizontally (diagonals included). -1 if none.
	 */
	static int findJoinIndex(Path path, BlockPos origin) {
		int best = -1;
		for (int i = 0; i < path.getNodeCount(); i++) {
			Node node = path.getNode(i);
			if (node.y != origin.getY()) {
				continue;
			}
			int dx = node.x - origin.getX();
			int dz = node.z - origin.getZ();
			if (dx * dx + dz * dz <= MAX_JOIN_HORIZONTAL_DISTANCE_SQUARED) {
				best = i;
			}
		}
		return best;
	}

	/** Node objects are immutable once a path is built; only the list and the cursor are per-copy. */
	private static Path copyPath(Path original) {
		int length = original.getNodeCount();
		List<Node> nodes = new ArrayList<>(length);
		for (int i = 0; i < length; i++) {
			nodes.add(original.getNode(i));
		}
		return new Path(nodes, original.getTarget(), original.canReach());
	}

	private record CacheKey(ResourceKey<Level> worldKey, UUID groupId, BlockPos target) {
	}

	private record CachedRoute(Path path, long computeTick) {
	}
}
