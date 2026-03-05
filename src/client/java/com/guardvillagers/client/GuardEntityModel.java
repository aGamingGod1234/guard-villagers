package com.guardvillagers.client;

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
	public static final EntityModelLayer GUARD_LAYER = new EntityModelLayer(Identifier.of("guardvillagers", "guard"), "main");

	public GuardEntityModel(ModelPart root) {
		super(root);
	}

	public static TexturedModelData getTexturedModelData() {
		ModelData modelData = new ModelData();
		ModelPartData root = modelData.getRoot();

		// Villager-style head: taller (10px) with nose and helmet overlay
		ModelPartData head = root.addChild("head", ModelPartBuilder.create().uv(0, 0).cuboid(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F), ModelTransform.NONE);
		head.addChild("helmet", ModelPartBuilder.create().uv(32, 0).cuboid(-4.5F, -10.5F, -4.5F, 9.0F, 10.5F, 9.0F), ModelTransform.NONE);
		head.addChild("nose", ModelPartBuilder.create().uv(24, 0).cuboid(-1.0F, -4.0F, -6.0F, 2.0F, 4.0F, 2.0F), ModelTransform.NONE);

		// Hat layer (required by BipedEntityModel, empty for guard)
		root.addChild("hat", ModelPartBuilder.create(), ModelTransform.NONE);

		root.addChild("body", ModelPartBuilder.create().uv(0, 18).cuboid(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F), ModelTransform.NONE);
		root.addChild("right_arm", ModelPartBuilder.create().uv(24, 18).cuboid(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), ModelTransform.of(-5.0F, 2.0F, 0.0F, 0.0F, 0.0F, 0.0F));
		root.addChild("left_arm", ModelPartBuilder.create().uv(24, 34).cuboid(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), ModelTransform.of(5.0F, 2.0F, 0.0F, 0.0F, 0.0F, 0.0F));
		root.addChild("right_leg", ModelPartBuilder.create().uv(0, 34).cuboid(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), ModelTransform.of(-2.0F, 12.0F, 0.0F, 0.0F, 0.0F, 0.0F));
		root.addChild("left_leg", ModelPartBuilder.create().uv(16, 34).cuboid(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), ModelTransform.of(2.0F, 12.0F, 0.0F, 0.0F, 0.0F, 0.0F));

		return TexturedModelData.of(modelData, 64, 64);
	}
}
