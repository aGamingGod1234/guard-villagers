package com.guardvillagers.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkTerrainCache {
	private static final int MAX_TILES_PER_WORLD_DIMENSION = 2_048;
	private static final int FALLBACK_COLOR = 0xFF2A313A;
	private static final int TILE_RESOLUTION = 16;
	private static final int TILE_PIXEL_COUNT = TILE_RESOLUTION * TILE_RESOLUTION;
	private static final int MAX_ASYNC_TILES_PER_FRAME = 8;

	private final Map<String, LinkedHashMap<Long, TerrainTile>> tilesByWorldDimension = new HashMap<>();
	private final Set<Long> pendingGeneration = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<Long, TerrainTile> asyncResults = new ConcurrentHashMap<>();

	public TerrainTile getOrCreate(ClientTacticsDataStore.WorldContext context, ClientWorld world, int chunkX, int chunkZ) {
		// First, drain any completed async tiles into the cache
		drainAsyncResults(context);

		String key = context.worldId() + "|" + context.dimensionId();
		LinkedHashMap<Long, TerrainTile> cache = this.tilesByWorldDimension.computeIfAbsent(
			key,
			ignored -> new LinkedHashMap<>(256, 0.75F, true)
		);
		long chunkKey = ChunkPos.toLong(chunkX, chunkZ);
		TerrainTile cached = cache.get(chunkKey);
		if (cached != null) {
			return cached;
		}

		WorldChunk chunk = world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (chunk == null) {
			return null;
		}

		// If already generating async, return null (pending)
		if (this.pendingGeneration.contains(chunkKey)) {
			return null;
		}

		// Generate synchronously for the first few tiles, then queue the rest async
		if (this.pendingGeneration.size() < MAX_ASYNC_TILES_PER_FRAME) {
			TerrainTile generated = generateTile(world, chunk);
			cache.put(chunkKey, generated);
			evictIfNeeded(cache);
			return generated;
		}

		// Queue for async generation
		this.pendingGeneration.add(chunkKey);
		ChunkPos chunkPos = chunk.getPos();
		int worldBottom = world.getBottomY();
		// Snapshot heightmap and block data synchronously (must be done on render thread)
		int[] heightmap = snapshotHeightmap(chunk, chunkPos);
		int[] blockColors = snapshotBlockColors(world, chunk, chunkPos, worldBottom, heightmap);
		CompletableFuture.supplyAsync(() -> computeTileFromSnapshot(blockColors))
			.thenAccept(tile -> {
				this.asyncResults.put(chunkKey, tile);
				this.pendingGeneration.remove(chunkKey);
			});

		return null;
	}

	private void drainAsyncResults(ClientTacticsDataStore.WorldContext context) {
		if (this.asyncResults.isEmpty()) {
			return;
		}
		String key = context.worldId() + "|" + context.dimensionId();
		LinkedHashMap<Long, TerrainTile> cache = this.tilesByWorldDimension.computeIfAbsent(
			key,
			ignored -> new LinkedHashMap<>(256, 0.75F, true)
		);
		for (Iterator<Map.Entry<Long, TerrainTile>> it = this.asyncResults.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long, TerrainTile> entry = it.next();
			cache.put(entry.getKey(), entry.getValue());
			it.remove();
		}
		evictIfNeeded(cache);
	}

	private static int[] snapshotHeightmap(WorldChunk chunk, ChunkPos chunkPos) {
		int[] heights = new int[TILE_PIXEL_COUNT];
		for (int pz = 0; pz < TILE_RESOLUTION; pz++) {
			for (int px = 0; px < TILE_RESOLUTION; px++) {
				int localX = (px * 16) / TILE_RESOLUTION;
				int localZ = (pz * 16) / TILE_RESOLUTION;
				heights[pz * TILE_RESOLUTION + px] = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, localX, localZ);
			}
		}
		return heights;
	}

	private static int[] snapshotBlockColors(ClientWorld world, WorldChunk chunk, ChunkPos chunkPos, int worldBottom, int[] heightmap) {
		int[] colors = new int[TILE_PIXEL_COUNT];
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int pz = 0; pz < TILE_RESOLUTION; pz++) {
			for (int px = 0; px < TILE_RESOLUTION; px++) {
				int localX = (px * 16) / TILE_RESOLUTION;
				int localZ = (pz * 16) / TILE_RESOLUTION;
				int worldX = chunkPos.getStartX() + localX;
				int worldZ = chunkPos.getStartZ() + localZ;
				int index = pz * TILE_RESOLUTION + px;
				int sampleY = Math.max(worldBottom, heightmap[index] - 1);

				pos.set(worldX, sampleY, worldZ);
				BlockState state = chunk.getBlockState(pos);
				int searchY = sampleY;
				while (searchY > worldBottom && state.isAir()) {
					searchY--;
					pos.set(worldX, searchY, worldZ);
					state = chunk.getBlockState(pos);
				}

				MapColor mapColor = state.getMapColor(world, pos);
				colors[index] = mapColor == MapColor.CLEAR ? FALLBACK_COLOR : 0xFF000000 | mapColor.color;
			}
		}
		return colors;
	}

	private static TerrainTile computeTileFromSnapshot(int[] colors) {
		long sumRed = 0, sumGreen = 0, sumBlue = 0;
		for (int color : colors) {
			sumRed += (color >> 16) & 0xFF;
			sumGreen += (color >> 8) & 0xFF;
			sumBlue += color & 0xFF;
		}
		int avgR = (int) (sumRed / TILE_PIXEL_COUNT);
		int avgG = (int) (sumGreen / TILE_PIXEL_COUNT);
		int avgB = (int) (sumBlue / TILE_PIXEL_COUNT);
		int averageColor = 0xFF000000 | (avgR << 16) | (avgG << 8) | avgB;
		return new TerrainTile(colors, averageColor);
	}

	private static TerrainTile generateTile(ClientWorld world, WorldChunk chunk) {
		int[] colors = new int[TILE_PIXEL_COUNT];
		long sumRed = 0, sumGreen = 0, sumBlue = 0;
		ChunkPos chunkPos = chunk.getPos();
		int worldBottom = world.getBottomY();
		BlockPos.Mutable samplePos = new BlockPos.Mutable();

		for (int pz = 0; pz < TILE_RESOLUTION; pz++) {
			for (int px = 0; px < TILE_RESOLUTION; px++) {
				int localX = (px * 16) / TILE_RESOLUTION;
				int localZ = (pz * 16) / TILE_RESOLUTION;
				int worldX = chunkPos.getStartX() + localX;
				int worldZ = chunkPos.getStartZ() + localZ;
				int topY = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, localX, localZ);
				int sampleY = Math.max(worldBottom, topY - 1);

				samplePos.set(worldX, sampleY, worldZ);
				BlockState state = chunk.getBlockState(samplePos);
				int searchY = sampleY;
				while (searchY > worldBottom && state.isAir()) {
					searchY--;
					samplePos.set(worldX, searchY, worldZ);
					state = chunk.getBlockState(samplePos);
				}

				MapColor mapColor = state.getMapColor(world, samplePos);
				int argb = mapColor == MapColor.CLEAR ? FALLBACK_COLOR : 0xFF000000 | mapColor.color;
				int index = pz * TILE_RESOLUTION + px;
				colors[index] = argb;
				sumRed += (argb >> 16) & 0xFF;
				sumGreen += (argb >> 8) & 0xFF;
				sumBlue += argb & 0xFF;
			}
		}

		int avgR = (int) (sumRed / TILE_PIXEL_COUNT);
		int avgG = (int) (sumGreen / TILE_PIXEL_COUNT);
		int avgB = (int) (sumBlue / TILE_PIXEL_COUNT);
		int averageColor = 0xFF000000 | (avgR << 16) | (avgG << 8) | avgB;
		return new TerrainTile(colors, averageColor);
	}

	public void invalidate(ClientTacticsDataStore.WorldContext context, int chunkX, int chunkZ) {
		String key = context.worldId() + "|" + context.dimensionId();
		LinkedHashMap<Long, TerrainTile> cache = this.tilesByWorldDimension.get(key);
		if (cache == null) {
			return;
		}
		cache.remove(ChunkPos.toLong(chunkX, chunkZ));
	}

	public void clearAll() {
		this.tilesByWorldDimension.clear();
		this.pendingGeneration.clear();
		this.asyncResults.clear();
	}

	private static void evictIfNeeded(LinkedHashMap<Long, TerrainTile> cache) {
		while (cache.size() > MAX_TILES_PER_WORLD_DIMENSION) {
			Iterator<Map.Entry<Long, TerrainTile>> iterator = cache.entrySet().iterator();
			if (iterator.hasNext()) {
				iterator.next();
				iterator.remove();
			} else {
				break;
			}
		}
	}

	public record TerrainTile(int[] colors, int averageColor) {
	}
}
