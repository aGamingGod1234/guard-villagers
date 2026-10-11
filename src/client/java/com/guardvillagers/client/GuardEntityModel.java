package com.guardvillagers.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Side-armed villager body used for every guard render pass.
 *
 * <p>The base and biome-clothing passes share one mesh that samples the vanilla villager UV layout. The outfit pass
 * (kettle hat, tabard, gloves) uses its own mesh and texture, so it never depends on which pixels vanilla textures
 * leave transparent.
 */
public final class GuardEntityModel extends HumanoidModel<GuardEntityRenderer.GuardRenderState> {
	private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath("guardvillagers", "guard");
	public static final ModelLayerLocation GUARD_LAYER = new ModelLayerLocation(LAYER_ID, "main");
	public static final ModelLayerLocation GUARD_BABY_LAYER = new ModelLayerLocation(LAYER_ID, "baby");
	public static final ModelLayerLocation GUARD_CLOTHING_LAYER = new ModelLayerLocation(LAYER_ID, "clothing");
	public static final ModelLayerLocation GUARD_CLOTHING_BABY_LAYER = new ModelLayerLocation(LAYER_ID, "clothing_baby");
	public static final ModelLayerLocation GUARD_OUTFIT_LAYER = new ModelLayerLocation(LAYER_ID, "outfit");

	/** Which render pass a baked model instance serves; decides per-frame part visibility in {@link #setupAnim}. */
	public enum Pass {
		/** Skin pass and armor models: every part visible. */
		BODY,
		/** Biome robe pass: head and hands hidden, the guard always wears a hat or helmet and gloves over them. */
		CLOTHING,
		/** Guard outfit pass: the kettle hat hides when a helmet is worn. */
		OUTFIT
	}

	private final Pass pass;
	private final @Nullable ModelPart robe;
	private final @Nullable ModelPart robeFitted;
	private final @Nullable ModelPart rightHand;
	private final @Nullable ModelPart leftHand;

	/** Armor-model constructor used by {@code ArmorModelSet.bake}; armor meshes have none of the optional parts. */
	public GuardEntityModel(ModelPart root) {
		this(root, Pass.BODY);
	}

	public GuardEntityModel(ModelPart root, Pass pass) {
		super(root);
		this.pass = pass;
		this.robe = optionalChild(this.body, "robe");
		this.robeFitted = optionalChild(this.body, "robe_fitted");
		this.rightHand = optionalChild(this.rightArm, "hand");
		this.leftHand = optionalChild(this.leftArm, "hand");
	}

	private static @Nullable ModelPart optionalChild(ModelPart parent, String name) {
		return parent.hasChild(name) ? parent.getChild(name) : null;
	}

	public static LayerDefinition getTexturedModelData() {
		MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
		PartDefinition root = mesh.getRoot();

		// Head + nose and the (vanilla-transparent) hat shell, matching the villager/zombie villager head.
		PartDefinition head = root.addOrReplaceChild(
				"head",
				CubeListBuilder.create()
						.texOffs(0, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F)
						.texOffs(24, 0).addBox(-1.0F, -3.0F, -6.0F, 2.0F, 4.0F, 2.0F),
				PartPose.ZERO);
		head.addOrReplaceChild(
				"hat",
				CubeListBuilder.create().texOffs(32, 0)
						.addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, new CubeDeformation(0.51F)),
				PartPose.ZERO);

		PartDefinition body = root.addOrReplaceChild(
				"body",
				CubeListBuilder.create().texOffs(16, 20).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F),
				PartPose.ZERO);
		// The vanilla 20px robe (UV 0,38) is split at the waist so the upper half can tighten under a chestplate
		// (chestplate front sits at z=3.1, the loose 0.5 robe at 3.5 would poke through it) while the skirt stays
		// wide enough (0.7) to cover leggings, which reach x=4.6.
		body.addOrReplaceChild(
				"robe",
				CubeListBuilder.create().texOffs(0, 38)
						.addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F, new CubeDeformation(0.5F)),
				PartPose.ZERO);
		body.addOrReplaceChild(
				"robe_fitted",
				CubeListBuilder.create().texOffs(0, 38)
						.addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F, new CubeDeformation(0.05F)),
				PartPose.ZERO);
		body.addOrReplaceChild(
				"skirt",
				CubeListBuilder.create().texOffs(0, 50)
						.addBox(-4.0F, 12.0F, -3.0F, 8.0F, 8.0F, 6.0F, new CubeDeformation(0.7F)),
				PartPose.ZERO);

		// Vanilla villager arms only have 8px of sleeve texture (UV 44,22); the 4px hand samples plain skin from the
		// back/side of the head (UV 16,8), which no biome robe texture paints over.
		PartDefinition rightArm = root.addOrReplaceChild(
				"right_arm",
				CubeListBuilder.create().texOffs(44, 22).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 8.0F, 4.0F),
				PartPose.offset(-5.0F, 2.0F, 0.0F));
		rightArm.addOrReplaceChild(
				"hand",
				CubeListBuilder.create().texOffs(16, 8).addBox(-3.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F),
				PartPose.ZERO);
		PartDefinition leftArm = root.addOrReplaceChild(
				"left_arm",
				CubeListBuilder.create().texOffs(44, 22).mirror().addBox(-1.0F, -2.0F, -2.0F, 4.0F, 8.0F, 4.0F),
				PartPose.offset(5.0F, 2.0F, 0.0F));
		leftArm.addOrReplaceChild(
				"hand",
				CubeListBuilder.create().texOffs(16, 8).mirror().addBox(-1.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F),
				PartPose.ZERO);

		root.addOrReplaceChild(
				"right_leg",
				CubeListBuilder.create().texOffs(0, 22).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
				PartPose.offset(-2.0F, 12.0F, 0.0F));
		root.addOrReplaceChild(
				"left_leg",
				CubeListBuilder.create().texOffs(0, 22).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
				PartPose.offset(2.0F, 12.0F, 0.0F));
		return LayerDefinition.create(mesh, 64, 64);
	}

	/**
	 * Outfit mesh for {@code textures/entity/guard/guard_outfit.png}: iron kettle hat (dome + brim), a tabard with
	 * front and back panels that sits outside both the robe (z=3.5) and a chestplate (z=3.1), and leather gloves.
	 */
	public static LayerDefinition getOutfitModelData() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
		// Brim sits at y -6..-5, just above the eyes (y about -5) and above the nose (y -3..1), so neither clips.
		head.addOrReplaceChild(
				"hat",
				CubeListBuilder.create()
						.texOffs(0, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 4.0F, 8.0F, new CubeDeformation(0.6F))
						.texOffs(0, 12).addBox(-6.0F, -6.0F, -6.0F, 12.0F, 1.0F, 12.0F),
				PartPose.ZERO);
		root.addOrReplaceChild(
				"body",
				CubeListBuilder.create().texOffs(0, 26)
						.addBox(-3.0F, 0.0F, -3.5F, 6.0F, 14.0F, 7.0F, new CubeDeformation(0.0F, 0.0F, 0.45F)),
				PartPose.ZERO);
		root.addOrReplaceChild(
				"right_arm",
				CubeListBuilder.create().texOffs(32, 26)
						.addBox(-3.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F, new CubeDeformation(0.3F)),
				PartPose.offset(-5.0F, 2.0F, 0.0F));
		root.addOrReplaceChild(
				"left_arm",
				CubeListBuilder.create().texOffs(32, 26).mirror()
						.addBox(-1.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F, new CubeDeformation(0.3F)),
				PartPose.offset(5.0F, 2.0F, 0.0F));
		root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-2.0F, 12.0F, 0.0F));
		root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(2.0F, 12.0F, 0.0F));
		return LayerDefinition.create(mesh, 64, 64);
	}

	@Override
	public void setupAnim(GuardEntityRenderer.GuardRenderState state) {
		super.setupAnim(state);
		boolean chestArmor = !state.chestEquipment.isEmpty();
		if (this.robe != null) {
			this.robe.visible = !chestArmor;
		}
		if (this.robeFitted != null) {
			this.robeFitted.visible = chestArmor;
		}
		boolean showSkinParts = this.pass != Pass.CLOTHING;
		this.head.visible = showSkinParts;
		if (this.rightHand != null) {
			this.rightHand.visible = showSkinParts;
		}
		if (this.leftHand != null) {
			this.leftHand.visible = showSkinParts;
		}
		if (this.pass == Pass.OUTFIT) {
			this.hat.visible = state.headEquipment.isEmpty();
		}
	}
}
