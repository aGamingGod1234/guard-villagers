package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class GuardDebugRenderer {
	private static final int CIRCLE_SEGMENTS = 48;
	private static final float FOLLOW_RANGE_R = 0.3f, FOLLOW_RANGE_G = 0.7f, FOLLOW_RANGE_B = 1.0f, FOLLOW_RANGE_A = 0.8f;
	private static final float HOME_ZONE_R = 0.2f, HOME_ZONE_G = 1.0f, HOME_ZONE_B = 0.2f, HOME_ZONE_A = 0.8f;
	private static final float HOME_CENTER_R = 1.0f, HOME_CENTER_G = 0.84f, HOME_CENTER_B = 0.0f, HOME_CENTER_A = 1.0f;
	private static final float TARGET_LINE_R = 1.0f, TARGET_LINE_G = 0.1f, TARGET_LINE_B = 0.1f, TARGET_LINE_A = 0.9f;

	// Pre-computed sin/cos table for circles to avoid trig per frame
	private static final float[] COS_TABLE = new float[CIRCLE_SEGMENTS + 1];
	private static final float[] SIN_TABLE = new float[CIRCLE_SEGMENTS + 1];

	static {
		for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
			double angle = (Math.PI * 2.0 * i) / CIRCLE_SEGMENTS;
			COS_TABLE[i] = (float) Math.cos(angle);
			SIN_TABLE[i] = (float) Math.sin(angle);
		}
	}

	// Reusable list for collecting guards — avoids per-frame allocation
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

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);

		context.matrixStack().push();
		context.matrixStack().translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		Matrix4f matrix = context.matrixStack().peek().getPositionMatrix();

		// Batch all circles into one LINE_STRIP draw call per circle
		// (LINE_STRIP can't batch multiple circles, so we use separate calls per circle
		// but batch all LINES geometry into one call)

		// First pass: render circles (each needs its own LINE_STRIP)
		for (GuardEntity guard : GUARD_SCRATCH) {
			if (guard.squaredDistanceTo(cameraPos) > maxDistSq) continue;

			double followRange = guard.getAttributeValue(EntityAttributes.FOLLOW_RANGE);
			renderCircle(matrix, guard.getX(), guard.getY() + 0.1, guard.getZ(),
					Math.max(2.0, followRange), FOLLOW_RANGE_R, FOLLOW_RANGE_G, FOLLOW_RANGE_B, FOLLOW_RANGE_A);

			Optional<BlockPos> home = guard.getSyncedHome();
			if (home.isPresent()) {
				BlockPos h = home.get();
				int radius = guard.getSyncedPatrolRadius();
				renderCircle(matrix, h.getX() + 0.5, h.getY() + 0.1, h.getZ() + 0.5,
						Math.max(2.0, radius), HOME_ZONE_R, HOME_ZONE_G, HOME_ZONE_B, HOME_ZONE_A);
			}
		}

		// Second pass: batch all lines (crosses + target lines) into one draw call
		BufferBuilder lineBuffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
		boolean hasLines = false;

		for (GuardEntity guard : GUARD_SCRATCH) {
			if (guard.squaredDistanceTo(cameraPos) > maxDistSq) continue;

			Optional<BlockPos> home = guard.getSyncedHome();
			if (home.isPresent()) {
				BlockPos h = home.get();
				float hx = h.getX() + 0.5f;
				float hy = h.getY() + 0.2f;
				float hz = h.getZ() + 0.5f;
				float halfSize = 0.5f;
				lineBuffer.vertex(matrix, hx - halfSize, hy, hz).color(HOME_CENTER_R, HOME_CENTER_G, HOME_CENTER_B, HOME_CENTER_A);
				lineBuffer.vertex(matrix, hx + halfSize, hy, hz).color(HOME_CENTER_R, HOME_CENTER_G, HOME_CENTER_B, HOME_CENTER_A);
				lineBuffer.vertex(matrix, hx, hy, hz - halfSize).color(HOME_CENTER_R, HOME_CENTER_G, HOME_CENTER_B, HOME_CENTER_A);
				lineBuffer.vertex(matrix, hx, hy, hz + halfSize).color(HOME_CENTER_R, HOME_CENTER_G, HOME_CENTER_B, HOME_CENTER_A);
				hasLines = true;
			}

			LivingEntity target = guard.getTarget();
			if (target != null && target.isAlive()) {
				lineBuffer.vertex(matrix, (float) guard.getX(), (float) (guard.getY() + 1.5), (float) guard.getZ())
						.color(TARGET_LINE_R, TARGET_LINE_G, TARGET_LINE_B, TARGET_LINE_A);
				lineBuffer.vertex(matrix, (float) target.getX(), (float) (target.getY() + target.getHeight() * 0.5), (float) target.getZ())
						.color(TARGET_LINE_R, TARGET_LINE_G, TARGET_LINE_B, TARGET_LINE_A);
				hasLines = true;
			}
		}

		if (hasLines) {
			BufferRenderer.drawWithGlobalProgram(lineBuffer.end());
		}

		context.matrixStack().pop();
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	private static void renderCircle(Matrix4f matrix, double cx, double cy, double cz,
									  double radius, float r, float g, float b, float a) {
		BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.POSITION_COLOR);
		float fcx = (float) cx;
		float fcy = (float) cy;
		float fcz = (float) cz;
		float fRadius = (float) radius;
		for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
			buffer.vertex(matrix, fcx + COS_TABLE[i] * fRadius, fcy, fcz + SIN_TABLE[i] * fRadius)
					.color(r, g, b, a);
		}
		BufferRenderer.drawWithGlobalProgram(buffer.end());
	}
}
