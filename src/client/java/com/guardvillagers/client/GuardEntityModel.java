package com.guardvillagers.client;

import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.ModelWithArms;
import net.minecraft.client.render.entity.model.ModelWithHead;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.client.render.entity.model.EntityModelLayer;

public final class GuardEntityModel extends EntityModel<GuardEntityRenderer.GuardRenderState> implements ModelWithArms, ModelWithHead {
	public static final EntityModelLayer GUARD_LAYER = new EntityModelLayer(Identifier.of("guardvillagers", "guard"), "main");

	private final ModelPart root;
	private final ModelPart head;
	private final ModelPart helmet;
	private final ModelPart nose;
	private final ModelPart body;
	private final ModelPart rightArm;
	private final ModelPart leftArm;
	private final ModelPart rightLeg;
	private final ModelPart leftLeg;

	public GuardEntityModel(ModelPart root) {
		super(root);
		this.root = root;
		this.head = root.getChild("head");
		this.helmet = this.head.getChild("helmet");
		this.nose = this.head.getChild("nose");
		this.body = root.getChild("body");
		this.rightArm = root.getChild("right_arm");
		this.leftArm = root.getChild("left_arm");
		this.rightLeg = root.getChild("right_leg");
		this.leftLeg = root.getChild("left_leg");
	}

	public static TexturedModelData getTexturedModelData() {
		ModelData modelData = new ModelData();
		ModelPartData root = modelData.getRoot();

		ModelPartData head = root.addChild("head", ModelPartBuilder.create().uv(0, 0).cuboid(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F), ModelTransform.of(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F));
		head.addChild("helmet", ModelPartBuilder.create().uv(32, 0).cuboid(-4.5F, -10.5F, -4.5F, 9.0F, 10.5F, 9.0F), ModelTransform.NONE);
		head.addChild("nose", ModelPartBuilder.create().uv(24, 0).cuboid(-1.0F, -4.0F, -6.0F, 2.0F, 4.0F, 2.0F), ModelTransform.NONE);

		root.addChild("body", ModelPartBuilder.create().uv(0, 18).cuboid(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F), ModelTransform.of(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F));
		root.addChild("right_arm", ModelPartBuilder.create().uv(24, 18).cuboid(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), ModelTransform.of(-5.0F, 2.0F, 0.0F, 0.0F, 0.0F, 0.0F));
		root.addChild("left_arm", ModelPartBuilder.create().uv(24, 34).cuboid(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), ModelTransform.of(5.0F, 2.0F, 0.0F, 0.0F, 0.0F, 0.0F));
		root.addChild("right_leg", ModelPartBuilder.create().uv(0, 34).cuboid(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), ModelTransform.of(-2.0F, 12.0F, 0.0F, 0.0F, 0.0F, 0.0F));
		root.addChild("left_leg", ModelPartBuilder.create().uv(16, 34).cuboid(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), ModelTransform.of(2.0F, 12.0F, 0.0F, 0.0F, 0.0F, 0.0F));

		return TexturedModelData.of(modelData, 64, 64);
	}

	@Override
	public void setAngles(GuardEntityRenderer.GuardRenderState state) {
		this.head.yaw = state.relativeHeadYaw * ((float) Math.PI / 180.0F);
		this.head.pitch = state.pitch * ((float) Math.PI / 180.0F);
		this.helmet.yaw = this.head.yaw;
		this.helmet.pitch = this.head.pitch;
		this.nose.yaw = this.head.yaw;
		this.nose.pitch = this.head.pitch;

		float swing = state.limbSwingAnimationProgress;
		float amount = state.limbSwingAmplitude;
		this.rightLeg.pitch = (float) Math.cos(swing * 0.6662F) * 1.2F * amount;
		this.leftLeg.pitch = (float) Math.cos(swing * 0.6662F + Math.PI) * 1.2F * amount;
		this.rightArm.pitch = (float) Math.cos(swing * 0.6662F + Math.PI) * 1.0F * amount;
		this.leftArm.pitch = (float) Math.cos(swing * 0.6662F) * 1.0F * amount;
	}

	public ModelPart getHead() {
		return this.head;
	}

	@Override
	public void setArmAngle(EntityRenderState state, Arm arm, MatrixStack matrices) {
		this.root.applyTransform(matrices);
		this.getArm(arm).applyTransform(matrices);
	}

	private ModelPart getArm(Arm arm) {
		return arm == Arm.LEFT ? this.leftArm : this.rightArm;
	}
}
