package com.guardvillagers.client;

import com.guardvillagers.GuardVillagersMod;
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

import java.util.Optional;

public final class GuardDebugRenderer {
	private static final int CIRCLE_SEGMENTS = 48;
	private static final float FOLLOW_RANGE_R = 0.3f, FOLLOW_RANGE_G = 0.7f, FOLLOW_RANGE_B = 1.0f, FOLLOW_RANGE_A = 0.8f;
	private static final float HOME_ZONE_R = 0.2f, HOME_ZONE_G = 1.0f, HOME_ZONE_B = 0.2f, HOME_ZONE_A = 0.8f;
	private static final float HOME_CENTER_R = 1.0f, HOME_CENTER_G = 0.84f, HOME_CENTER_B = 0.0f, HOME_CENTER_A = 1.0f;
	private static final float TARGET_LINE_R = 1.0f, TARGET_LINE_G = 0.1f, TARGET_LINE_B = 0.1f, TARGET_LINE_A = 0.9f;

	private GuardDebugRenderer() {}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(GuardDebugRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) return;

		Vec3d cameraPos = context.camera().getPos();
		double maxDistSq = 256.0 * 256.0;

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);

		context.matrixStack().push();
		context.matrixStack().translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

		for (GuardEntity guard : client.world.getEntitiesByClass(
				GuardEntity.class,
				new Box(
					cameraPos.x - 256, client.world.getBottomY(), cameraPos.z - 256,
					cameraPos.x + 256, client.world.getTopYInclusive(), cameraPos.z + 256
				),
				GuardEntity::isDebugActive
		)) {
			if (guard.squaredDistanceTo(cameraPos) > maxDistSq) continue;

			double followRange = guard.getAttributeValue(EntityAttributes.FOLLOW_RANGE);
			renderCircle(context, guard.getX(), guard.getY() + 0.1, guard.getZ(),
					Math.max(2.0, followRange), FOLLOW_RANGE_R, FOLLOW_RANGE_G, FOLLOW_RANGE_B, FOLLOW_RANGE_A);

			Optional<BlockPos> home = guard.getSyncedHome();
			if (home.isPresent()) {
				BlockPos h = home.get();
				double hx = h.getX() + 0.5;
				double hy = h.getY() + 0.1;
				double hz = h.getZ() + 0.5;
				int radius = guard.getSyncedPatrolRadius();
				renderCircle(context, hx, hy, hz, Math.max(2.0, radius),
						HOME_ZONE_R, HOME_ZONE_G, HOME_ZONE_B, HOME_ZONE_A);
				renderCross(context, hx, h.getY() + 0.2, hz, 0.5,
						HOME_CENTER_R, HOME_CENTER_G, HOME_CENTER_B, HOME_CENTER_A);
			}

			LivingEntity target = guard.getTarget();
			if (target != null && target.isAlive()) {
				renderLine(context,
						guard.getX(), guard.getY() + 1.5, guard.getZ(),
						target.getX(), target.getY() + target.getHeight() * 0.5, target.getZ(),
						TARGET_LINE_R, TARGET_LINE_G, TARGET_LINE_B, TARGET_LINE_A);
			}
		}

		context.matrixStack().pop();

		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	private static void renderCircle(WorldRenderContext context, double cx, double cy, double cz,
									  double radius, float r, float g, float b, float a) {
		BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.POSITION_COLOR);
		for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
			double angle = (Math.PI * 2.0 * i) / CIRCLE_SEGMENTS;
			float x = (float) (cx + Math.cos(angle) * radius);
			float z = (float) (cz + Math.sin(angle) * radius);
			buffer.vertex(context.matrixStack().peek().getPositionMatrix(), x, (float) cy, z)
					.color(r, g, b, a);
		}
		BufferRenderer.drawWithGlobalProgram(buffer.end());
	}

	private static void renderCross(WorldRenderContext context, double cx, double cy, double cz,
									  double size, float r, float g, float b, float a) {
		BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
		float halfSize = (float) size;
		buffer.vertex(context.matrixStack().peek().getPositionMatrix(), (float) (cx - halfSize), (float) cy, (float) cz)
				.color(r, g, b, a);
		buffer.vertex(context.matrixStack().peek().getPositionMatrix(), (float) (cx + halfSize), (float) cy, (float) cz)
				.color(r, g, b, a);
		buffer.vertex(context.matrixStack().peek().getPositionMatrix(), (float) cx, (float) cy, (float) (cz - halfSize))
				.color(r, g, b, a);
		buffer.vertex(context.matrixStack().peek().getPositionMatrix(), (float) cx, (float) cy, (float) (cz + halfSize))
				.color(r, g, b, a);
		BufferRenderer.drawWithGlobalProgram(buffer.end());
	}

	private static void renderLine(WorldRenderContext context, double x1, double y1, double z1,
									double x2, double y2, double z2,
									float r, float g, float b, float a) {
		BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
		buffer.vertex(context.matrixStack().peek().getPositionMatrix(), (float) x1, (float) y1, (float) z1)
				.color(r, g, b, a);
		buffer.vertex(context.matrixStack().peek().getPositionMatrix(), (float) x2, (float) y2, (float) z2)
				.color(r, g, b, a);
		BufferRenderer.drawWithGlobalProgram(buffer.end());
	}
}
