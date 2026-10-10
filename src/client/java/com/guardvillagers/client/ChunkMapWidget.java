package com.guardvillagers.client;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongComparator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.ChunkPos;

public final class ChunkMapWidget {
	private static final double MIN_ZOOM = 0.05D;
	private static final double MAX_ZOOM = 32.0D;
	private static final double ZOOM_STEP_BASE = 1.15D;
	private static final double ZOOM_SMOOTHING_PER_SECOND = 16.0D;
	private static final double BASE_CHUNK_PIXELS = 16.0D;
	private static final double DETAILED_TEXTURE_MIN_CHUNK_PIXELS = 4.0D;
	private static final double LABEL_MIN_CHUNK_PIXELS = 11.0D;
	private static final int PAN_MARGIN_CHUNKS = 4;

	private static final int MAP_BACKGROUND = 0xFF10161D;
	private static final int SELECTION_OVERLAY = 0x446FD3FF;
	private static final int SELECTION_BORDER = 0xFF6FD3FF;
	private static final int HOVER_OVERLAY = 0x33FFFFFF;
	private static final int ERASE_PREVIEW = 0xFFFF6B6B;
	private static final int PREVIEW_SHADOW = 0xAA000000;
	private static final int ZONE_OUTLINE_ALPHA_MASK = 0xCC000000;

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
	private double targetZoom = 1.0D;
	private boolean zoomAnimating;
	private double zoomAnchorScreenX;
	private double zoomAnchorScreenY;
	private double zoomAnchorChunkX;
	private double zoomAnchorChunkZ;
	private long lastFrameNanos;
	private RegionColor activeColor = RegionColor.BLUE;
	private ChunkPos selectionStart;
	private ChunkPos selectionEnd;
	private boolean selecting;
	private boolean panning;
	private boolean painting;
	private boolean paintErase;
	private int lastPaintX;
	private int lastPaintZ;
	private ChunkPos hoveredChunk;
	private int visibleChunkCount;
	private int[] chunkXEdges = new int[64];
	private int[] chunkZEdges = new int[64];
	private final int[] discoveredBounds = new int[4];
	private boolean hasDiscoveredBounds;
	private final LongArrayList visibleChunkKeys = new LongArrayList();
	private final LongComparator distanceToCamera = (a, b) -> Double.compare(this.distanceToCameraSq(a), this.distanceToCameraSq(b));

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
		this.centerOn(centerChunk);
	}

	public void centerOn(ChunkPos chunk) {
		if (chunk == null) {
			return;
		}
		this.centerChunkX = chunk.x() + 0.5D;
		this.centerChunkZ = chunk.z() + 0.5D;
		this.zoomAnimating = false;
		this.targetZoom = this.zoom;
		this.cameraInitialized = true;
	}

	/**
	 * @param eraseModifier whether shift is held, so the hover preview shows the erase brush
	 */
	public void render(
			GuiGraphicsExtractor graphics,
			Font font,
			ClientLevel world,
			ClientTacticsDataStore.WorldContext worldContext,
			int mouseX,
			int mouseY,
			boolean eraseModifier) {
		this.advanceZoom();
		graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, MAP_BACKGROUND);
		this.visibleChunkCount = 0;
		if (world == null || worldContext == null) {
			this.hoveredChunk = null;
			this.hasDiscoveredBounds = false;
			return;
		}
		this.hasDiscoveredBounds = this.dataStore.discoveredBounds(worldContext, this.discoveredBounds);
		this.clampCamera();

		double chunkScale = this.chunkScale();
		double viewportCenterX = this.x + (this.width / 2.0D);
		double viewportCenterY = this.y + (this.height / 2.0D);
		int minChunkX = (int) Math.floor(this.centerChunkX + ((this.x - viewportCenterX) / chunkScale)) - 1;
		int maxChunkX = (int) Math.ceil(this.centerChunkX + (((this.x + this.width) - viewportCenterX) / chunkScale)) + 1;
		int minChunkZ = (int) Math.floor(this.centerChunkZ + ((this.y - viewportCenterY) / chunkScale)) - 1;
		int maxChunkZ = (int) Math.ceil(this.centerChunkZ + (((this.y + this.height) - viewportCenterY) / chunkScale)) + 1;

		LongArrayList visible = this.visibleChunkKeys;
		this.dataStore.collectDiscoveredInBounds(worldContext, minChunkX, maxChunkX, minChunkZ, maxChunkZ, visible);
		LongArrays.quickSort(visible.elements(), 0, visible.size(), this.distanceToCamera);
		this.terrainCache.prepareVisibleTiles(worldContext, world, visible);
		this.visibleChunkCount = visible.size();

		int[] xEdges = this.chunkXEdges = computeChunkEdges(this.chunkXEdges, minChunkX, maxChunkX, this.centerChunkX, viewportCenterX, chunkScale);
		int[] zEdges = this.chunkZEdges = computeChunkEdges(this.chunkZEdges, minChunkZ, maxChunkZ, this.centerChunkZ, viewportCenterY, chunkScale);
		boolean drawLabels = chunkScale >= LABEL_MIN_CHUNK_PIXELS && font != null;

		graphics.enableScissor(this.x, this.y, this.x + this.width, this.y + this.height);
		for (int i = 0, size = visible.size(); i < size; i++) {
			long key = visible.getLong(i);
			int chunkX = ChunkPos.getX(key);
			int chunkZ = ChunkPos.getZ(key);
			int xIndex = chunkX - minChunkX;
			int zIndex = chunkZ - minChunkZ;
			int left = xEdges[xIndex];
			int right = xEdges[xIndex + 1];
			int top = zEdges[zIndex];
			int bottom = zEdges[zIndex + 1];
			if (right <= left || bottom <= top) {
				continue;
			}

			this.renderChunkTerrain(graphics, worldContext, chunkX, chunkZ, left, top, right, bottom);

			RegionColor regionColor = this.dataStore.getRegionColor(worldContext, chunkX, chunkZ);
			if (regionColor != RegionColor.NONE) {
				this.drawZoneHighlight(graphics, font, drawLabels, worldContext, chunkX, chunkZ, left, top, right, bottom, regionColor);
			}

			if (this.isChunkSelected(chunkX, chunkZ)) {
				graphics.fill(left, top, right, bottom, SELECTION_OVERLAY);
			}
		}

		if (this.hasSelection()) {
			int selLeft = this.chunkToScreenX(Math.min(this.selectionStart.x(), this.selectionEnd.x()));
			int selRight = this.chunkToScreenX(Math.max(this.selectionStart.x(), this.selectionEnd.x()) + 1);
			int selTop = this.chunkToScreenZ(Math.min(this.selectionStart.z(), this.selectionEnd.z()));
			int selBottom = this.chunkToScreenZ(Math.max(this.selectionStart.z(), this.selectionEnd.z()) + 1);
			graphics.outline(selLeft, selTop, Math.max(1, selRight - selLeft), Math.max(1, selBottom - selTop), SELECTION_BORDER);
		}

		this.renderHover(graphics, worldContext, mouseX, mouseY, eraseModifier);
		graphics.disableScissor();
	}

	private void renderHover(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY, boolean eraseModifier) {
		if (!this.contains(mouseX, mouseY) || this.panning) {
			this.hoveredChunk = null;
			return;
		}
		int hoverX = (int) Math.floor(this.screenToChunkX(mouseX));
		int hoverZ = (int) Math.floor(this.screenToChunkZ(mouseY));
		if (!this.dataStore.isDiscovered(worldContext, hoverX, hoverZ)) {
			this.hoveredChunk = null;
			return;
		}
		if (this.hoveredChunk == null || this.hoveredChunk.x() != hoverX || this.hoveredChunk.z() != hoverZ) {
			this.hoveredChunk = new ChunkPos(hoverX, hoverZ);
		}

		int left = this.chunkToScreenX(hoverX);
		int right = this.chunkToScreenX(hoverX + 1);
		int top = this.chunkToScreenZ(hoverZ);
		int bottom = this.chunkToScreenZ(hoverZ + 1);
		if (right > left && bottom > top) {
			graphics.fill(left, top, right, bottom, HOVER_OVERLAY);
		}
		if (this.selecting) {
			return;
		}

		// Brush preview: what a right-click would paint (the whole selection when one exists).
		boolean erase = this.painting ? this.paintErase : eraseModifier;
		int previewColor = erase ? ERASE_PREVIEW : this.activeColor.swatchArgb();
		if (this.hasSelection()) {
			left = this.chunkToScreenX(Math.min(this.selectionStart.x(), this.selectionEnd.x()));
			right = this.chunkToScreenX(Math.max(this.selectionStart.x(), this.selectionEnd.x()) + 1);
			top = this.chunkToScreenZ(Math.min(this.selectionStart.z(), this.selectionEnd.z()));
			bottom = this.chunkToScreenZ(Math.max(this.selectionStart.z(), this.selectionEnd.z()) + 1);
		}
		int w = right - left;
		int h = bottom - top;
		if (w >= 3 && h >= 3) {
			graphics.outline(left - 1, top - 1, w + 2, h + 2, PREVIEW_SHADOW);
			graphics.outline(left, top, w, h, previewColor);
		} else if (w >= 1 && h >= 1) {
			graphics.fill(left, top, right, bottom, previewColor);
		}
	}

	/** Draws the player's position and facing; called after {@link #render} with the same bounds. */
	public void drawPlayerMarker(GuiGraphicsExtractor graphics, double chunkX, double chunkZ, float yawDegrees) {
		double scale = this.chunkScale();
		int px = (int) Math.round(this.x + (this.width / 2.0D) + ((chunkX - this.centerChunkX) * scale));
		int py = (int) Math.round(this.y + (this.height / 2.0D) + ((chunkZ - this.centerChunkZ) * scale));
		if (!this.contains(px, py)) {
			return;
		}
		// Minecraft yaw 0 faces +Z (down on this map); 90 faces -X.
		double radians = Math.toRadians(yawDegrees);
		int tipX = px + (int) Math.round(-Math.sin(radians) * 5.0D);
		int tipY = py + (int) Math.round(Math.cos(radians) * 5.0D);
		graphics.enableScissor(this.x, this.y, this.x + this.width, this.y + this.height);
		graphics.fill(tipX - 1, tipY - 1, tipX + 1, tipY + 1, 0xFFFFFFFF);
		graphics.fill(px - 3, py - 3, px + 3, py + 3, 0xFF10161D);
		graphics.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFFFFF);
		graphics.disableScissor();
	}

	public boolean mouseClicked(
			double mouseX,
			double mouseY,
			int button,
			boolean shiftDown,
			ClientLevel world,
			ClientTacticsDataStore.WorldContext worldContext) {
		if (!this.contains(mouseX, mouseY) || world == null || worldContext == null) {
			return false;
		}

		if (button == 2) {
			this.panning = true;
			this.selecting = false;
			this.painting = false;
			return true;
		}

		if (button == 0) {
			ChunkPos anchor = this.chunkAt(mouseX, mouseY);
			if (anchor == null || !this.dataStore.isDiscovered(worldContext, anchor.x(), anchor.z())) {
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
			int chunkX = (int) Math.floor(this.screenToChunkX(mouseX));
			int chunkZ = (int) Math.floor(this.screenToChunkZ(mouseY));
			if (this.hasSelection()) {
				this.paintSelection(worldContext, shiftDown);
				return true;
			}
			// Without a selection the right button is a brush: drag to keep painting.
			this.painting = true;
			this.paintErase = shiftDown;
			this.lastPaintX = chunkX;
			this.lastPaintZ = chunkZ;
			this.dataStore.setRegionColor(worldContext, chunkX, chunkZ, this.paintColor());
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
			ClientLevel world,
			ClientTacticsDataStore.WorldContext worldContext) {
		if (world == null || worldContext == null) {
			return false;
		}

		if (button == 2 && this.panning) {
			this.panByPixels(-deltaX, -deltaY);
			return true;
		}

		if (button == 0 && this.selecting) {
			ChunkPos next = this.chunkAtClamped(mouseX, mouseY);
			this.selectionEnd = next;
			return true;
		}

		if (button == 1 && this.painting) {
			if (!this.contains(mouseX, mouseY)) {
				return true;
			}
			int chunkX = (int) Math.floor(this.screenToChunkX(mouseX));
			int chunkZ = (int) Math.floor(this.screenToChunkZ(mouseY));
			if (chunkX != this.lastPaintX || chunkZ != this.lastPaintZ) {
				this.paintLine(worldContext, this.lastPaintX, this.lastPaintZ, chunkX, chunkZ);
				this.lastPaintX = chunkX;
				this.lastPaintZ = chunkZ;
			}
			return true;
		}
		return false;
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0 && this.selecting) {
			this.selectionEnd = this.chunkAtClamped(mouseX, mouseY);
			this.selecting = false;
			return true;
		}
		if (button == 1 && this.painting) {
			this.painting = false;
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
		this.zoomAt(mouseX, mouseY, Math.pow(ZOOM_STEP_BASE, verticalAmount));
		return true;
	}

	/** Starts a smooth zoom that keeps the chunk under (screenX, screenY) fixed on screen. */
	public void zoomAt(double screenX, double screenY, double factor) {
		this.zoomAnchorScreenX = screenX;
		this.zoomAnchorScreenY = screenY;
		this.zoomAnchorChunkX = this.screenToChunkX(screenX);
		this.zoomAnchorChunkZ = this.screenToChunkZ(screenY);
		this.targetZoom = clamp(this.targetZoom * factor, MIN_ZOOM, MAX_ZOOM);
		this.zoomAnimating = true;
	}

	/** Zoom anchored at the viewport centre, used by the +/- keys. */
	public void zoomSteps(int steps) {
		this.zoomAt(this.x + (this.width / 2.0D), this.y + (this.height / 2.0D), Math.pow(ZOOM_STEP_BASE, steps * 2));
	}

	public void panByPixels(double deltaX, double deltaY) {
		double scale = this.chunkScale();
		double dx = deltaX / scale;
		double dz = deltaY / scale;
		this.centerChunkX += dx;
		this.centerChunkZ += dz;
		// Keep an in-flight zoom consistent with the pan instead of snapping the camera back.
		this.zoomAnchorChunkX += dx;
		this.zoomAnchorChunkZ += dz;
		this.clampCamera();
	}

	public void paintSelection(ClientTacticsDataStore.WorldContext worldContext, boolean erase) {
		if (!this.hasSelection() || worldContext == null) {
			return;
		}
		RegionColor paintColor = erase ? RegionColor.NONE : this.activeColor;
		int minX = Math.min(this.selectionStart.x(), this.selectionEnd.x());
		int maxX = Math.max(this.selectionStart.x(), this.selectionEnd.x());
		int minZ = Math.min(this.selectionStart.z(), this.selectionEnd.z());
		int maxZ = Math.max(this.selectionStart.z(), this.selectionEnd.z());
		for (int chunkX = minX; chunkX <= maxX; chunkX++) {
			for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
				this.dataStore.setRegionColor(worldContext, ChunkPos.pack(chunkX, chunkZ), paintColor);
			}
		}
	}

	public void clearSelection() {
		this.selectionStart = null;
		this.selectionEnd = null;
		this.selecting = false;
	}

	public boolean hasSelection() {
		return this.selectionStart != null && this.selectionEnd != null;
	}

	public int selectionWidth() {
		return this.hasSelection() ? Math.abs(this.selectionEnd.x() - this.selectionStart.x()) + 1 : 0;
	}

	public int selectionHeight() {
		return this.hasSelection() ? Math.abs(this.selectionEnd.z() - this.selectionStart.z()) + 1 : 0;
	}

	public boolean isSelecting() {
		return this.selecting;
	}

	public boolean isPainting() {
		return this.painting;
	}

	public boolean isErasing() {
		return this.painting && this.paintErase;
	}

	public boolean isPanning() {
		return this.panning;
	}

	public boolean isMouseOver(double mouseX, double mouseY) {
		return this.contains(mouseX, mouseY);
	}

	/** Discovered chunks drawn last frame; zero means the map shows nothing at this camera position. */
	public int visibleChunkCount() {
		return this.visibleChunkCount;
	}

	public boolean hasDiscoveredChunks() {
		return this.hasDiscoveredBounds;
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

	private RegionColor paintColor() {
		return this.paintErase ? RegionColor.NONE : this.activeColor;
	}

	// Fast drags skip chunks between mouse events, so fill the gap with a Bresenham line.
	private void paintLine(ClientTacticsDataStore.WorldContext worldContext, int fromX, int fromZ, int toX, int toZ) {
		RegionColor color = this.paintColor();
		int dx = Math.abs(toX - fromX);
		int dz = -Math.abs(toZ - fromZ);
		int stepX = fromX < toX ? 1 : -1;
		int stepZ = fromZ < toZ ? 1 : -1;
		int error = dx + dz;
		int cx = fromX;
		int cz = fromZ;
		for (int guard = 0; guard < 4096; guard++) {
			this.dataStore.setRegionColor(worldContext, cx, cz, color);
			if (cx == toX && cz == toZ) {
				return;
			}
			int doubled = 2 * error;
			if (doubled >= dz) {
				error += dz;
				cx += stepX;
			}
			if (doubled <= dx) {
				error += dx;
				cz += stepZ;
			}
		}
	}

	private void advanceZoom() {
		long now = System.nanoTime();
		double seconds = this.lastFrameNanos == 0L ? 0.0D : Math.min(0.1D, (now - this.lastFrameNanos) / 1.0E9D);
		this.lastFrameNanos = now;
		if (!this.zoomAnimating) {
			return;
		}
		double blend = 1.0D - Math.exp(-ZOOM_SMOOTHING_PER_SECOND * seconds);
		double logCurrent = Math.log(this.zoom);
		double logTarget = Math.log(this.targetZoom);
		double next = Math.exp(logCurrent + ((logTarget - logCurrent) * blend));
		if (Math.abs(logTarget - Math.log(next)) < 0.002D) {
			next = this.targetZoom;
			this.zoomAnimating = false;
		}
		this.zoom = next;
		double scale = this.chunkScale();
		this.centerChunkX = this.zoomAnchorChunkX - ((this.zoomAnchorScreenX - (this.x + (this.width / 2.0D))) / scale);
		this.centerChunkZ = this.zoomAnchorChunkZ - ((this.zoomAnchorScreenY - (this.y + (this.height / 2.0D))) / scale);
	}

	// Keeps the camera centre near explored land so the map can never be panned into an empty void.
	private void clampCamera() {
		if (!this.hasDiscoveredBounds) {
			return;
		}
		this.centerChunkX = clamp(this.centerChunkX, this.discoveredBounds[0] - PAN_MARGIN_CHUNKS, this.discoveredBounds[1] + 1 + PAN_MARGIN_CHUNKS);
		this.centerChunkZ = clamp(this.centerChunkZ, this.discoveredBounds[2] - PAN_MARGIN_CHUNKS, this.discoveredBounds[3] + 1 + PAN_MARGIN_CHUNKS);
	}

	private void renderChunkTerrain(
			GuiGraphicsExtractor graphics,
			ClientTacticsDataStore.WorldContext worldContext,
			int chunkX,
			int chunkZ,
			int left,
			int top,
			int right,
			int bottom) {
		ChunkTerrainCache.TerrainTile tile = this.terrainCache.getCachedTile(worldContext, chunkX, chunkZ);
		if (tile == null) {
			return;
		}

		DynamicTexture texture = Math.min(right - left, bottom - top) < DETAILED_TEXTURE_MIN_CHUNK_PIXELS
				? null
				: this.terrainCache.acquireTexture(tile);
		if (texture == null) {
			graphics.fill(left, top, right, bottom, tile.averageColor());
			return;
		}
		// Map the whole 16x16 tile onto the chunk's screen rect regardless of zoom.
		graphics.blit(texture.getTextureView(), texture.getSampler(), left, top, right, bottom, 0.0F, 1.0F, 0.0F, 1.0F);
	}

	private void drawZoneHighlight(
			GuiGraphicsExtractor graphics,
			Font font,
			boolean drawLabels,
			ClientTacticsDataStore.WorldContext worldContext,
			int chunkX,
			int chunkZ,
			int left,
			int top,
			int right,
			int bottom,
			RegionColor regionColor) {
		if (right - left < 1 || bottom - top < 1) {
			return;
		}

		graphics.fill(left, top, right, bottom, regionColor.overlayArgb());

		int outlineColor = (regionColor.swatchArgb() & 0x00FFFFFF) | ZONE_OUTLINE_ALPHA_MASK;
		boolean northEdge = this.dataStore.getRegionColor(worldContext, chunkX, chunkZ - 1) != regionColor;
		boolean westEdge = this.dataStore.getRegionColor(worldContext, chunkX - 1, chunkZ) != regionColor;
		if (northEdge) {
			graphics.fill(left, top, right, top + 1, outlineColor);
		}
		if (this.dataStore.getRegionColor(worldContext, chunkX, chunkZ + 1) != regionColor) {
			graphics.fill(left, bottom - 1, right, bottom, outlineColor);
		}
		if (westEdge) {
			graphics.fill(left, top, left + 1, bottom, outlineColor);
		}
		if (this.dataStore.getRegionColor(worldContext, chunkX + 1, chunkZ) != regionColor) {
			graphics.fill(right - 1, top, right, bottom, outlineColor);
		}

		// Colour-blind aid: the zone's palette number is stamped on each region's north-west corner chunk.
		if (drawLabels && northEdge && westEdge) {
			graphics.text(font, regionColor.hotkeyLabel(), left + 3, top + 2, 0xFFFFFFFF, true);
		}
	}

	private boolean isChunkSelected(int chunkX, int chunkZ) {
		if (!this.hasSelection()) {
			return false;
		}
		int minX = Math.min(this.selectionStart.x(), this.selectionEnd.x());
		int maxX = Math.max(this.selectionStart.x(), this.selectionEnd.x());
		int minZ = Math.min(this.selectionStart.z(), this.selectionEnd.z());
		int maxZ = Math.max(this.selectionStart.z(), this.selectionEnd.z());
		return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
	}

	private ChunkPos chunkAt(double mouseX, double mouseY) {
		if (!this.contains(mouseX, mouseY)) {
			return null;
		}
		return new ChunkPos((int) Math.floor(this.screenToChunkX(mouseX)), (int) Math.floor(this.screenToChunkZ(mouseY)));
	}

	// Selection drags that leave the map stick to its edge instead of freezing at the last in-map chunk.
	private ChunkPos chunkAtClamped(double mouseX, double mouseY) {
		double clampedX = clamp(mouseX, this.x, this.x + this.width - 1);
		double clampedY = clamp(mouseY, this.y, this.y + this.height - 1);
		return new ChunkPos((int) Math.floor(this.screenToChunkX(clampedX)), (int) Math.floor(this.screenToChunkZ(clampedY)));
	}

	// Edges are rounded from exact positions so they always match chunkAt(); below 1px per chunk some chunks
	// get zero width and are skipped instead of pushing every later chunk off its true position.
	private static int[] computeChunkEdges(int[] reuse, int minChunk, int maxChunk, double centerChunkAxis, double viewportCenterAxis, double chunkScale) {
		int count = (maxChunk - minChunk) + 2;
		int[] edges = reuse.length >= count ? reuse : new int[Math.max(count, reuse.length * 2)];
		for (int index = 0; index < count; index++) {
			edges[index] = (int) Math.round(viewportCenterAxis + (((minChunk + index) - centerChunkAxis) * chunkScale));
		}
		return edges;
	}

	private int chunkToScreenX(int chunkX) {
		return (int) Math.round(this.x + (this.width / 2.0D) + ((chunkX - this.centerChunkX) * this.chunkScale()));
	}

	private int chunkToScreenZ(int chunkZ) {
		return (int) Math.round(this.y + (this.height / 2.0D) + ((chunkZ - this.centerChunkZ) * this.chunkScale()));
	}

	private double distanceToCameraSq(long chunkKey) {
		double dx = (ChunkPos.getX(chunkKey) + 0.5D) - this.centerChunkX;
		double dz = (ChunkPos.getZ(chunkKey) + 0.5D) - this.centerChunkZ;
		return (dx * dx) + (dz * dz);
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
