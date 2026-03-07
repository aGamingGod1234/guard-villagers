package com.guardvillagers.client;

import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class GuardDebugRenderer {
	private static final int CACHE_REFRESH_TICKS = 10;
	private static final int CIRCLE_SEGMENTS = 64;
	private static final double DETECTION_RANGE = 32.0D;
	private static final double CIRCLE_Y_OFFSET = 0.01D;
	private static final float LABEL_SCALE = 0.020F;
	private static final float LINE_WIDTH = 2.0F;
	private static final float[][] CIRCLE_POINTS = buildCirclePoints();
	private static final List<GuardEntity> CACHED_GUARDS = new ArrayList<>();
	private static long lastCacheTick = Long.MIN_VALUE;
	private static double lastCacheRange = -1.0D;

	private GuardDebugRenderer() {
	}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(GuardDebugRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			return;
		}
		if (!ClientDebugState.isEnabled()) {
			CACHED_GUARDS.clear();
			return;
		}

		double range = Math.max(1.0D, ClientDebugState.getRange());
		updateGuardCache(client, range);
		if (CACHED_GUARDS.isEmpty()) {
			return;
		}

		MatrixStack matrices = context.matrices();
		VertexConsumerProvider vertexConsumers = context.consumers();
		Vec3d cameraPos = context.worldState().cameraRenderState.pos;
		double maxDistanceSq = range * range;
		ClientTacticsDataStore.WorldContext worldContext = ClientTacticsDataStore.resolveContext(client, client.world);

		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

		for (GuardEntity guard : CACHED_GUARDS) {
			if (!guard.isAlive() || guard.squaredDistanceTo(client.player) > maxDistanceSq) {
				continue;
			}
			ClientGuardDebugData.GuardDebugSnapshot snapshot = ClientGuardDebugData.get(guard.getId());
			renderDetectionCircle(matrices, vertexConsumers, guard);
			renderPathHighlights(matrices, vertexConsumers, guard, snapshot);
			renderTargetLine(matrices, vertexConsumers, guard, snapshot, client);
		}
		for (GuardEntity guard : CACHED_GUARDS) {
			if (!guard.isAlive() || guard.squaredDistanceTo(client.player) > maxDistanceSq) {
				continue;
			}
			renderLabels(matrices, vertexConsumers, guard, client, worldContext);
		}

		matrices.pop();
		ClientGuardDebugData.pruneMissing(client.world);
	}

	private static void updateGuardCache(MinecraftClient client, double range) {
		if (client.world == null || client.player == null) {
			CACHED_GUARDS.clear();
			lastCacheTick = Long.MIN_VALUE;
			return;
		}
		long tick = client.world.getTime();
		if (lastCacheTick != Long.MIN_VALUE && tick - lastCacheTick < CACHE_REFRESH_TICKS && Math.abs(lastCacheRange - range) < 0.001D) {
			return;
		}

		lastCacheTick = tick;
		lastCacheRange = range;
		double rangeSq = range * range;
		CACHED_GUARDS.clear();
		CACHED_GUARDS.addAll(client.world.getEntitiesByClass(
			GuardEntity.class,
			client.player.getBoundingBox().expand(range),
			guard -> guard.isAlive() && guard.squaredDistanceTo(client.player) <= rangeSq
		));
	}

	private static void renderDetectionCircle(MatrixStack matrices, VertexConsumerProvider vertexConsumers, GuardEntity guard) {
		VertexConsumer lines = vertexConsumers.getBuffer(RenderLayers.lines());
		lines.lineWidth(LINE_WIDTH);
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		double centerX = guard.getX();
		double centerY = guard.getY() + CIRCLE_Y_OFFSET;
		double centerZ = guard.getZ();
		for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
			double x1 = centerX + DETECTION_RANGE * CIRCLE_POINTS[i][0];
			double z1 = centerZ + DETECTION_RANGE * CIRCLE_POINTS[i][1];
			double x2 = centerX + DETECTION_RANGE * CIRCLE_POINTS[i + 1][0];
			double z2 = centerZ + DETECTION_RANGE * CIRCLE_POINTS[i + 1][1];
			lineVertex(lines, matrix, x1, centerY, z1, 0.0F, 0.9F, 0.25F, 1.0F);
			lineVertex(lines, matrix, x2, centerY, z2, 0.0F, 0.9F, 0.25F, 1.0F);
		}
	}

	private static void renderPathHighlights(
		MatrixStack matrices,
		VertexConsumerProvider vertexConsumers,
		GuardEntity guard,
		ClientGuardDebugData.GuardDebugSnapshot snapshot
	) {
		if (snapshot == null || snapshot.pathNodes().isEmpty()) {
			return;
		}

		int currentIndex = snapshot.currentPathIndex();
		List<BlockPos> nodes = snapshot.pathNodes();
		if (currentIndex < 0 || currentIndex >= nodes.size()) {
			return;
		}

		VertexConsumer filled = vertexConsumers.getBuffer(RenderLayers.debugFilledBox());
		int startIndex = Math.max(0, currentIndex - 2);
		for (int i = startIndex; i < nodes.size(); i++) {
			BlockPos node = nodes.get(i);
			drawFilledBlock(matrices, filled, node, 0.0F, 0.39F, 1.0F, 0.30F);
		}
		drawFilledBlock(matrices, filled, guard.getBlockPos(), 1.0F, 0.0F, 0.0F, 0.30F);
	}

	private static void renderTargetLine(
		MatrixStack matrices,
		VertexConsumerProvider vertexConsumers,
		GuardEntity guard,
		ClientGuardDebugData.GuardDebugSnapshot snapshot,
		MinecraftClient client
	) {
		LivingEntity target = resolveTarget(guard, snapshot, client);
		if (target == null || !target.isAlive()) {
			return;
		}

		Vec3d origin = guard.getEyePos();
		Vec3d destination = closestPointOnBox(target.getBoundingBox(), origin);
		VertexConsumer lines = vertexConsumers.getBuffer(RenderLayers.lines());
		lines.lineWidth(LINE_WIDTH);
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		lineVertex(lines, matrix, origin.x, origin.y, origin.z, 1.0F, 1.0F, 0.0F, 1.0F);
		lineVertex(lines, matrix, destination.x, destination.y, destination.z, 1.0F, 1.0F, 0.0F, 1.0F);
	}

	private static LivingEntity resolveTarget(
		GuardEntity guard,
		ClientGuardDebugData.GuardDebugSnapshot snapshot,
		MinecraftClient client
	) {
		if (snapshot != null && snapshot.targetEntityId() >= 0 && client.world != null) {
			Entity synced = client.world.getEntityById(snapshot.targetEntityId());
			if (synced instanceof LivingEntity living) {
				return living;
			}
		}
		return guard.getTarget();
	}

	private static void renderLabels(
		MatrixStack matrices,
		VertexConsumerProvider vertexConsumers,
		GuardEntity guard,
		MinecraftClient client,
		ClientTacticsDataStore.WorldContext worldContext
	) {
		TextRenderer textRenderer = client.textRenderer;
		List<DebugLabelLine> lines = buildLabelLines(guard, client, worldContext);
		if (lines.isEmpty()) {
			return;
		}

		matrices.push();
		matrices.translate(guard.getX(), guard.getY() + guard.getHeight() + 0.75D, guard.getZ());
		matrices.multiply(client.gameRenderer.getCamera().getRotation());
		matrices.scale(-LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		int background = (int) (client.options.getTextBackgroundOpacity(0.25F) * 255.0F) << 24;
		float lineY = -(lines.size() - 1) * (textRenderer.fontHeight + 2);
		for (DebugLabelLine line : lines) {
			float lineX = -textRenderer.getWidth(line.text()) / 2.0F;
			textRenderer.draw(
				line.text(),
				lineX,
				lineY,
				line.color(),
				false,
				matrix,
				vertexConsumers,
				TextRenderer.TextLayerType.SEE_THROUGH,
				background,
				LightmapTextureManager.MAX_LIGHT_COORDINATE
			);
			lineY += textRenderer.fontHeight + 2;
		}
		matrices.pop();
	}

	private static List<DebugLabelLine> buildLabelLines(GuardEntity guard, MinecraftClient client, ClientTacticsDataStore.WorldContext worldContext) {
		List<DebugLabelLine> lines = new ArrayList<>();
		int hp = Math.max(0, MathHelper.floor(guard.getHealth()));
		int maxHp = Math.max(1, MathHelper.floor(guard.getMaxHealth()));
		lines.add(line("HP: " + hp + "/" + maxHp, 0xFF5555));

		int level = guard.getLevel();
		lines.add(line("Lvl: " + level, 0x55FF55));

		int xp = guard.getExperience();
		String xpLine = level >= 10 ? "XP: " + xp + "/MAX" : "XP: " + xp + "/" + (level * 120);
		lines.add(line(xpLine, 0x55FFFF));

		lines.add(line("Role: " + titleCase(guard.getRole().name()), 0xFFFF55));

		GuardBehavior behavior = guard.getBehavior();
		String behaviorValue = behavior == null ? "Nil" : titleCase(behavior.name());
		lines.add(line("Behavior: " + behaviorValue, 0xFF55FF));

		UUID ownerUuid = guard.getOwnerUuid();
		String ownerValue = ownerUuid == null ? "Nil" : resolveOwnerName(ownerUuid, client);
		lines.add(line("Owner: " + ownerValue, 0xFFFFFF));

		String groupValue = guard.getGroupIndex() < 0 ? "Nil" : guard.getGroupName();
		lines.add(line("Group: " + groupValue, 0xFFAA00));

		RegionColor regionColor = ClientTacticsDataStore.getInstance().getRegionColor(worldContext, guard.getBlockX() >> 4, guard.getBlockZ() >> 4);
		String zoneValue = regionColor == RegionColor.NONE ? "Nil" : regionColor.label();
		lines.add(line("Zone: " + zoneValue, 0x5555FF));
		return lines;
	}

	private static DebugLabelLine line(String text, int color) {
		if (text.endsWith("Nil")) {
			return new DebugLabelLine(text, 0xAAAAAA);
		}
		return new DebugLabelLine(text, color);
	}

	private static String resolveOwnerName(UUID ownerUuid, MinecraftClient client) {
		if (client.getNetworkHandler() == null) {
			return shortUuid(ownerUuid);
		}
		PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(ownerUuid);
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
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
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

	private static void drawFilledBlock(MatrixStack matrices, VertexConsumer consumer, BlockPos pos, float r, float g, float b, float a) {
		drawFilledBox(
			matrices,
			consumer,
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			pos.getX() + 1.0D,
			pos.getY() + 1.0D,
			pos.getZ() + 1.0D,
			r,
			g,
			b,
			a
		);
	}

	private static void drawFilledBox(
		MatrixStack matrices,
		VertexConsumer consumer,
		double minX,
		double minY,
		double minZ,
		double maxX,
		double maxY,
		double maxZ,
		float r,
		float g,
		float b,
		float a
	) {
		Matrix4f matrix = matrices.peek().getPositionMatrix();

		quad(consumer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, r, g, b, a);
		quad(consumer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
		quad(consumer, matrix, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);
		quad(consumer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a);
		quad(consumer, matrix, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
		quad(consumer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
	}

	private static void quad(
		VertexConsumer consumer,
		Matrix4f matrix,
		double x1,
		double y1,
		double z1,
		double x2,
		double y2,
		double z2,
		double x3,
		double y3,
		double z3,
		double x4,
		double y4,
		double z4,
		float r,
		float g,
		float b,
		float a
	) {
		quadVertex(consumer, matrix, x1, y1, z1, r, g, b, a);
		quadVertex(consumer, matrix, x2, y2, z2, r, g, b, a);
		quadVertex(consumer, matrix, x3, y3, z3, r, g, b, a);
		quadVertex(consumer, matrix, x4, y4, z4, r, g, b, a);
	}

	private static void quadVertex(VertexConsumer consumer, Matrix4f matrix, double x, double y, double z, float r, float g, float b, float a) {
		consumer.vertex(matrix, (float) x, (float) y, (float) z).color(r, g, b, a);
	}

	private static void lineVertex(VertexConsumer consumer, Matrix4f matrix, double x, double y, double z, float r, float g, float b, float a) {
		consumer.vertex(matrix, (float) x, (float) y, (float) z).color(r, g, b, a).normal(0.0F, 1.0F, 0.0F);
	}

	private static Vec3d closestPointOnBox(Box box, Vec3d point) {
		return new Vec3d(
			Math.max(box.minX, Math.min(point.x, box.maxX)),
			Math.max(box.minY, Math.min(point.y, box.maxY)),
			Math.max(box.minZ, Math.min(point.z, box.maxZ))
		);
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

	private record DebugLabelLine(String text, int color) {
	}
}
