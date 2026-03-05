package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Debug overlay for guard patrol ranges, home zones, and target lines.
 * Renders follow-range circles (blue), home-zone circles (green),
 * home center cross markers (gold), and target lines (red) in 3D world space.
 */
public final class GuardDebugRenderer {
	private static final int CIRCLE_SEGMENTS = 48;

	// Pre-computed sin/cos table for circles
	private static final float[] COS_TABLE = new float[CIRCLE_SEGMENTS + 1];
	private static final float[] SIN_TABLE = new float[CIRCLE_SEGMENTS + 1];

	static {
		for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
			double angle = (Math.PI * 2.0 * i) / CIRCLE_SEGMENTS;
			COS_TABLE[i] = (float) Math.cos(angle);
			SIN_TABLE[i] = (float) Math.sin(angle);
		}
	}

	private static final List<GuardEntity> GUARD_SCRATCH = new ArrayList<>();

	private GuardDebugRenderer() {}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(GuardDebugRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) return;

		Vec3d cameraPos = context.camera().getPos();
		double maxDistSq = 256.0 * 256.0;

		GUARD_SCRATCH.clear();
		GUARD_SCRATCH.addAll(client.world.getEntitiesByClass(
			GuardEntity.class,
			new Box(
				cameraPos.x - 256, client.world.getBottomY(), cameraPos.z - 256,
				cameraPos.x + 256, client.world.getTopYInclusive(), cameraPos.z + 256
			),
			GuardEntity::isDebugActive
		));

		if (GUARD_SCRATCH.isEmpty()) return;

		VertexConsumerProvider consumers = context.consumers();
		if (consumers == null) return;

		VertexConsumer lineConsumer = consumers.getBuffer(RenderLayer.getLines());

		context.matrixStack().push();
		context.matrixStack().translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		Matrix4f matrix = context.matrixStack().peek().getPositionMatrix();

		for (GuardEntity guard : GUARD_SCRATCH) {
			if (guard.squaredDistanceTo(cameraPos) > maxDistSq) continue;

			// Follow range circle (blue)
			double followRange = guard.getAttributeValue(EntityAttributes.FOLLOW_RANGE);
			renderCircle(lineConsumer, matrix, guard.getX(), guard.getY() + 0.1, guard.getZ(),
					Math.max(2.0, followRange), 0.3f, 0.7f, 1.0f, 0.8f);

			// Home zone circle (green) and center cross (gold)
			Optional<BlockPos> home = guard.getSyncedHome();
			if (home.isPresent()) {
				BlockPos h = home.get();
				int radius = guard.getSyncedPatrolRadius();
				renderCircle(lineConsumer, matrix, h.getX() + 0.5, h.getY() + 0.1, h.getZ() + 0.5,
						Math.max(2.0, radius), 0.2f, 1.0f, 0.2f, 0.8f);

				// Home center cross marker
				float hx = h.getX() + 0.5f;
				float hy = h.getY() + 0.2f;
				float hz = h.getZ() + 0.5f;
				float halfSize = 0.5f;
				renderLine(lineConsumer, matrix, hx - halfSize, hy, hz, hx + halfSize, hy, hz,
						1.0f, 0.84f, 0.0f, 1.0f);
				renderLine(lineConsumer, matrix, hx, hy, hz - halfSize, hx, hy, hz + halfSize,
						1.0f, 0.84f, 0.0f, 1.0f);
			}

			// Target line (red)
			LivingEntity target = guard.getTarget();
			if (target != null && target.isAlive()) {
				renderLine(lineConsumer, matrix,
						(float) guard.getX(), (float) (guard.getY() + 1.5), (float) guard.getZ(),
						(float) target.getX(), (float) (target.getY() + target.getHeight() * 0.5), (float) target.getZ(),
						1.0f, 0.1f, 0.1f, 0.9f);
			}
		}

		context.matrixStack().pop();
	}

	private static void renderCircle(VertexConsumer consumer, Matrix4f matrix,
									  double cx, double cy, double cz,
									  double radius, float r, float g, float b, float a) {
		float fcx = (float) cx;
		float fcy = (float) cy;
		float fcz = (float) cz;
		float fRadius = (float) radius;

		for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
			float x1 = fcx + COS_TABLE[i] * fRadius;
			float z1 = fcz + SIN_TABLE[i] * fRadius;
			float x2 = fcx + COS_TABLE[i + 1] * fRadius;
			float z2 = fcz + SIN_TABLE[i + 1] * fRadius;

			// Compute normal for the line segment (pointing up for horizontal lines)
			float dx = x2 - x1;
			float dz = z2 - z1;
			float length = (float) Math.sqrt(dx * dx + dz * dz);
			float nx = length > 0 ? dx / length : 1.0f;
			float nz = length > 0 ? dz / length : 0.0f;

			consumer.vertex(matrix, x1, fcy, z1).color(r, g, b, a).normal(nx, 0.0f, nz);
			consumer.vertex(matrix, x2, fcy, z2).color(r, g, b, a).normal(nx, 0.0f, nz);
		}
	}

	private static void renderLine(VertexConsumer consumer, Matrix4f matrix,
									float x1, float y1, float z1,
									float x2, float y2, float z2,
									float r, float g, float b, float a) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float dz = z2 - z1;
		float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float nx = length > 0 ? dx / length : 1.0f;
		float ny = length > 0 ? dy / length : 0.0f;
		float nz = length > 0 ? dz / length : 0.0f;

		consumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(nx, ny, nz);
		consumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(nx, ny, nz);
	}
}
