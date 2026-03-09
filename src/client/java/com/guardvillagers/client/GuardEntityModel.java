package com.guardvillagers.client;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

public final class GuardEntityModel extends BipedEntityModel<GuardEntityRenderer.GuardRenderState> {
	public static final EntityModelLayer GUARD_LAYER = new EntityModelLayer(Identifier.of("guardvillagers", "guard"),
			"main");

	public GuardEntityModel(ModelPart root) {
		super(root);
	}

	public static TexturedModelData getTexturedModelData() {
		return createModelData(Dilation.NONE, true);
	}

	@Override
	public void setAngles(GuardEntityRenderer.GuardRenderState state) {
		super.setAngles(state);
		// BipedEntityModel handles the side-arm idle pose plus bow/swing animations.
	}

	private static TexturedModelData createModelData(Dilation dilation, boolean includeNose) {
		ModelData modelData = new ModelData();
		ModelPartData root = modelData.getRoot();

		ModelPartData head = root.addChild(
				"head",
				ModelPartBuilder.create().uv(0, 0).cuboid(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, dilation),
				ModelTransform.NONE);
		head.addChild(
				"hat",
				ModelPartBuilder.create().uv(32, 0).cuboid(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F,
						includeNose ? new Dilation(0.51F) : dilation.add(0.5F)),
				ModelTransform.NONE);
		if (includeNose) {
			head.addChild(
					"nose",
					ModelPartBuilder.create().uv(24, 0).cuboid(-1.0F, -1.0F, -6.0F, 2.0F, 4.0F, 2.0F),
					ModelTransform.origin(0.0F, -2.0F, 0.0F));
		}

		root.addChild(
				"body",
				ModelPartBuilder.create().uv(16, 20).cuboid(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F, dilation),
				ModelTransform.NONE);
		root.addChild(
				"right_arm",
				ModelPartBuilder.create().uv(44, 22).cuboid(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, dilation),
				ModelTransform.origin(-5.0F, 2.0F, 0.0F));
		root.addChild(
				"left_arm",
				ModelPartBuilder.create().uv(44, 22).mirrored().cuboid(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F,
						dilation),
				ModelTransform.origin(5.0F, 2.0F, 0.0F));
		root.addChild(
				"right_leg",
				ModelPartBuilder.create().uv(0, 22).cuboid(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, dilation),
				ModelTransform.origin(-2.0F, 12.0F, 0.0F));
		root.addChild(
				"left_leg",
				ModelPartBuilder.create().uv(0, 22).mirrored().cuboid(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F,
						dilation),
				ModelTransform.origin(2.0F, 12.0F, 0.0F));
		return TexturedModelData.of(modelData, 64, 64);
	}
}
