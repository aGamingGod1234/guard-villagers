package com.guardvillagers.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;

public final class ChunkMapWidget {
	private static final double MIN_ZOOM = 0.25D;
	private static final double MAX_ZOOM = 8.0D;
	private static final double ZOOM_STEP_BASE = 1.1D;
	private static final double BASE_CHUNK_PIXELS = 24.0D;

	private static final int MAP_BACKGROUND = 0xFF10161D;
	private static final int SELECTION_OVERLAY = 0x446FD3FF;
	private static final int HOVER_OVERLAY = 0x33FFFFFF;

	private final ClientTacticsDataStore dataStore;
	private final ChunkTerrainCache terrainCache;

	private int x;
	private int y;
	private int width;
	private int height;
	private boolean cameraInitialized;
	private double centerChunkX;
	private double centerChunkZ;
	private double zoom = 1.0D;
	private RegionColor activeColor = RegionColor.BLUE;
	private ChunkPos selectionStart;
	private ChunkPos selectionEnd;
	private boolean selecting;
	private boolean panning;
	private ChunkPos hoveredChunk;

	public ChunkMapWidget(ClientTacticsDataStore dataStore, ChunkTerrainCache terrainCache) {
		this.dataStore = dataStore;
		this.terrainCache = terrainCache;
	}

	public void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
	}

	public void ensureCameraCentered(ChunkPos centerChunk) {
		if (this.cameraInitialized || centerChunk == null) {
			return;
		}
		this.centerChunkX = centerChunk.x;
		this.centerChunkZ = centerChunk.z;
		this.cameraInitialized = true;
	}

	public void render(
		DrawContext context,
		ClientWorld world,
		ClientTacticsDataStore.WorldContext worldContext,
		int mouseX,
		int mouseY
	) {
		context.fill(this.x, this.y, this.x + this.width, this.y + this.height, MAP_BACKGROUND);
		this.hoveredChunk = null;
		if (world == null || worldContext == null) {
			return;
		}

		double chunkScale = this.chunkScale();
		double viewportCenterX = this.x + (this.width / 2.0D);
		double viewportCenterY = this.y + (this.height / 2.0D);
		int minChunkX = (int) Math.floor(this.screenToChunkX(this.x)) - 1;
		int maxChunkX = (int) Math.ceil(this.screenToChunkX(this.x + this.width)) + 1;
		int minChunkZ = (int) Math.floor(this.screenToChunkZ(this.y)) - 1;
		int maxChunkZ = (int) Math.ceil(this.screenToChunkZ(this.y + this.height)) + 1;

		context.enableScissor(this.x, this.y, this.x + this.width, this.y + this.height);
		for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
			for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
				if (!this.dataStore.isDiscovered(worldContext, chunkX, chunkZ)) {
					continue;
				}

				int left = (int) Math.floor(viewportCenterX + (chunkX - this.centerChunkX) * chunkScale);
				int right = (int) Math.floor(viewportCenterX + ((chunkX + 1) - this.centerChunkX) * chunkScale);
				int top = (int) Math.floor(viewportCenterY + (chunkZ - this.centerChunkZ) * chunkScale);
				int bottom = (int) Math.floor(viewportCenterY + ((chunkZ + 1) - this.centerChunkZ) * chunkScale);
				if (right <= left || bottom <= top) {
					continue;
				}
				this.renderChunkTerrain(context, world, worldContext, chunkX, chunkZ, left, top, right, bottom);

				RegionColor regionColor = this.dataStore.getRegionColor(worldContext, chunkX, chunkZ);
				if (regionColor != RegionColor.NONE) {
					context.fill(left, top, right, bottom, regionColor.overlayArgb());
				}

				if (this.isChunkSelected(chunkX, chunkZ)) {
					context.fill(left, top, right, bottom, SELECTION_OVERLAY);
				}
			}
		}
		context.disableScissor();

		if (this.contains(mouseX, mouseY)) {
			int hoveredChunkX = (int) Math.floor(this.screenToChunkX(mouseX));
			int hoveredChunkZ = (int) Math.floor(this.screenToChunkZ(mouseY));
			if (this.dataStore.isDiscovered(worldContext, hoveredChunkX, hoveredChunkZ)) {
				this.hoveredChunk = new ChunkPos(hoveredChunkX, hoveredChunkZ);
				int left = (int) Math.floor(viewportCenterX + (hoveredChunkX - this.centerChunkX) * chunkScale);
				int right = (int) Math.floor(viewportCenterX + ((hoveredChunkX + 1) - this.centerChunkX) * chunkScale);
				int top = (int) Math.floor(viewportCenterY + (hoveredChunkZ - this.centerChunkZ) * chunkScale);
				int bottom = (int) Math.floor(viewportCenterY + ((hoveredChunkZ + 1) - this.centerChunkZ) * chunkScale);
				context.fill(left, top, right, bottom, HOVER_OVERLAY);
			}
		}
	}

	public boolean mouseClicked(
		double mouseX,
		double mouseY,
		int button,
		boolean shiftDown,
		ClientWorld world,
		ClientTacticsDataStore.WorldContext worldContext
	) {
		if (!this.contains(mouseX, mouseY) || world == null || worldContext == null) {
			return false;
		}

		if (button == 2) {
			this.panning = true;
			this.selecting = false;
			return true;
		}

		if (button == 0) {
			ChunkPos anchor = this.chunkAt(mouseX, mouseY);
			if (anchor == null || !this.dataStore.isDiscovered(worldContext, anchor.x, anchor.z)) {
				this.clearSelection();
				return true;
			}
			this.selectionStart = anchor;
			this.selectionEnd = anchor;
			this.selecting = true;
			this.panning = false;
			return true;
		}

		if (button == 1) {
			this.paintAtCursor(mouseX, mouseY, shiftDown, worldContext);
			return true;
		}

		return false;
	}

	public boolean mouseDragged(
		double mouseX,
		double mouseY,
		int button,
		double deltaX,
		double deltaY,
		ClientWorld world,
		ClientTacticsDataStore.WorldContext worldContext
	) {
		if (world == null || worldContext == null) {
			return false;
		}

		if (button == 2 && this.panning) {
			double scale = this.chunkScale();
			this.centerChunkX -= deltaX / scale;
			this.centerChunkZ -= deltaY / scale;
			return true;
		}

		if (button == 0 && this.selecting) {
			ChunkPos next = this.chunkAt(mouseX, mouseY);
			if (next != null) {
				this.selectionEnd = next;
			}
			return true;
		}
		return false;
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0 && this.selecting) {
			ChunkPos next = this.chunkAt(mouseX, mouseY);
			if (next != null) {
				this.selectionEnd = next;
			}
			this.selecting = false;
			return true;
		}
		if (button == 2 && this.panning) {
			this.panning = false;
			return true;
		}
		return false;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
		if (!this.contains(mouseX, mouseY)) {
			return false;
		}
		double chunkUnderCursorX = this.screenToChunkX(mouseX);
		double chunkUnderCursorZ = this.screenToChunkZ(mouseY);
		double zoomFactor = Math.pow(ZOOM_STEP_BASE, verticalAmount);
		double nextZoom = clamp(this.zoom * zoomFactor, MIN_ZOOM, MAX_ZOOM);
		if (Math.abs(nextZoom - this.zoom) < 1.0E-5D) {
			return true;
		}
		this.zoom = nextZoom;

		double centerScreenX = this.x + (this.width / 2.0D);
		double centerScreenY = this.y + (this.height / 2.0D);
		double chunkScale = this.chunkScale();
		this.centerChunkX = chunkUnderCursorX - ((mouseX - centerScreenX) / chunkScale);
		this.centerChunkZ = chunkUnderCursorZ - ((mouseY - centerScreenY) / chunkScale);
		return true;
	}

	public void clearSelection() {
		this.selectionStart = null;
		this.selectionEnd = null;
		this.selecting = false;
	}

	public boolean hasSelection() {
		return this.selectionStart != null && this.selectionEnd != null;
	}

	public RegionColor activeColor() {
		return this.activeColor;
	}

	public void setActiveColor(RegionColor activeColor) {
		if (activeColor == null || activeColor == RegionColor.NONE) {
			return;
		}
		this.activeColor = activeColor;
	}

	public ChunkPos hoveredChunk() {
		return this.hoveredChunk;
	}

	public double zoom() {
		return this.zoom;
	}

	private void paintAtCursor(double mouseX, double mouseY, boolean clear, ClientTacticsDataStore.WorldContext worldContext) {
		RegionColor paintColor = clear ? RegionColor.NONE : this.activeColor;
		if (this.hasSelection()) {
			int minX = Math.min(this.selectionStart.x, this.selectionEnd.x);
			int maxX = Math.max(this.selectionStart.x, this.selectionEnd.x);
			int minZ = Math.min(this.selectionStart.z, this.selectionEnd.z);
			int maxZ = Math.max(this.selectionStart.z, this.selectionEnd.z);
			for (int chunkX = minX; chunkX <= maxX; chunkX++) {
				for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
					long chunkKey = ChunkPos.toLong(chunkX, chunkZ);
					this.dataStore.setRegionColor(worldContext, chunkKey, paintColor);
				}
			}
			return;
		}
		ChunkPos hovered = this.chunkAt(mouseX, mouseY);
		if (hovered != null) {
			this.dataStore.setRegionColor(worldContext, hovered.x, hovered.z, paintColor);
		}
	}

	private void renderChunkTerrain(
		DrawContext context,
		ClientWorld world,
		ClientTacticsDataStore.WorldContext worldContext,
		int chunkX,
		int chunkZ,
		int left,
		int top,
		int right,
		int bottom
	) {
		ChunkTerrainCache.TerrainTile tile = this.terrainCache.getOrCreate(worldContext, world, chunkX, chunkZ);
		if (tile == null) {
			context.fill(left, top, right, bottom, 0xFF222A32);
			return;
		}
		if (this.zoom <= 1.0D) {
			context.fill(left, top, right, bottom, tile.averageColor());
			return;
		}

		int widthPixels = right - left;
		int heightPixels = bottom - top;
		int[] colors = tile.colors();
		for (int pixelZ = 0; pixelZ < 16; pixelZ++) {
			int rowTop = top + (heightPixels * pixelZ) / 16;
			int rowBottom = top + (heightPixels * (pixelZ + 1)) / 16;
			if (rowBottom <= rowTop) {
				continue;
			}
			for (int pixelX = 0; pixelX < 16; pixelX++) {
				int columnLeft = left + (widthPixels * pixelX) / 16;
				int columnRight = left + (widthPixels * (pixelX + 1)) / 16;
				if (columnRight <= columnLeft) {
					continue;
				}
				int color = colors[(pixelZ << 4) | pixelX];
				context.fill(columnLeft, rowTop, columnRight, rowBottom, color);
			}
		}
	}

	private boolean isChunkSelected(int chunkX, int chunkZ) {
		if (!this.hasSelection()) {
			return false;
		}
		int minX = Math.min(this.selectionStart.x, this.selectionEnd.x);
		int maxX = Math.max(this.selectionStart.x, this.selectionEnd.x);
		int minZ = Math.min(this.selectionStart.z, this.selectionEnd.z);
		int maxZ = Math.max(this.selectionStart.z, this.selectionEnd.z);
		return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
	}

	private ChunkPos chunkAt(double mouseX, double mouseY) {
		if (!this.contains(mouseX, mouseY)) {
			return null;
		}
		int chunkX = (int) Math.floor(this.screenToChunkX(mouseX));
		int chunkZ = (int) Math.floor(this.screenToChunkZ(mouseY));
		return new ChunkPos(chunkX, chunkZ);
	}

	private double chunkScale() {
		return BASE_CHUNK_PIXELS * this.zoom;
	}

	private double screenToChunkX(double screenX) {
		double centerScreenX = this.x + (this.width / 2.0D);
		return this.centerChunkX + ((screenX - centerScreenX) / this.chunkScale());
	}

	private double screenToChunkZ(double screenY) {
		double centerScreenY = this.y + (this.height / 2.0D);
		return this.centerChunkZ + ((screenY - centerScreenY) / this.chunkScale());
	}

	private boolean contains(double mouseX, double mouseY) {
		return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
