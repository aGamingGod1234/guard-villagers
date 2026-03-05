package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.UUID;

public class GuardEntityRenderer extends MobEntityRenderer<GuardEntity, GuardEntityRenderer.GuardRenderState, GuardEntityModel> {
	private static final Identifier TEXTURE = Identifier.of("guardvillagers", "textures/entity/guard_villager.png");

	public GuardEntityRenderer(EntityRendererFactory.Context context) {
		super(context, new GuardEntityModel(context.getPart(GuardEntityModel.GUARD_LAYER)), 0.5F);
		this.addFeature(new ArmorFeatureRenderer<>(
			this,
			new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
			new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
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

		MinecraftClient client = MinecraftClient.getInstance();
		if (entity.isDebugActive()) {
			state.displayName = buildDebugLabel(entity);
		} else if (client.player != null && client.targetedEntity == entity && state.displayName == null) {
			state.displayName = Text.literal("Lv " + entity.getLevel() + " | " + entity.getRole().name() + " | " + entity.getBehavior().name());
		}
	}

	private static Text buildDebugLabel(GuardEntity entity) {
		StringBuilder sb = new StringBuilder();
		sb.append("\u00A7b[DBG] \u00A7f").append(entity.getName().getString());
		sb.append("\n\u00A77HP: \u00A7f").append(String.format("%.0f/%.0f", entity.getHealth(), entity.getMaxHealth()));
		sb.append("  \u00A77Lv: \u00A7f").append(entity.getLevel());
		sb.append("  \u00A77XP: \u00A7f").append(entity.getExperience());
		sb.append("\n\u00A77Role: \u00A7f").append(entity.getRole().name());
		sb.append("  \u00A77Behavior: \u00A7f").append(entity.getBehavior().name());

		LivingEntity target = entity.getTarget();
		if (target != null && target.isAlive()) {
			sb.append("\n\u00A7cTarget: \u00A7f").append(target.getName().getString());
		}

		String groupName = entity.getGroupName();
		if (groupName != null && !groupName.isBlank() && !groupName.equals("Role")) {
			sb.append("\n\u00A77Group: \u00A7f").append(groupName);
		}

		if (entity.hasOwner()) {
			UUID ownerUuid = entity.getOwnerUuid();
			MinecraftClient client = MinecraftClient.getInstance();
			String ownerName = null;
			if (client.world != null && ownerUuid != null) {
				var player = client.world.getPlayerByUuid(ownerUuid);
				if (player != null) {
					ownerName = player.getName().getString();
				}
			}
			sb.append("\n\u00A77Owner: \u00A7f").append(ownerName != null ? ownerName : (ownerUuid != null ? ownerUuid.toString().substring(0, 8) : "?"));
		}

		entity.getSyncedHome().ifPresent(home ->
			sb.append("\n\u00A77Home: \u00A7f").append(home.getX()).append(", ").append(home.getY()).append(", ").append(home.getZ())
				.append(" \u00A77R: \u00A7f").append(entity.getSyncedPatrolRadius())
		);

		return Text.literal(sb.toString());
	}

	public static class GuardRenderState extends BipedEntityRenderState {
	}
}
