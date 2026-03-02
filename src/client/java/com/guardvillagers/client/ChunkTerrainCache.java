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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ChunkTerrainCache {
	private static final int MAX_TILES_PER_WORLD_DIMENSION = 2_048;
	private static final int FALLBACK_COLOR = 0xFF2A313A;

	private final Map<String, LinkedHashMap<Long, TerrainTile>> tilesByWorldDimension = new HashMap<>();
	private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

	// Dev note: minimap tiles are generated once per chunk and cached per world+dimension.
	public TerrainTile getOrCreate(ClientTacticsDataStore.WorldContext context, ClientWorld world, int chunkX, int chunkZ) {
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
		TerrainTile generated = this.generateTile(world, chunk);
		cache.put(chunkKey, generated);
		if (cache.size() > MAX_TILES_PER_WORLD_DIMENSION) {
			Iterator<Map.Entry<Long, TerrainTile>> iterator = cache.entrySet().iterator();
			if (iterator.hasNext()) {
				iterator.next();
				iterator.remove();
			}
		}
		return generated;
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
	}

	private TerrainTile generateTile(ClientWorld world, WorldChunk chunk) {
		int[] colors = new int[256];
		long sumRed = 0;
		long sumGreen = 0;
		long sumBlue = 0;
		ChunkPos chunkPos = chunk.getPos();
		int worldBottom = world.getBottomY();

		for (int localZ = 0; localZ < 16; localZ++) {
			for (int localX = 0; localX < 16; localX++) {
				int worldX = chunkPos.getStartX() + localX;
				int worldZ = chunkPos.getStartZ() + localZ;
				int topY = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, localX, localZ);
				int sampleY = Math.max(worldBottom, topY - 1);

				this.samplePos.set(worldX, sampleY, worldZ);
				BlockState state = chunk.getBlockState(this.samplePos);
				int searchY = sampleY;
				while (searchY > worldBottom && state.isAir()) {
					searchY--;
					this.samplePos.set(worldX, searchY, worldZ);
					state = chunk.getBlockState(this.samplePos);
				}

				MapColor mapColor = state.getMapColor(world, this.samplePos);
				int argb = mapColor == MapColor.CLEAR ? FALLBACK_COLOR : 0xFF000000 | mapColor.color;
				int index = (localZ << 4) | localX;
				colors[index] = argb;
				sumRed += (argb >> 16) & 0xFF;
				sumGreen += (argb >> 8) & 0xFF;
				sumBlue += argb & 0xFF;
			}
		}

		int averageRed = (int) (sumRed / 256L);
		int averageGreen = (int) (sumGreen / 256L);
		int averageBlue = (int) (sumBlue / 256L);
		int averageColor = 0xFF000000 | (averageRed << 16) | (averageGreen << 8) | averageBlue;
		return new TerrainTile(colors, averageColor);
	}

	public record TerrainTile(int[] colors, int averageColor) {
	}
}
