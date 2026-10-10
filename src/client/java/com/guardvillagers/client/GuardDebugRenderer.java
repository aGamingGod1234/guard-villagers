package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Operator debug overlay. Geometry is emitted as per-frame gizmos at the end of level extraction (the same
 * pipeline vanilla debug renderers use); labels are submitted as text with a background during submit
 * collection so they keep the readable look of the old overlay.
 */
public final class GuardDebugRenderer {
	private static final int CACHE_REFRESH_TICKS = 10;
	private static final int CIRCLE_SEGMENTS = 64;
	private static final double DETECTION_RANGE = 32.0D;
	private static final double CIRCLE_Y_OFFSET = 0.01D;
	private static final float LABEL_SCALE = 0.020F;
	private static final double LABEL_Y_OFFSET = 0.75D;
	private static final int LABEL_LINE_COUNT = 9;
	private static final float LINE_WIDTH = 2.5F;
	private static final float PATH_OUTLINE_WIDTH = 2.0F;
	private static final int PATH_TRAIL_KEEP_BEHIND = 3;
	// Path nodes snap to integer block Y. When a guard walks on partial blocks (slabs,
	// stairs, carpets) the actual ground surface is above pos.getY(), so the marker must
	// float high enough to stay above those surfaces and keep a thin body so it stays
	// visible edge-on.
	private static final double PATH_MARKER_Y_BASE = 0.06D;
	private static final double PATH_MARKER_HEIGHT = 0.09D;
	private static final float PATH_FILL_ALPHA = 0.50F;
	private static final float PATH_OUTLINE_ALPHA = 0.82F;
	private static final GizmoStyle PATH_CURRENT_STYLE = pathStyle(0.42F, 1.00F, 0.34F);
	private static final GizmoStyle PATH_NODE_STYLE = pathStyle(0.95F, 0.95F, 0.20F);
	private static final GizmoStyle PATH_DESTINATION_STYLE = pathStyle(1.00F, 0.38F, 0.34F);
	private static final int CIRCLE_COLOR = ARGB.colorFromFloat(1.0F, 0.0F, 0.9F, 0.25F);
	private static final int TARGET_LINE_COLOR = ARGB.colorFromFloat(1.0F, 0.95F, 0.95F, 0.20F);
	private static final float[][] CIRCLE_POINTS = buildCirclePoints();

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

		boolean rebuildLabels = tick != lastLabelTick;
		lastLabelTick = tick;
		float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(false);
		double maxDistanceSq = range * range;
		ClientTacticsDataStore.WorldContext worldContext = null;

		for (GuardEntity guard : CACHED_GUARDS) {
			if (!isRenderable(guard, level, player, maxDistanceSq)) {
				continue;
			}
			ClientGuardDebugData.GuardDebugSnapshot snapshot = ClientGuardDebugData.get(guard.getId());
			LivingEntity target = resolveTarget(guard, snapshot, level);
			Vec3 position = guard.getPosition(partialTick);

			emitDetectionCircle(position);
			emitPathHighlights(snapshot);
			if (target != null && target.isAlive()) {
				Gizmos.line(guard.getEyePosition(partialTick), target.getEyePosition(partialTick), TARGET_LINE_COLOR, LINE_WIDTH);
			}

			GuardLabel label = obtainLabel(activeLabelCount++);
			label.x = position.x;
			label.y = position.y + guard.getBbHeight() + LABEL_Y_OFFSET;
			label.z = position.z;
			// Label text only changes with game state, so rebuild it once per tick rather than every frame.
			if (rebuildLabels || label.guardId != guard.getId()) {
				if (worldContext == null) {
					worldContext = ClientTacticsDataStore.resolveContext(client, level);
				}
				label.guardId = guard.getId();
				buildLabelLines(label, guard, target, client, worldContext);
			}
		}
	}

	private static boolean isRenderable(GuardEntity guard, ClientLevel level, LocalPlayer player, double maxDistanceSq) {
		return !guard.isRemoved()
				&& guard.isAlive()
				&& guard.level() == level
				&& guard.distanceToSqr(player) <= maxDistanceSq;
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
		Vec3 previous = new Vec3(center.x + DETECTION_RANGE * CIRCLE_POINTS[0][0], y, center.z + DETECTION_RANGE * CIRCLE_POINTS[0][1]);
		for (int i = 1; i <= CIRCLE_SEGMENTS; i++) {
			Vec3 next = new Vec3(center.x + DETECTION_RANGE * CIRCLE_POINTS[i][0], y, center.z + DETECTION_RANGE * CIRCLE_POINTS[i][1]);
			Gizmos.line(previous, next, CIRCLE_COLOR, LINE_WIDTH);
			previous = next;
		}
	}

	private static void emitPathHighlights(ClientGuardDebugData.GuardDebugSnapshot snapshot) {
		if (snapshot == null || snapshot.pathNodes().isEmpty()) {
			return;
		}
		List<BlockPos> nodes = snapshot.pathNodes();
		int currentPathIndex = snapshot.currentPathIndex();
		if (currentPathIndex < 0 || currentPathIndex >= nodes.size()) {
			return;
		}

		int lastIndex = nodes.size() - 1;
		for (int i = Math.max(0, currentPathIndex - PATH_TRAIL_KEEP_BEHIND); i <= lastIndex; i++) {
			GizmoStyle style = i == currentPathIndex
					? PATH_CURRENT_STYLE
					: i == lastIndex ? PATH_DESTINATION_STYLE : PATH_NODE_STYLE;
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
		int background = client.options.getBackgroundColor(0.25F);
		int lineStep = font.lineHeight + 2;

		for (int i = 0; i < activeLabelCount; i++) {
			GuardLabel label = LABEL_POOL.get(i);
			poseStack.pushPose();
			poseStack.translate(label.x - camera.pos.x, label.y - camera.pos.y, label.z - camera.pos.z);
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
						label.colors[line],
						background,
						0);
				lineY += lineStep;
			}
			poseStack.popPose();
		}
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
			Minecraft client,
			ClientTacticsDataStore.WorldContext worldContext
	) {
		Font font = client.font;
		label.lineCount = 0;
		int hp = Math.max(0, Mth.floor(guard.getHealth()));
		int maxHp = Math.max(1, Mth.floor(guard.getMaxHealth()));
		label.add(font, "HP: " + hp + "/" + maxHp, 0xFF5555);

		int level = guard.getLevel();
		label.add(font, "Level: " + level, 0x55FF55);

		int xp = guard.getExperience();
		label.add(font, level >= 10 ? "XP: " + xp + "/MAX" : "XP: " + xp + "/" + (level * 120), 0x55FFFF);

		label.add(font, "Role: " + titleCase(guard.getRole().name()), 0xFFFF55);

		String targetValue = target == null || !target.isAlive() ? "None" : target.getName().getString();
		label.add(font, "Target: " + targetValue, 0xFF55FF);

		label.add(font, "Type: " + (guard.getRole() == GuardRole.BOWMAN ? "Bowmen" : "Swordsmen"), 0xF5E6A9);

		UUID ownerUuid = guard.getOwnerUuid();
		String ownerValue = ownerUuid == null ? "Nil" : resolveOwnerName(ownerUuid, client);
		label.add(font, "Owned: " + ownerValue, ownerUuid == null ? 0xAAAAAA : 0xFFFFFF);

		RegionColor regionColor = ClientTacticsDataStore.getInstance()
				.getRegionColor(worldContext, guard.getBlockX() >> 4, guard.getBlockZ() >> 4);
		String zoneValue = regionColor == RegionColor.NONE ? "Nil" : regionColor.label();
		label.add(font, "Zone: " + zoneValue, regionColor == RegionColor.NONE ? 0xAAAAAA : 0x5555FF);

		String groupValue = guard.getGroupIndex() < 0 ? "Nil" : guard.getGroupName();
		label.add(font, "Group: " + groupValue, guard.getGroupIndex() < 0 ? 0xAAAAAA : 0xFFAA00);
	}

	private static String resolveOwnerName(UUID ownerUuid, Minecraft client) {
		if (client.getConnection() == null) {
			return shortUuid(ownerUuid);
		}
		PlayerInfo entry = client.getConnection().getPlayerInfo(ownerUuid);
		if (entry != null && entry.getProfile() != null) {
			return entry.getProfile().name();
		}
		return shortUuid(ownerUuid);
	}

	private static String shortUuid(UUID uuid) {
		String raw = uuid.toString();
		return raw.substring(0, Math.min(8, raw.length())) + "...";
	}

	private static String titleCase(String value) {
		if (value == null || value.isBlank()) {
			return "Nil";
		}
		String[] parts = value.toLowerCase(Locale.ROOT).split("_");
		StringBuilder builder = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(Character.toUpperCase(part.charAt(0)));
			if (part.length() > 1) {
				builder.append(part.substring(1));
			}
		}
		return builder.length() == 0 ? "Nil" : builder.toString();
	}

	private static GizmoStyle pathStyle(float r, float g, float b) {
		return GizmoStyle.strokeAndFill(
				ARGB.colorFromFloat(PATH_OUTLINE_ALPHA, brighten(r), brighten(g), brighten(b)),
				PATH_OUTLINE_WIDTH,
				ARGB.colorFromFloat(PATH_FILL_ALPHA, r, g, b));
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
		private final FormattedCharSequence[] texts = new FormattedCharSequence[LABEL_LINE_COUNT];
		private final int[] widths = new int[LABEL_LINE_COUNT];
		private final int[] colors = new int[LABEL_LINE_COUNT];
		private int lineCount;
		private int guardId = -1;
		private double x;
		private double y;
		private double z;

		private void add(Font font, String text, int rgb) {
			FormattedCharSequence sequence = FormattedCharSequence.forward(text, Style.EMPTY);
			this.texts[this.lineCount] = sequence;
			this.widths[this.lineCount] = font.width(sequence);
			// Font uses the alpha channel as-is, so 0xRRGGBB colors would draw fully transparent.
			this.colors[this.lineCount] = ARGB.opaque(rgb);
			this.lineCount++;
		}

		private void clear() {
			for (int i = 0; i < LABEL_LINE_COUNT; i++) {
				this.texts[i] = null;
			}
			this.lineCount = 0;
			this.guardId = -1;
		}
	}
}
