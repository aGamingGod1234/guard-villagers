package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;

public class GuardEntityRenderer
		extends MobEntityRenderer<GuardEntity, GuardEntityRenderer.GuardRenderState, GuardEntityModel> {
	private final ItemModelManager itemModelManager;

	public GuardEntityRenderer(EntityRendererFactory.Context context) {
		super(context, new GuardEntityModel(context.getPart(GuardEntityModel.GUARD_LAYER)), 0.5F);
		this.itemModelManager = context.getItemModelManager();
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
			state.displayName = Text.literal(
					"Lv " + entity.getLevel() + " | " + entity.getRole().name() + " | " + entity.getBehavior().name());
		}
	}

	private static BipedEntityModel.ArmPose getArmPose(GuardEntity entity, Arm arm) {
		// Bow draw (ranged guards using bow in main hand)
		if (arm == Arm.RIGHT && entity.isUsingItem()
				&& entity.getActiveHand() == net.minecraft.util.Hand.MAIN_HAND
				&& entity.getMainHandStack().isOf(net.minecraft.item.Items.BOW)) {
			return BipedEntityModel.ArmPose.BOW_AND_ARROW;
		}
		// Shield blocking (off-hand)
		if (arm == Arm.LEFT && entity.isUsingItem()
				&& entity.getActiveHand() == net.minecraft.util.Hand.OFF_HAND) {
			return BipedEntityModel.ArmPose.BLOCK;
		}
		// Melee swing: vanilla BipedEntityModel reads handSwingProgress
		// automatically from swingHand(), no explicit pose needed.
		return BipedEntityModel.ArmPose.EMPTY;
	}

	public static class GuardRenderState extends BipedEntityRenderState {
		public String skinProfileId = "";
	}
}
