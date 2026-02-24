package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.EquipmentModelData;
import net.minecraft.client.render.entity.model.ZombieVillagerEntityModel;
import net.minecraft.client.render.entity.state.ZombieVillagerRenderState;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class GuardEntityRenderer extends BipedEntityRenderer<GuardEntity, ZombieVillagerRenderState, ZombieVillagerEntityModel<ZombieVillagerRenderState>> {
	private static final Identifier TEXTURE = Identifier.of("guardvillagers", "textures/entity/guard_villager.png");

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
	public Identifier getTexture(ZombieVillagerRenderState state) {
		return TEXTURE;
	}

	@Override
	public ZombieVillagerRenderState createRenderState() {
		return new ZombieVillagerRenderState();
	}

	@Override
	public void updateRenderState(GuardEntity entity, ZombieVillagerRenderState state, float tickDelta) {
		super.updateRenderState(entity, state, tickDelta);

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null && client.targetedEntity == entity) {
			state.displayName = Text.literal(
				"Lv " + entity.getLevel()
					+ " | " + entity.getRole().name()
					+ " | " + entity.getBehavior().name()
					+ " | HP " + Math.round(entity.getHealth()) + "/" + Math.round(entity.getMaxHealth())
			);
		}
	}

	@Override
	protected boolean hasLabel(GuardEntity entity, double distance) {
		MinecraftClient client = MinecraftClient.getInstance();
		return super.hasLabel(entity, distance) || (client.player != null && client.targetedEntity == entity);
	}
}
