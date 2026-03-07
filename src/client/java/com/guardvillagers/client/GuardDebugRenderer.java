package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public final class GuardDebugRenderer {
	private static final int CACHE_REFRESH_TICKS = 10;
	private static final float LINE_HALF_WIDTH = 0.015F;
	private static final float PATH_GREEN_R = 0.28F;
	private static final float PATH_GREEN_G = 0.70F;
	private static final float PATH_GREEN_B = 0.42F;
	private static final float PATH_CARPET_ALPHA = 0.40F;
	private static final float DESTINATION_ALPHA = 0.40F;
	private static final float LOOK_LINE_R = 1.0F;
	private static final float LOOK_LINE_G = 0.94F;
	private static final float LOOK_LINE_B = 0.30F;
	private static final float LOOK_LINE_A = 1.0F;
	private static final double LOOK_TRACE_DISTANCE = 20.0D;

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

		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

		for (GuardEntity guard : CACHED_GUARDS) {
			if (!guard.isAlive() || guard.squaredDistanceTo(client.player) > maxDistanceSq) {
				continue;
			}
			ClientGuardDebugData.GuardDebugSnapshot snapshot = ClientGuardDebugData.get(guard.getId());
			renderPathHighlights(matrices, vertexConsumers, guard, snapshot);
			renderLookIndicator(matrices, vertexConsumers, guard, snapshot, client, cameraPos);
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

	private static void renderPathHighlights(
		MatrixStack matrices,
		VertexConsumerProvider vertexConsumers,
		GuardEntity guard,
		ClientGuardDebugData.GuardDebugSnapshot snapshot
	) {
		if (snapshot == null || snapshot.pathNodes().isEmpty()) {
			return;
		}

		List<BlockPos> nodes = snapshot.pathNodes();
		int currentIndex = snapshot.currentPathIndex();
		if (currentIndex < 0 || currentIndex >= nodes.size()) {
			return;
		}

		VertexConsumer filled = vertexConsumers.getBuffer(RenderLayers.debugFilledBox());
		for (int i = currentIndex; i < nodes.size(); i++) {
			BlockPos node = nodes.get(i);
			drawTopFace(
				matrices,
				filled,
				node,
				PATH_GREEN_R,
				PATH_GREEN_G,
				PATH_GREEN_B,
				PATH_CARPET_ALPHA
			);
		}

		BlockPos destination = nodes.get(nodes.size() - 1);
		if (!destination.equals(guard.getBlockPos())) {
			drawFilledBox(
				matrices,
				filled,
				destination.getX(),
				destination.getY(),
				destination.getZ(),
				destination.getX() + 1.0D,
				destination.getY() + 1.0D,
				destination.getZ() + 1.0D,
				PATH_GREEN_R,
				PATH_GREEN_G,
				PATH_GREEN_B,
				DESTINATION_ALPHA
			);
		}
	}

	private static void renderLookIndicator(
		MatrixStack matrices,
		VertexConsumerProvider vertexConsumers,
		GuardEntity guard,
		ClientGuardDebugData.GuardDebugSnapshot snapshot,
		MinecraftClient client,
		Vec3d cameraPos
	) {
		Vec3d origin = guard.getEyePos();
		Vec3d destination = resolveLookDestination(guard, snapshot, client, origin);
		if (destination == null) {
			return;
		}

		VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayers.debugFilledBox());
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		renderLineSegment(
			consumer,
			matrix,
			cameraPos,
			origin.x,
			origin.y,
			origin.z,
			destination.x,
			destination.y,
			destination.z,
			LOOK_LINE_R,
			LOOK_LINE_G,
			LOOK_LINE_B,
			LOOK_LINE_A
		);
	}

	private static Vec3d resolveLookDestination(
		GuardEntity guard,
		ClientGuardDebugData.GuardDebugSnapshot snapshot,
		MinecraftClient client,
		Vec3d origin
	) {
		LivingEntity target = resolveTarget(guard, snapshot, client);
		if (target != null && target.isAlive()) {
			return closestPointOnBox(target.getBoundingBox(), origin);
		}

		Vec3d fallback = origin.add(guard.getRotationVec(1.0F).multiply(LOOK_TRACE_DISTANCE));
		HitResult hitResult = guard.getEntityWorld().raycast(new RaycastContext(
			origin,
			fallback,
			RaycastContext.ShapeType.COLLIDER,
			RaycastContext.FluidHandling.NONE,
			guard
		));
		if (hitResult.getType() == HitResult.Type.MISS) {
			return fallback;
		}
		return hitResult.getPos();
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

	private static void drawTopFace(
		MatrixStack matrices,
		VertexConsumer consumer,
		BlockPos pos,
		float r,
		float g,
		float b,
		float a
	) {
		double minX = pos.getX();
		double minY = pos.getY() + 0.01D;
		double minZ = pos.getZ();
		double maxX = pos.getX() + 1.0D;
		double maxZ = pos.getZ() + 1.0D;
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		quad(
			consumer,
			matrix,
			minX,
			minY,
			minZ,
			maxX,
			minY,
			minZ,
			maxX,
			minY,
			maxZ,
			minX,
			minY,
			maxZ,
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

	private static void renderLineSegment(
		VertexConsumer consumer,
		Matrix4f matrix,
		Vec3d cameraPos,
		double x1,
		double y1,
		double z1,
		double x2,
		double y2,
		double z2,
		float r,
		float g,
		float b,
		float a
	) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double dz = z2 - z1;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1.0E-6D) {
			return;
		}

		double midX = (x1 + x2) * 0.5D;
		double midY = (y1 + y2) * 0.5D;
		double midZ = (z1 + z2) * 0.5D;
		double camDx = midX - cameraPos.x;
		double camDy = midY - cameraPos.y;
		double camDz = midZ - cameraPos.z;

		double crossX = dy * camDz - dz * camDy;
		double crossY = dz * camDx - dx * camDz;
		double crossZ = dx * camDy - dy * camDx;
		double crossLen = Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
		if (crossLen < 1.0E-6D) {
			crossX = -dz;
			crossY = 0.0D;
			crossZ = dx;
			crossLen = Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
			if (crossLen < 1.0E-6D) {
				return;
			}
		}

		double scale = LINE_HALF_WIDTH / crossLen;
		double offX = crossX * scale;
		double offY = crossY * scale;
		double offZ = crossZ * scale;

		float ax = (float) (x1 + offX);
		float ay = (float) (y1 + offY);
		float az = (float) (z1 + offZ);

		float bx = (float) (x1 - offX);
		float by = (float) (y1 - offY);
		float bz = (float) (z1 - offZ);

		float cx = (float) (x2 - offX);
		float cy = (float) (y2 - offY);
		float cz = (float) (z2 - offZ);

		float ex = (float) (x2 + offX);
		float ey = (float) (y2 + offY);
		float ez = (float) (z2 + offZ);

		consumer.vertex(matrix, ax, ay, az).color(r, g, b, a);
		consumer.vertex(matrix, bx, by, bz).color(r, g, b, a);
		consumer.vertex(matrix, cx, cy, cz).color(r, g, b, a);
		consumer.vertex(matrix, ex, ey, ez).color(r, g, b, a);
	}

	private static Vec3d closestPointOnBox(Box box, Vec3d point) {
		return new Vec3d(
			Math.max(box.minX, Math.min(point.x, box.maxX)),
			Math.max(box.minY, Math.min(point.y, box.maxY)),
			Math.max(box.minZ, Math.min(point.z, box.maxZ))
		);
	}
}
