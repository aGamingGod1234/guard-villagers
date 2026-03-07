package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.EquipmentModelData;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;

public class GuardEntityRenderer extends MobEntityRenderer<GuardEntity, GuardEntityRenderer.GuardRenderState, GuardEntityModel> {
	private final ItemModelManager itemModelManager;

	public GuardEntityRenderer(EntityRendererFactory.Context context) {
		super(context, new GuardEntityModel(context.getPart(GuardEntityModel.GUARD_LAYER)), 0.5F);
		this.itemModelManager = context.getItemModelManager();
		EquipmentModelData<GuardEntityModel> armorModels = EquipmentModelData.mapToEntityModel(
			EntityModelLayers.PLAYER_EQUIPMENT,
			context.getEntityModels(),
			GuardEntityModel::new
		);
		this.addFeature(new ArmorFeatureRenderer<>(
			this,
			armorModels,
			context.getEquipmentRenderer()
		));
		this.addFeature(new HeldItemFeatureRenderer<>(this));
	}

	@Override
	public Identifier getTexture(GuardRenderState state) {
		return GuardSkinResolver.resolveTexture(state.skinProfileId);
	}

	@Override
	public GuardRenderState createRenderState() {
		return new GuardRenderState();
	}

	@Override
	public void updateRenderState(GuardEntity entity, GuardRenderState state, float tickDelta) {
		super.updateRenderState(entity, state, tickDelta);
		BipedEntityRenderer.updateBipedRenderState(entity, state, tickDelta, this.itemModelManager);
		state.leftArmPose = getArmPose(entity, Arm.LEFT);
		state.rightArmPose = getArmPose(entity, Arm.RIGHT);
		state.skinProfileId = entity.getSkinProfileId();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null && client.targetedEntity == entity && state.displayName == null) {
			state.displayName = Text.literal("Lv " + entity.getLevel() + " | " + entity.getRole().name() + " | " + entity.getBehavior().name());
		}
	}

	private static BipedEntityModel.ArmPose getArmPose(GuardEntity entity, Arm arm) {
		if (entity.getMainArm() == arm && entity.isAttacking() && entity.getMainHandStack().isOf(Items.BOW)) {
			return BipedEntityModel.ArmPose.BOW_AND_ARROW;
		}
		return BipedEntityModel.ArmPose.EMPTY;
	}

	public static class GuardRenderState extends BipedEntityRenderState {
		public String skinProfileId = "";
	}
}
