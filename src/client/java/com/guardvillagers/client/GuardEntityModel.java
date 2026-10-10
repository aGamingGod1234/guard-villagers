package com.guardvillagers.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerLikeModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;

public final class GuardEntityModel extends HumanoidModel<GuardEntityRenderer.GuardRenderState>
		implements VillagerLikeModel<GuardEntityRenderer.GuardRenderState> {
	public static final ModelLayerLocation GUARD_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath("guardvillagers", "guard"),
			"main");
	public static final ModelLayerLocation GUARD_BABY_LAYER = new ModelLayerLocation(
			Identifier.fromNamespaceAndPath("guardvillagers", "guard"),
			"baby");
	public static final ModelLayerLocation GUARD_CLOTHING_LAYER = new ModelLayerLocation(
			Identifier.fromNamespaceAndPath("guardvillagers", "guard"),
			"clothing");
	public static final ModelLayerLocation GUARD_CLOTHING_BABY_LAYER = new ModelLayerLocation(
			Identifier.fromNamespaceAndPath("guardvillagers", "guard"),
			"clothing_baby");

	public GuardEntityModel(ModelPart root) {
		super(root);
	}

	public static LayerDefinition getTexturedModelData() {
		return createTexturedModelData(false);
	}

	public static LayerDefinition getClothingTexturedModelData() {
		return createTexturedModelData(true);
	}

	private static LayerDefinition createTexturedModelData(boolean includeJacket) {
		MeshDefinition modelData = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
		PartDefinition root = modelData.getRoot();
		PartDefinition head = root.addOrReplaceChild(
				"head",
				CubeListBuilder.create()
						.texOffs(0, 0)
						.addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F)
						.texOffs(24, 0)
						.addBox(-1.0F, -3.0F, -6.0F, 2.0F, 4.0F, 2.0F),
				PartPose.ZERO);
		PartDefinition hat = head.addOrReplaceChild(
				"hat",
				CubeListBuilder.create().texOffs(32, 0).addBox(
						-4.0F,
						-10.0F,
						-4.0F,
						8.0F,
						10.0F,
						8.0F,
						new CubeDeformation(0.5F)),
				PartPose.ZERO);
		hat.addOrReplaceChild(
				"hat_rim",
				CubeListBuilder.create().texOffs(30, 47).addBox(-8.0F, -8.0F, -6.0F, 16.0F, 16.0F, 1.0F),
				PartPose.rotation(-1.5707964F, 0.0F, 0.0F));

		PartDefinition body = root.addOrReplaceChild(
				"body",
				CubeListBuilder.create().texOffs(16, 20).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F),
				PartPose.ZERO);
		if (includeJacket) {
			body.addOrReplaceChild(
					"jacket",
					CubeListBuilder.create().texOffs(0, 38).addBox(
							-4.0F,
							0.0F,
							-3.0F,
							8.0F,
							20.0F,
							6.0F,
							new CubeDeformation(0.5F)),
					PartPose.ZERO);
		}
		root.addOrReplaceChild(
				"right_arm",
				CubeListBuilder.create()
						.texOffs(44, 22)
						.addBox(-3.0F, -2.0F, -2.0F, 4.0F, 8.0F, 4.0F)
						.texOffs(44, 26)
						.addBox(-3.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F),
				PartPose.offset(-5.0F, 2.0F, 0.0F));
		root.addOrReplaceChild(
				"left_arm",
				CubeListBuilder.create()
						.texOffs(44, 22)
						.mirror()
						.addBox(-1.0F, -2.0F, -2.0F, 4.0F, 8.0F, 4.0F)
						.texOffs(44, 26)
						.mirror()
						.addBox(-1.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F),
				PartPose.offset(5.0F, 2.0F, 0.0F));
		root.addOrReplaceChild(
				"right_leg",
				CubeListBuilder.create().texOffs(0, 22).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
				PartPose.offset(-2.0F, 12.0F, 0.0F));
		root.addOrReplaceChild(
				"left_leg",
				CubeListBuilder.create().texOffs(0, 22).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
				PartPose.offset(2.0F, 12.0F, 0.0F));
		return LayerDefinition.create(modelData, 64, 64);
	}

	@Override
	public void translateToArms(GuardEntityRenderer.GuardRenderState state, PoseStack poseStack) {
		this.translateToHand(state, HumanoidArm.RIGHT, poseStack);
	}
}
