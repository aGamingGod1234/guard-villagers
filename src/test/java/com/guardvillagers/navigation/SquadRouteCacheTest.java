package com.guardvillagers.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class SquadRouteCacheTest {
	private static final ResourceKey<Level> WORLD = ResourceKey.create(Registries.DIMENSION,
			Identifier.fromNamespaceAndPath("guardvillagers", "test"));
	private static final UUID SQUAD = new UUID(4L, 2L);

	/** Straight path along +x from (0,64,0) to (10,64,0). */
	private static Path straightPath(boolean reached) {
		List<Node> nodes = new ArrayList<>();
		for (int x = 0; x <= 10; x++) {
			nodes.add(new Node(x, 64, 0));
		}
		return new Path(nodes, new BlockPos(10, 64, 0), reached);
	}

	@BeforeEach
	void reset() {
		SquadRouteCache.clear();
	}

	@Test
	void joinIndexPicksFurthestAdjacentNodeAtSameHeight() {
		Path path = straightPath(true);
		assertEquals(4, SquadRouteCache.findJoinIndex(path, new BlockPos(3, 64, 1)));
		assertEquals(-1, SquadRouteCache.findJoinIndex(path, new BlockPos(3, 65, 0)));
		assertEquals(-1, SquadRouteCache.findJoinIndex(path, new BlockPos(3, 64, 3)));
	}

	@Test
	void sharedRouteStartsAtTheJoinNodeAndIsAPrivateCopy() {
		Path original = straightPath(true);
		BlockPos target = new BlockPos(10, 64, 0);
		SquadRouteCache.cacheSquadRoute(WORLD, SQUAD, target, original, 100L);

		Path shared = SquadRouteCache.getSquadRoute(WORLD, SQUAD, new BlockPos(5, 64, 1), target, 110L);
		assertNotNull(shared);
		assertNotSame(original, shared);
		assertEquals(6, shared.getNextNodeIndex());
		shared.advance();
		assertEquals(0, original.getNextNodeIndex());
	}

	@Test
	void guardFarFromTheRouteGetsNothing() {
		BlockPos target = new BlockPos(10, 64, 0);
		SquadRouteCache.cacheSquadRoute(WORLD, SQUAD, target, straightPath(true), 100L);
		assertNull(SquadRouteCache.getSquadRoute(WORLD, SQUAD, new BlockPos(5, 64, 8), target, 101L));
	}

	@Test
	void partialRoutesAreNotCachedAndOldRoutesExpire() {
		BlockPos target = new BlockPos(10, 64, 0);
		SquadRouteCache.cacheSquadRoute(WORLD, SQUAD, target, straightPath(false), 100L);
		assertEquals(0, SquadRouteCache.size());

		SquadRouteCache.cacheSquadRoute(WORLD, SQUAD, target, straightPath(true), 100L);
		assertNull(SquadRouteCache.getSquadRoute(WORLD, SQUAD, new BlockPos(0, 64, 0), target,
				100L + SquadRouteCache.MAX_CACHE_AGE_TICKS + 1));
		assertEquals(0, SquadRouteCache.size());
	}

	@Test
	void cacheIsBoundedEvenWhenAllEntriesAreFresh() {
		for (int i = 0; i < SquadRouteCache.MAX_CACHE_ENTRIES * 3; i++) {
			SquadRouteCache.cacheSquadRoute(WORLD, SQUAD, new BlockPos(i, 64, 0), straightPath(true), 100L);
		}
		assertTrue(SquadRouteCache.size() <= SquadRouteCache.MAX_CACHE_ENTRIES);
	}
}
