package com.guardvillagers.client;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Caches top-down terrain colors per chunk. CPU-side colors are kept per world/dimension (LRU, bounded);
 * GPU textures are created lazily only for chunks drawn at full detail and are bounded separately.
 * Must only be used from the render thread.
 */
public final class ChunkTerrainCache {
	private static final int MAX_TILES_PER_WORLD_DIMENSION = 4_096;
	private static final int MAX_WORLD_DIMENSIONS = 4;
	private static final int MAX_TEXTURES = 2_048;
	private static final int MAX_TILE_GENERATIONS_PER_FRAME = 24;
	private static final int MAX_TEXTURE_UPLOADS_PER_FRAME = 64;
	private static final int FALLBACK_COLOR = 0xFF161C25;
	private static final int TILE_RESOLUTION = 16;
	private static final int TILE_PIXEL_COUNT = TILE_RESOLUTION * TILE_RESOLUTION;
	private static final Supplier<String> TEXTURE_LABEL = () -> "guardvillagers_chunk_terrain";

	private final LinkedHashMap<ClientTacticsDataStore.WorldContext, Long2ObjectLinkedOpenHashMap<TerrainTile>> tilesByWorld =
			new LinkedHashMap<>(8, 0.75F, true);
	private final ReferenceLinkedOpenHashSet<TerrainTile> textureLru = new ReferenceLinkedOpenHashSet<>();
	private final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
	private ClientTacticsDataStore.WorldContext currentContext;
	private Long2ObjectLinkedOpenHashMap<TerrainTile> currentTiles;
	private long frame;
	private int uploadsThisFrame;

	/** Generates missing tiles for the given packed chunk keys, nearest-first order expected. */
	public void prepareVisibleTiles(ClientTacticsDataStore.WorldContext context, ClientLevel world, LongArrayList visibleChunkKeys) {
		this.frame++;
		this.uploadsThisFrame = 0;
		if (context == null || world == null || visibleChunkKeys == null || visibleChunkKeys.isEmpty()) {
			return;
		}

		Long2ObjectLinkedOpenHashMap<TerrainTile> tiles = this.tilesFor(context, true);
		int generated = 0;
		for (int i = 0, size = visibleChunkKeys.size(); i < size; i++) {
			if (generated >= MAX_TILE_GENERATIONS_PER_FRAME) {
				break;
			}
			long chunkKey = visibleChunkKeys.getLong(i);
			if (tiles.containsKey(chunkKey)) {
				continue;
			}
			// Cache full of tiles that were on screen last frame: generating more would only evict visible tiles.
			if (tiles.size() >= MAX_TILES_PER_WORLD_DIMENSION && tiles.get(tiles.firstLongKey()).lastSeenFrame >= this.frame - 1) {
				break;
			}

			LevelChunk chunk = world.getChunkSource().getChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), ChunkStatus.FULL, false);
			if (chunk == null) {
				continue;
			}
			tiles.putAndMoveToLast(chunkKey, this.generateTile(world, chunk));
			generated++;
			this.evictIfNeeded(tiles);
		}
	}

	public TerrainTile getCachedTile(ClientTacticsDataStore.WorldContext context, int chunkX, int chunkZ) {
		if (context == null) {
			return null;
		}
		Long2ObjectLinkedOpenHashMap<TerrainTile> tiles = this.tilesFor(context, false);
		if (tiles == null) {
			return null;
		}
		TerrainTile tile = tiles.getAndMoveToLast(ChunkPos.pack(chunkX, chunkZ));
		if (tile != null) {
			tile.lastSeenFrame = this.frame;
		}
		return tile;
	}

	/**
	 * Returns an uploaded texture for the tile, or null if this frame's upload or texture budget is used up
	 * (callers fall back to the tile's average color).
	 */
	public DynamicTexture acquireTexture(TerrainTile tile) {
		if (tile.texture != null) {
			tile.lastTextureFrame = this.frame;
			this.textureLru.addAndMoveToLast(tile);
			return tile.texture;
		}
		if (this.uploadsThisFrame >= MAX_TEXTURE_UPLOADS_PER_FRAME) {
			return null;
		}
		if (this.textureLru.size() >= MAX_TEXTURES) {
			TerrainTile oldest = this.textureLru.first();
			if (oldest.lastTextureFrame >= this.frame) {
				return null;
			}
			this.textureLru.removeFirst();
			oldest.releaseTexture();
		}

		NativeImage image = new NativeImage(TILE_RESOLUTION, TILE_RESOLUTION, false);
		for (int pixelZ = 0; pixelZ < TILE_RESOLUTION; pixelZ++) {
			int rowOffset = pixelZ * TILE_RESOLUTION;
			for (int pixelX = 0; pixelX < TILE_RESOLUTION; pixelX++) {
				image.setPixel(pixelX, pixelZ, tile.colors[rowOffset + pixelX]);
			}
		}
		// The constructor uploads the image; DynamicTexture already samples with NEAREST filtering.
		tile.texture = new DynamicTexture(TEXTURE_LABEL, image);
		tile.lastTextureFrame = this.frame;
		this.textureLru.addAndMoveToLast(tile);
		this.uploadsThisFrame++;
		return tile.texture;
	}

	public void invalidate(ClientTacticsDataStore.WorldContext context, int chunkX, int chunkZ) {
		if (context == null) {
			return;
		}
		Long2ObjectLinkedOpenHashMap<TerrainTile> tiles = this.tilesFor(context, false);
		if (tiles == null) {
			return;
		}
		TerrainTile removed = tiles.remove(ChunkPos.pack(chunkX, chunkZ));
		if (removed != null) {
			this.dropTexture(removed);
		}
	}

	/** Frees all GPU textures but keeps the CPU-side colors so the map reopens without re-sampling chunks. */
	public void releaseTextures() {
		for (TerrainTile tile : this.textureLru) {
			tile.releaseTexture();
		}
		this.textureLru.clear();
	}

	public void clearAll() {
		this.releaseTextures();
		this.tilesByWorld.clear();
		this.currentContext = null;
		this.currentTiles = null;
	}

	private Long2ObjectLinkedOpenHashMap<TerrainTile> tilesFor(ClientTacticsDataStore.WorldContext context, boolean create) {
		if (context == this.currentContext || context.equals(this.currentContext)) {
			return this.currentTiles;
		}
		Long2ObjectLinkedOpenHashMap<TerrainTile> tiles = this.tilesByWorld.get(context);
		if (tiles == null) {
			if (!create) {
				return null;
			}
			tiles = new Long2ObjectLinkedOpenHashMap<>(256);
			this.tilesByWorld.put(context, tiles);
			this.evictWorldsIfNeeded();
		}
		this.currentContext = context;
		this.currentTiles = tiles;
		return tiles;
	}

	private void evictWorldsIfNeeded() {
		Iterator<Map.Entry<ClientTacticsDataStore.WorldContext, Long2ObjectLinkedOpenHashMap<TerrainTile>>> iterator = this.tilesByWorld.entrySet().iterator();
		while (this.tilesByWorld.size() > MAX_WORLD_DIMENSIONS && iterator.hasNext()) {
			Map.Entry<ClientTacticsDataStore.WorldContext, Long2ObjectLinkedOpenHashMap<TerrainTile>> eldest = iterator.next();
			for (TerrainTile tile : eldest.getValue().values()) {
				this.dropTexture(tile);
			}
			if (eldest.getValue() == this.currentTiles) {
				this.currentContext = null;
				this.currentTiles = null;
			}
			iterator.remove();
		}
	}

	private void evictIfNeeded(Long2ObjectLinkedOpenHashMap<TerrainTile> tiles) {
		while (tiles.size() > MAX_TILES_PER_WORLD_DIMENSION) {
			this.dropTexture(tiles.removeFirst());
		}
	}

	private void dropTexture(TerrainTile tile) {
		if (tile.texture != null) {
			this.textureLru.remove(tile);
			tile.releaseTexture();
		}
	}

	private TerrainTile generateTile(ClientLevel world, LevelChunk chunk) {
		int[] colors = new int[TILE_PIXEL_COUNT];
		int worldBottom = world.getMinY();
		ChunkPos chunkPos = chunk.getPos();
		int startX = chunkPos.getMinBlockX();
		int startZ = chunkPos.getMinBlockZ();
		BlockPos.MutableBlockPos pos = this.samplePos;
		long sumRed = 0L;
		long sumGreen = 0L;
		long sumBlue = 0L;

		for (int localZ = 0; localZ < TILE_RESOLUTION; localZ++) {
			int worldZ = startZ + localZ;
			int rowOffset = localZ * TILE_RESOLUTION;
			for (int localX = 0; localX < TILE_RESOLUTION; localX++) {
				int worldX = startX + localX;
				// ChunkAccess#getHeight already returns the top block (first free Y minus one).
				int sampleY = Math.max(worldBottom, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ));

				pos.set(worldX, sampleY, worldZ);
				BlockState state = chunk.getBlockState(pos);
				while (sampleY > worldBottom && state.isAir()) {
					sampleY--;
					pos.set(worldX, sampleY, worldZ);
					state = chunk.getBlockState(pos);
				}

				MapColor mapColor = state.getMapColor(world, pos);
				int color = mapColor == MapColor.NONE ? FALLBACK_COLOR : 0xFF000000 | mapColor.col;
				colors[rowOffset + localX] = color;
				sumRed += (color >> 16) & 0xFF;
				sumGreen += (color >> 8) & 0xFF;
				sumBlue += color & 0xFF;
			}
		}

		int averageColor = 0xFF000000
				| ((int) (sumRed / TILE_PIXEL_COUNT) << 16)
				| ((int) (sumGreen / TILE_PIXEL_COUNT) << 8)
				| (int) (sumBlue / TILE_PIXEL_COUNT);
		return new TerrainTile(colors, averageColor);
	}

	public static final class TerrainTile {
		private final int[] colors;
		private final int averageColor;
		private DynamicTexture texture;
		private long lastSeenFrame;
		private long lastTextureFrame;

		private TerrainTile(int[] colors, int averageColor) {
			this.colors = colors;
			this.averageColor = averageColor;
		}

		public int averageColor() {
			return this.averageColor;
		}

		private void releaseTexture() {
			if (this.texture != null) {
				this.texture.close();
				this.texture = null;
			}
		}
	}
}
