package com.guardvillagers.client;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelPart;
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
		return TexturedModelData.of(BipedEntityModel.getModelData(Dilation.NONE, 0.0F), 64, 64);
	}
}
