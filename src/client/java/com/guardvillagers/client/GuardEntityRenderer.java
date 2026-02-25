package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.EquipmentModelData;
import net.minecraft.client.render.entity.model.ZombieVillagerEntityModel;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.render.entity.state.ZombieVillagerRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class GuardEntityRenderer extends BipedEntityRenderer<GuardEntity, GuardEntityRenderer.GuardRenderState, ZombieVillagerEntityModel<GuardEntityRenderer.GuardRenderState>> {
	private static final Identifier TEXTURE = Identifier.of("minecraft", "textures/entity/villager/villager.png");
	private static final String DEBUG_PREFIX = "[DBG] ";
	private static final double DEBUG_X_OFFSET = 0.0D;
	private static final double DEBUG_START_Y_OFFSET = 0.30D;
	private static final double DEBUG_LINE_STEP = 0.24D;

	public GuardEntityRenderer(EntityRendererFactory.Context context) {
		super(
			context,
			new ZombieVillagerEntityModel<>(context.getPart(EntityModelLayers.ZOMBIE_VILLAGER)),
			new ZombieVillagerEntityModel<>(context.getPart(EntityModelLayers.ZOMBIE_VILLAGER_BABY)),
			0.5F,
			VillagerEntityRenderer.HEAD_TRANSFORMATION
		);

		this.addFeature(new ArmorFeatureRenderer<>(
			this,
			EquipmentModelData.mapToEntityModel(
				EntityModelLayers.ZOMBIE_VILLAGER_EQUIPMENT,
				context.getEntityModels(),
				ZombieVillagerEntityModel::new
			),
			EquipmentModelData.mapToEntityModel(
				EntityModelLayers.ZOMBIE_VILLAGER_BABY_EQUIPMENT,
				context.getEntityModels(),
				ZombieVillagerEntityModel::new
			),
			context.getEquipmentRenderer()
		));
		this.addFeature(new HeldItemFeatureRenderer<>(this));
	}

	@Override
	public Identifier getTexture(GuardRenderState state) {
		return TEXTURE;
	}

	@Override
	public GuardRenderState createRenderState() {
		return new GuardRenderState();
	}

	@Override
	public void updateRenderState(GuardEntity entity, GuardRenderState state, float tickDelta) {
		super.updateRenderState(entity, state, tickDelta);
		state.debugLines = List.of();
		state.renderDebugOverlay = false;

		MinecraftClient client = MinecraftClient.getInstance();
		Text customName = entity.getCustomName();
		boolean hasDebugName = customName != null && customName.getString().startsWith(DEBUG_PREFIX);
		if (hasDebugName) {
			state.renderDebugOverlay = true;
			state.debugLines = buildDebugLines(entity);
			state.displayName = null;
			return;
		}

		if (client.player != null && client.targetedEntity == entity && state.displayName == null) {
			state.displayName = Text.literal(
				"Lv " + entity.getLevel()
					+ " | " + entity.getRole().name()
					+ " | " + entity.getBehavior().name()
					+ " | HP " + Math.round(entity.getHealth()) + "/" + Math.round(entity.getMaxHealth())
			);
		}
	}

	@Override
	protected void renderLabelIfPresent(GuardRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
		if (!state.renderDebugOverlay || state.nameLabelPos == null) {
			super.renderLabelIfPresent(state, matrices, queue, cameraState);
			return;
		}

		Text originalDisplayName = state.displayName;
		Vec3d originalLabelPos = state.nameLabelPos;

		renderDebugStack(state, matrices, queue, cameraState, originalLabelPos, state.debugLines);

		state.displayName = originalDisplayName;
		state.nameLabelPos = originalLabelPos;
	}

	private void renderDebugStack(GuardRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState cameraState, Vec3d labelPos, List<Text> lines) {
		for (int i = 0; i < lines.size(); i++) {
			state.displayName = lines.get(i);
			state.nameLabelPos = labelPos.add(DEBUG_X_OFFSET, DEBUG_START_Y_OFFSET + (i * DEBUG_LINE_STEP), 0.0D);
			super.renderLabelIfPresent(state, matrices, queue, cameraState);
		}
	}

	private List<Text> buildDebugLines(GuardEntity entity) {
		List<Text> lines = new ArrayList<>();
		lines.add(Text.literal("[DBG] " + entity.getUuid().toString().substring(0, 8)).formatted(Formatting.AQUA, Formatting.BOLD));
		lines.add(Text.literal("Lv " + entity.getLevel() + " XP " + entity.getExperience()).formatted(Formatting.GREEN));

		float maxHealth = Math.max(1.0F, entity.getMaxHealth());
		float healthRatio = entity.getHealth() / maxHealth;
		Formatting hpColor = healthRatio >= 0.6F ? Formatting.GREEN : (healthRatio >= 0.3F ? Formatting.YELLOW : Formatting.RED);
		lines.add(Text.literal("HP " + Math.round(entity.getHealth()) + "/" + Math.round(maxHealth)).formatted(hpColor));
		lines.add(Text.literal("Cooldown " + entity.getCombatCooldown() + " | Staying " + (entity.isStaying() ? "Yes" : "No")).formatted(Formatting.WHITE));

		lines.add(Text.literal("Role: " + entity.getRole().name()).formatted(Formatting.GOLD));
		lines.add(Text.literal("Behavior: " + entity.getBehavior().name()).formatted(Formatting.LIGHT_PURPLE));
		lines.add(Text.literal("Formation: " + entity.getFormationType().name()).formatted(Formatting.AQUA));
		lines.add(Text.literal("Row " + (entity.getHierarchyRow() + 1) + " Col " + (entity.getHierarchyColumn() + 1)).formatted(Formatting.BLUE));
		lines.add(Text.literal(entity.getHierarchyRole()).formatted(Formatting.YELLOW));

		double followRange = entity.getAttributeValue(EntityAttributes.FOLLOW_RANGE);
		lines.add(Text.literal("Follow " + String.format("%.1f", followRange)).formatted(Formatting.AQUA));
		lines.add(Text.literal("Patrol " + entity.getPatrolRadius()).formatted(Formatting.YELLOW));

		String home = entity.getHome().map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ()).orElse("none");
		lines.add(Text.literal("Home: " + home).formatted(home.equals("none") ? Formatting.DARK_GRAY : Formatting.GREEN));

		LivingEntity target = entity.getTarget();
		if (target != null && target.isAlive()) {
			lines.add(Text.literal("Target: " + target.getName().getString()).formatted(Formatting.RED));
		} else if (entity.getPriorityTargetUuid() != null) {
			lines.add(Text.literal("Target: tracked").formatted(Formatting.RED));
		} else {
			lines.add(Text.literal("Target: none").formatted(Formatting.GRAY));
		}
		return lines;
	}

	@Override
	protected boolean hasLabel(GuardEntity entity, double distance) {
		MinecraftClient client = MinecraftClient.getInstance();
		Text customName = entity.getCustomName();
		boolean hasDebugName = customName != null && customName.getString().startsWith(DEBUG_PREFIX);
		return hasDebugName || super.hasLabel(entity, distance) || (client.player != null && client.targetedEntity == entity);
	}

	public static class GuardRenderState extends ZombieVillagerRenderState {
		private boolean renderDebugOverlay;
		private List<Text> debugLines = List.of();
	}
}
