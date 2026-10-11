package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Style;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Operator debug overlay. Geometry is emitted as per-frame gizmos at the end of level extraction (the same pipeline
 * vanilla debug renderers use); labels are submitted as text with a background during submit collection.
 *
 * <p>Color legend (geometry and text share it):
 * <ul>
 *   <li>aqua ring: detection range of the guard under the crosshair</li>
 *   <li>magenta: current target (line from the guard's eyes and the "Target" label line)</li>
 *   <li>path: lime = node being walked to, yellow = upcoming nodes, orange = destination</li>
 *   <li>HP line: green above 60%, yellow above 30%, red below</li>
 * </ul>
 *
 * <p>Everything fades with camera distance. Only the guard under the crosshair (own ray pick, 48 blocks) gets the
 * ring and the full label; other guards show a two-line summary (plus target), and labels that would overlap a
 * nearer or focused label are skipped so crowds stay readable. Label values are checked once per game
 * tick; unchanged text and measured widths are reused in pooled buffers. The gizmo API itself still allocates one small
 * record per primitive, as it does for vanilla debug renderers.
 */
public final class GuardDebugRenderer {
	private static final int CACHE_REFRESH_TICKS = 10;
	private static final int CIRCLE_SEGMENTS = 48;
	private static final double DETECTION_RANGE = 32.0D;
	private static final double CIRCLE_Y_OFFSET = 0.05D;
	private static final float LABEL_SCALE = 0.020F;
	private static final double LABEL_Y_OFFSET = 0.85D;
	private static final int LABEL_LINE_COUNT = 5;
	private static final float LINE_WIDTH = 2.5F;
	private static final float PATH_OUTLINE_WIDTH = 2.0F;
	private static final int PATH_TRAIL_KEEP_BEHIND = 3;
	// Path nodes snap to integer block Y. When a guard walks on partial blocks (slabs, stairs, carpets) the actual
	// ground surface is above pos.getY(), so the marker floats high enough to stay above those surfaces and keeps a
	// thin body so it stays visible edge-on.
	private static final double PATH_MARKER_Y_BASE = 0.06D;
	private static final double PATH_MARKER_HEIGHT = 0.09D;
	private static final float PATH_FILL_ALPHA = 0.50F;
	private static final float PATH_OUTLINE_ALPHA = 0.82F;

	// Legend colors (RGB). Keep geometry and text on the same values.
	private static final int RGB_RING = 0x55FFFF;
	private static final int RGB_TARGET = 0xFF55FF;
	private static final int RGB_HEADER = 0xFFFFFF;
	private static final int RGB_DETAIL = 0xB0B0B0;
	private static final int RGB_HP_HIGH = 0x55FF55;
	private static final int RGB_HP_MID = 0xFFFF55;
	private static final int RGB_HP_LOW = 0xFF5555;
	private static final float[] PATH_CURRENT_RGB = {0.42F, 1.00F, 0.34F};
	private static final float[] PATH_NODE_RGB = {0.95F, 0.95F, 0.20F};
	private static final float[] PATH_DESTINATION_RGB = {1.00F, 0.55F, 0.20F};

	// Distance fade: full strength within FADE_START of the range, down to FADE_MIN at the edge. Path styles are
	// pre-built per fade step so fading never allocates a style.
	private static final double FADE_START = 0.45D;
	private static final float FADE_MIN = 0.30F;
	private static final int FADE_STEPS = 8;
	private static final GizmoStyle[] PATH_CURRENT_STYLES = pathStyles(PATH_CURRENT_RGB);
	private static final GizmoStyle[] PATH_NODE_STYLES = pathStyles(PATH_NODE_RGB);
	private static final GizmoStyle[] PATH_DESTINATION_STYLES = pathStyles(PATH_DESTINATION_RGB);
	private static final float[][] CIRCLE_POINTS = buildCirclePoints();
	private static final double FOCUS_PICK_RANGE = 48.0D;
	private static final double FOCUS_PICK_PADDING = 0.2D;
	private static final double[] SLAB_RANGE = new double[2];
	private static final Vector3f CAMERA_RIGHT = new Vector3f();
	private static final Vector3f CAMERA_UP = new Vector3f();
	private static final Vector3f CAMERA_FORWARD = new Vector3f();
	private static int[] drawOrder = new int[16];
	private static int[] sortScratch = new int[16];
	// Tangent-space bands narrow overlap checks without dropping any labels. Outer bands include infinity.
	private static final IntArrayList[] OVERLAP_BANDS = new IntArrayList[128];
	static {
		for (int i = 0; i < OVERLAP_BANDS.length; i++) {
			OVERLAP_BANDS[i] = new IntArrayList();
		}
	}

	private static final List<GuardEntity> CACHED_GUARDS = new ArrayList<>();
	private static final List<GuardLabel> LABEL_POOL = new ArrayList<>();
	private static int activeLabelCount;
	private static ClientLevel cachedLevel;
	private static long lastCacheTick = Long.MIN_VALUE;
	private static double lastCacheRange = -1.0D;
	private static long lastLabelTick = Long.MIN_VALUE;

	private GuardDebugRenderer() {
	}

	public static void register() {
		LevelRenderEvents.END_EXTRACTION.register(GuardDebugRenderer::extract);
		LevelRenderEvents.COLLECT_SUBMITS.register(GuardDebugRenderer::submitLabels);
		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				((ReloadableResourceManager) client.getResourceManager()).registerReloadListener(
						(ResourceManagerReloadListener) resources -> reset()));
	}

	/** Drops every cached entity reference and timer; call on disconnect so nothing leaks into the next world. */
	public static void reset() {
		CACHED_GUARDS.clear();
		for (GuardLabel label : LABEL_POOL) {
			label.clear();
		}
		activeLabelCount = 0;
		cachedLevel = null;
		lastCacheTick = Long.MIN_VALUE;
		lastCacheRange = -1.0D;
		lastLabelTick = Long.MIN_VALUE;
	}

	private static void extract(LevelExtractionContext context) {
		activeLabelCount = 0;
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = context.level();
		LocalPlayer player = client.player;
		if (level == null || player == null) {
			return;
		}
		if (!ClientDebugState.isEnabled()) {
			if (cachedLevel != null) {
				reset();
			}
			ClientGuardDebugData.clear();
			return;
		}

		double range = Math.max(1.0D, ClientDebugState.getRange());
		long tick = level.getGameTime();
		updateGuardCache(level, player, range, tick);
		if (CACHED_GUARDS.isEmpty()) {
			return;
		}

		boolean newTick = tick != lastLabelTick;
		lastLabelTick = tick;
		float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(false);
		GuardEntity focused = pickFocusedGuard(player, partialTick);
		// Vanilla only opens the per-frame gizmo collector inside Minecraft.runTick. Frames rendered from elsewhere
		// (Fabric client gametest screenshots call GameRenderer.extract/render directly while the per-tick collector
		// is active) would otherwise drop these gizmos for that frame. Scoping to the level renderer's per-frame
		// collector is a harmless nest during normal frames.
		Gizmos.TemporaryCollection gizmoScope = context.levelRenderer().collectPerFrameGizmos();
		try {
			emitGuards(client, level, player, range, focused, newTick, partialTick);
		} finally {
			gizmoScope.close();
		}
	}

	private static void emitGuards(
			Minecraft client,
			ClientLevel level,
			LocalPlayer player,
			double range,
			GuardEntity focused,
			boolean newTick,
			float partialTick
	) {
		double maxDistanceSq = range * range;
		ClientTacticsDataStore.WorldContext worldContext = null;

		for (GuardEntity guard : CACHED_GUARDS) {
			double distanceSq = guard.distanceToSqr(player);
			if (!isRenderable(guard, level, distanceSq, maxDistanceSq)) {
				continue;
			}
			boolean isFocused = guard == focused;
			float fade = fadeFor(Math.sqrt(distanceSq), range);
			ClientGuardDebugData.GuardDebugSnapshot snapshot = ClientGuardDebugData.get(guard.getId());
			LivingEntity target = resolveTarget(guard, snapshot, level);
			boolean hasTarget = target != null && target.isAlive();
			if (isFocused) {
				emitDetectionCircle(guard.getPosition(partialTick));
			}
			emitPathHighlights(snapshot, fade);
			if (hasTarget) {
				Gizmos.line(guard.getEyePosition(partialTick), target.getEyePosition(partialTick),
						ARGB.color(fade, RGB_TARGET), LINE_WIDTH);
			}

			GuardLabel label = obtainLabel(activeLabelCount++);
			label.x = Mth.lerp(partialTick, guard.xo, guard.getX());
			label.y = Mth.lerp(partialTick, guard.yo, guard.getY()) + guard.getBbHeight() + LABEL_Y_OFFSET;
			label.z = Mth.lerp(partialTick, guard.zo, guard.getZ());
			// Check label values once per tick (or on focus change); unchanged text retains its measured width.
			if (newTick || label.guardId != guard.getId() || label.focused != isFocused) {
				if (isFocused && worldContext == null) {
					worldContext = ClientTacticsDataStore.resolveContext(client, level);
				}
				label.guardId = guard.getId();
				label.focused = isFocused;
				buildLabelLines(label, guard, hasTarget ? target : null, isFocused, client, worldContext);
			}
		}
	}

	/**
	 * Picks the guard under the crosshair up to {@link #FOCUS_PICK_RANGE} blocks (vanilla's crosshair pick stops at
	 * interaction reach). Plain ray/AABB slab test over the cached guard list; blocks do not occlude, which suits an
	 * operator tool.
	 */
	private static GuardEntity pickFocusedGuard(LocalPlayer player, float partialTick) {
		Vec3 eye = player.getEyePosition(partialTick);
		Vec3 look = player.getViewVector(partialTick);
		GuardEntity best = null;
		double bestT = FOCUS_PICK_RANGE;
		for (GuardEntity guard : CACHED_GUARDS) {
			if (guard.isRemoved() || !guard.isAlive()) {
				continue;
			}
			AABB box = guard.getBoundingBox();
			double t = rayBoxEntry(eye, look,
					box.minX - FOCUS_PICK_PADDING, box.minY, box.minZ - FOCUS_PICK_PADDING,
					box.maxX + FOCUS_PICK_PADDING, box.maxY, box.maxZ + FOCUS_PICK_PADDING);
			if (t >= 0.0D && t < bestT) {
				bestT = t;
				best = guard;
			}
		}
		return best;
	}

	/** Distance along the ray to the box entry (0 if the origin is inside), or -1 when the ray misses. */
	private static double rayBoxEntry(
			Vec3 origin, Vec3 dir, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		double[] t = SLAB_RANGE;
		t[0] = 0.0D;
		t[1] = Double.MAX_VALUE;
		if (!clipSlab(origin.x, dir.x, minX, maxX, t)
				|| !clipSlab(origin.y, dir.y, minY, maxY, t)
				|| !clipSlab(origin.z, dir.z, minZ, maxZ, t)) {
			return -1.0D;
		}
		return t[0];
	}

	private static boolean clipSlab(double origin, double dir, double lo, double hi, double[] t) {
		if (Math.abs(dir) < 1.0E-9D) {
			return origin >= lo && origin <= hi;
		}
		double t1 = (lo - origin) / dir;
		double t2 = (hi - origin) / dir;
		t[0] = Math.max(t[0], Math.min(t1, t2));
		t[1] = Math.min(t[1], Math.max(t1, t2));
		return t[0] <= t[1];
	}

	private static boolean isRenderable(GuardEntity guard, ClientLevel level, double distanceSq, double maxDistanceSq) {
		return !guard.isRemoved() && guard.isAlive() && guard.level() == level && distanceSq <= maxDistanceSq;
	}

	private static float fadeFor(double distance, double range) {
		double start = range * FADE_START;
		if (distance <= start) {
			return 1.0F;
		}
		double t = Mth.clamp((distance - start) / Math.max(1.0D, range - start), 0.0D, 1.0D);
		return (float) Mth.lerp(t, 1.0D, FADE_MIN);
	}

	private static int fadeStep(float fade) {
		return Mth.clamp(Math.round((1.0F - fade) / (1.0F - FADE_MIN) * (FADE_STEPS - 1)), 0, FADE_STEPS - 1);
	}

	private static void updateGuardCache(ClientLevel level, LocalPlayer player, double range, long tick) {
		boolean sameLevel = level == cachedLevel;
		boolean fresh = sameLevel
				&& lastCacheTick != Long.MIN_VALUE
				&& tick >= lastCacheTick
				&& tick - lastCacheTick < CACHE_REFRESH_TICKS
				&& Math.abs(lastCacheRange - range) < 0.001D;
		if (fresh) {
			return;
		}

		cachedLevel = level;
		lastCacheTick = tick;
		lastCacheRange = range;
		double rangeSq = range * range;
		CACHED_GUARDS.clear();
		CACHED_GUARDS.addAll(level.getEntitiesOfClass(
				GuardEntity.class,
				player.getBoundingBox().inflate(range),
				guard -> guard.isAlive() && guard.distanceToSqr(player) <= rangeSq));
		ClientGuardDebugData.pruneMissing(level);
	}

	private static void emitDetectionCircle(Vec3 center) {
		double y = center.y + CIRCLE_Y_OFFSET;
		int color = ARGB.color(0.85F, RGB_RING);
		Vec3 previous = new Vec3(center.x + DETECTION_RANGE * CIRCLE_POINTS[0][0], y, center.z + DETECTION_RANGE * CIRCLE_POINTS[0][1]);
		for (int i = 1; i <= CIRCLE_SEGMENTS; i++) {
			Vec3 next = new Vec3(center.x + DETECTION_RANGE * CIRCLE_POINTS[i][0], y, center.z + DETECTION_RANGE * CIRCLE_POINTS[i][1]);
			Gizmos.line(previous, next, color, LINE_WIDTH);
			previous = next;
		}
	}

	private static void emitPathHighlights(ClientGuardDebugData.GuardDebugSnapshot snapshot, float fade) {
		if (snapshot == null || snapshot.pathNodes().isEmpty()) {
			return;
		}
		List<BlockPos> nodes = snapshot.pathNodes();
		int currentPathIndex = snapshot.currentPathIndex();
		if (currentPathIndex < 0 || currentPathIndex >= nodes.size()) {
			return;
		}

		int step = fadeStep(fade);
		int lastIndex = nodes.size() - 1;
		for (int i = Math.max(0, currentPathIndex - PATH_TRAIL_KEEP_BEHIND); i <= lastIndex; i++) {
			GizmoStyle style = i == currentPathIndex
					? PATH_CURRENT_STYLES[step]
					: i == lastIndex ? PATH_DESTINATION_STYLES[step] : PATH_NODE_STYLES[step];
			BlockPos node = nodes.get(i);
			double minY = node.getY() + PATH_MARKER_Y_BASE;
			Gizmos.cuboid(
					new AABB(node.getX(), minY, node.getZ(), node.getX() + 1.0D, minY + PATH_MARKER_HEIGHT, node.getZ() + 1.0D),
					style);
		}
	}

	private static LivingEntity resolveTarget(
			GuardEntity guard,
			ClientGuardDebugData.GuardDebugSnapshot snapshot,
			ClientLevel level
	) {
		if (snapshot != null && snapshot.targetEntityId() >= 0) {
			Entity synced = level.getEntity(snapshot.targetEntityId());
			if (synced instanceof LivingEntity living) {
				return living;
			}
		}
		// Mob targets are server-side only, so this is normally null on a remote client.
		return guard.getTarget();
	}

	private static void submitLabels(LevelRenderContext context) {
		if (activeLabelCount == 0) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;
		CameraRenderState camera = context.levelState().cameraRenderState;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		double range = Math.max(1.0D, ClientDebugState.getRange());
		int baseBackgroundAlpha = ARGB.alpha(client.options.getBackgroundColor(0.25F));
		int lineStep = font.lineHeight + 1;
		int visible = layoutLabels(camera, lineStep);

		for (int order = 0; order < visible; order++) {
			GuardLabel label = LABEL_POOL.get(drawOrder[order]);
			double dx = label.x - camera.pos.x;
			double dy = label.y - camera.pos.y;
			double dz = label.z - camera.pos.z;
			float fade = label.focused ? 1.0F : fadeFor(Math.sqrt(dx * dx + dy * dy + dz * dz), range);
			int background = ARGB.color(Math.round(baseBackgroundAlpha * fade), 0);

			poseStack.pushPose();
			poseStack.translate(dx, dy, dz);
			poseStack.mulPose(camera.orientation);
			poseStack.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
			float lineY = -(label.lineCount - 1) * lineStep;
			for (int line = 0; line < label.lineCount; line++) {
				collector.submitText(
						poseStack,
						-label.widths[line] / 2.0F,
						lineY,
						label.texts[line],
						false,
						Font.DisplayMode.NORMAL,
						LightCoordsUtil.FULL_BRIGHT,
						ARGB.color(fade, label.colors[line]),
						background,
						0);
				lineY += lineStep;
			}
			poseStack.popPose();
		}
	}

	/**
	 * Projects every label to a rectangle in camera tangent space and keeps only labels that do not overlap one
	 * already kept. The focused label wins, then nearer labels. Fills {@link #drawOrder}; returns how many to draw.
	 */
	private static int layoutLabels(CameraRenderState camera, int lineStep) {
		camera.orientation.transform(CAMERA_RIGHT.set(1.0F, 0.0F, 0.0F));
		camera.orientation.transform(CAMERA_UP.set(0.0F, 1.0F, 0.0F));
		camera.orientation.transform(CAMERA_FORWARD.set(0.0F, 0.0F, -1.0F));
		if (drawOrder.length < activeLabelCount) {
			drawOrder = new int[Math.max(activeLabelCount, drawOrder.length * 2)];
			sortScratch = new int[drawOrder.length];
		}
		int candidates = 0;
		for (int i = 0; i < activeLabelCount; i++) {
			GuardLabel label = LABEL_POOL.get(i);
			double dx = label.x - camera.pos.x;
			double dy = label.y - camera.pos.y;
			double dz = label.z - camera.pos.z;
			double depth = dx * CAMERA_FORWARD.x + dy * CAMERA_FORWARD.y + dz * CAMERA_FORWARD.z;
			if (depth < 0.1D) {
				continue;
			}
			// The text block is drawn upward from the anchor: its last line sits on the anchor.
			double lineHeightWorld = lineStep * LABEL_SCALE;
			double heightWorld = label.lineCount * lineHeightWorld;
			double up = dx * CAMERA_UP.x + dy * CAMERA_UP.y + dz * CAMERA_UP.z;
			label.depth = depth;
			label.screenX = (dx * CAMERA_RIGHT.x + dy * CAMERA_RIGHT.y + dz * CAMERA_RIGHT.z) / depth;
			label.screenY = (up + heightWorld / 2.0D - lineHeightWorld) / depth;
			label.halfW = label.maxWidth() * LABEL_SCALE / 2.0D / depth;
			label.halfH = heightWorld / 2.0D / depth;
			drawOrder[candidates++] = i;
		}
		sortLabels(0, candidates);
		for (IntArrayList band : OVERLAP_BANDS) {
			band.clear();
		}
		int kept = 0;
		for (int i = 0; i < candidates; i++) {
			GuardLabel label = LABEL_POOL.get(drawOrder[i]);
			boolean overlaps = false;
			int firstBand = overlapBand(label.screenY - label.halfH);
			int lastBand = overlapBand(label.screenY + label.halfH);
			for (int b = firstBand; b <= lastBand && !overlaps; b++) {
				IntArrayList band = OVERLAP_BANDS[b];
				for (int k = 0; k < band.size() && !overlaps; k++) {
					GuardLabel other = LABEL_POOL.get(band.getInt(k));
					overlaps = Math.abs(label.screenX - other.screenX) < label.halfW + other.halfW
							&& Math.abs(label.screenY - other.screenY) < label.halfH + other.halfH;
				}
			}
			if (!overlaps) {
				for (int b = firstBand; b <= lastBand; b++) {
					OVERLAP_BANDS[b].add(drawOrder[i]);
				}
				drawOrder[kept++] = drawOrder[i];
			}
		}
		return kept;
	}

	private static int overlapBand(double y) {
		return Mth.clamp((int) Math.floor((y + 2.0D) * 32.0D), 0, OVERLAP_BANDS.length - 1);
	}

	/** Stable merge sort preserves the old focused/nearest/tie priority without quadratic crowd sorting. */
	private static void sortLabels(int from, int to) {
		if (to - from < 2) {
			return;
		}
		int middle = (from + to) >>> 1;
		sortLabels(from, middle);
		sortLabels(middle, to);
		int left = from;
		int right = middle;
		for (int i = from; i < to; i++) {
			if (left < middle && (right == to || !drawsBefore(LABEL_POOL.get(drawOrder[right]), LABEL_POOL.get(drawOrder[left])))) {
				sortScratch[i] = drawOrder[left++];
			} else {
				sortScratch[i] = drawOrder[right++];
			}
		}
		System.arraycopy(sortScratch, from, drawOrder, from, to - from);
	}

	private static boolean drawsBefore(GuardLabel a, GuardLabel b) {
		if (a.focused != b.focused) {
			return a.focused;
		}
		return a.depth < b.depth;
	}

	private static GuardLabel obtainLabel(int index) {
		while (LABEL_POOL.size() <= index) {
			LABEL_POOL.add(new GuardLabel());
		}
		return LABEL_POOL.get(index);
	}

	private static void buildLabelLines(
			GuardLabel label,
			GuardEntity guard,
			LivingEntity target,
			boolean focused,
			Minecraft client,
			ClientTacticsDataStore.WorldContext worldContext
	) {
		Font font = client.font;
		label.lineCount = 0;
		int level = guard.getLevel();
		GuardRole role = guard.getRole();
		if (label.header == null || label.level != level || label.role != role) {
			label.level = level;
			label.role = role;
			label.header = (role == GuardRole.BOWMAN ? "Bowman" : "Swordsman") + "  Lv " + level;
		}
		label.add(font, label.header, RGB_HEADER);

		float maxHealth = Math.max(1.0F, guard.getMaxHealth());
		float health = guard.getHealth();
		float ratio = Mth.clamp(health / maxHealth, 0.0F, 1.0F);
		int hpColor = ratio > 0.6F ? RGB_HP_HIGH : ratio > 0.3F ? RGB_HP_MID : RGB_HP_LOW;
		int hp = Mth.ceil(health);
		int maxHp = Mth.ceil(maxHealth);
		if (label.health == null || label.hp != hp || label.maxHp != maxHp) {
			label.hp = hp;
			label.maxHp = maxHp;
			label.health = "HP " + hp + "/" + maxHp;
		}
		label.add(font, label.health, hpColor);

		if (target != null) {
			label.add(font, "Target: " + target.getName().getString(), RGB_TARGET);
		}
		if (!focused) {
			return;
		}

		int xp = guard.getExperience();
		String xpText = level >= 10 ? "XP max" : "XP " + xp + "/" + (level * 120);
		label.add(font, xpText + "  " + titleCase(guard.getBehavior().name()), RGB_DETAIL);

		// Owner and group live server-side; the client only knows them for guards in its own synced roster.
		ClientGuardRosterStore.GuardRosterEntry rosterEntry = findRosterEntry(worldContext, guard.getUUID());
		RegionColor regionColor = ClientTacticsDataStore.getInstance()
				.getRegionColor(worldContext, guard.getBlockX() >> 4, guard.getBlockZ() >> 4);
		StringBuilder detail = new StringBuilder();
		if (rosterEntry != null) {
			detail.append("Yours");
			if (rosterEntry.groupIndex() >= 0 && !rosterEntry.groupName().isBlank()) {
				detail.append(" · ").append(rosterEntry.groupName());
			}
		}
		if (regionColor != RegionColor.NONE) {
			if (detail.length() > 0) {
				detail.append(" · ");
			}
			detail.append("Zone ").append(regionColor.label());
		}
		if (detail.length() > 0) {
			label.add(font, detail.toString(), regionColor != RegionColor.NONE ? regionColor.swatchArgb() & 0xFFFFFF : RGB_DETAIL);
		}
	}

	private static ClientGuardRosterStore.GuardRosterEntry findRosterEntry(
			ClientTacticsDataStore.WorldContext worldContext, UUID guardUuid) {
		for (ClientGuardRosterStore.GuardRosterEntry entry : ClientGuardRosterStore.getInstance().roster(worldContext)) {
			if (entry.guardUuid().equals(guardUuid)) {
				return entry;
			}
		}
		return null;
	}

	private static String titleCase(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private static GizmoStyle[] pathStyles(float[] rgb) {
		GizmoStyle[] styles = new GizmoStyle[FADE_STEPS];
		for (int step = 0; step < FADE_STEPS; step++) {
			float fade = Mth.lerp(step / (float) (FADE_STEPS - 1), 1.0F, FADE_MIN);
			styles[step] = GizmoStyle.strokeAndFill(
					ARGB.colorFromFloat(PATH_OUTLINE_ALPHA * fade, brighten(rgb[0]), brighten(rgb[1]), brighten(rgb[2])),
					PATH_OUTLINE_WIDTH,
					ARGB.colorFromFloat(PATH_FILL_ALPHA * fade, rgb[0], rgb[1], rgb[2]));
		}
		return styles;
	}

	private static float brighten(float channel) {
		return Math.min(1.0F, channel + 0.24F);
	}

	private static float[][] buildCirclePoints() {
		float[][] points = new float[CIRCLE_SEGMENTS + 1][2];
		for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
			double angle = (2.0D * Math.PI * i) / CIRCLE_SEGMENTS;
			points[i][0] = (float) Math.cos(angle);
			points[i][1] = (float) Math.sin(angle);
		}
		return points;
	}

	/** Reusable per-guard label buffer so the overlay does not allocate label containers every frame. */
	private static final class GuardLabel {
		private final String[] strings = new String[LABEL_LINE_COUNT];
		private final FormattedCharSequence[] texts = new FormattedCharSequence[LABEL_LINE_COUNT];
		private final int[] widths = new int[LABEL_LINE_COUNT];
		private final int[] colors = new int[LABEL_LINE_COUNT];
		private int lineCount;
		private int guardId = -1;
		private boolean focused;
		private double x;
		private double y;
		private double z;
		private double depth;
		private double screenX;
		private double screenY;
		private double halfW;
		private double halfH;
		private String header;
		private String health;
		private int level;
		private GuardRole role;
		private int hp;
		private int maxHp;

		private int maxWidth() {
			int max = 0;
			for (int i = 0; i < this.lineCount; i++) {
				max = Math.max(max, this.widths[i]);
			}
			return max + 2;
		}

		private void add(Font font, String text, int rgb) {
			if (this.lineCount >= LABEL_LINE_COUNT) {
				return;
			}
			if (!text.equals(this.strings[this.lineCount])) {
				this.strings[this.lineCount] = text;
				FormattedCharSequence sequence = FormattedCharSequence.forward(text, Style.EMPTY);
				this.texts[this.lineCount] = sequence;
				this.widths[this.lineCount] = font.width(sequence);
			}
			this.colors[this.lineCount] = rgb & 0xFFFFFF;
			this.lineCount++;
		}

		private void clear() {
			for (int i = 0; i < LABEL_LINE_COUNT; i++) {
				this.texts[i] = null;
				this.strings[i] = null;
			}
			this.lineCount = 0;
			this.guardId = -1;
			this.focused = false;
		}
	}
}
