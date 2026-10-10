package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.client.renderer.entity.state.VillagerDataHolderRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class GuardEntityRenderer
		extends HumanoidMobRenderer<GuardEntity, GuardEntityRenderer.GuardRenderState, GuardEntityModel> {
	private static final VillagerData FIXED_PLAINS_VILLAGER_DATA = new VillagerData(
			BuiltInRegistries.VILLAGER_TYPE.wrapAsHolder(BuiltInRegistries.VILLAGER_TYPE.getValueOrThrow(VillagerType.PLAINS)),
			BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
					BuiltInRegistries.VILLAGER_PROFESSION.getValueOrThrow(VillagerProfession.NONE)),
			1);

	public GuardEntityRenderer(EntityRendererProvider.Context context) {
		super(
				context,
				new GuardEntityModel(context.bakeLayer(GuardEntityModel.GUARD_LAYER)),
				new GuardEntityModel(context.bakeLayer(GuardEntityModel.GUARD_BABY_LAYER)),
				0.5F,
				VillagerRenderer.CUSTOM_HEAD_TRANSFORMS);
		this.addLayer(
				new HumanoidArmorLayer<>(
						this,
						ArmorModelSet.bake(
								ModelLayers.ZOMBIE_VILLAGER_ARMOR,
								context.getModelSet(),
								GuardEntityModel::new),
						ArmorModelSet.bake(
								ModelLayers.ZOMBIE_VILLAGER_BABY_ARMOR,
								context.getModelSet(),
								GuardEntityModel::new),
						context.getEquipmentRenderer()));
		this.addLayer(
				new VillagerProfessionLayer<>(
						this,
						context.getResourceManager(),
						"villager",
						new GuardEntityModel(context.bakeLayer(GuardEntityModel.GUARD_CLOTHING_LAYER)),
						new GuardEntityModel(context.bakeLayer(GuardEntityModel.GUARD_CLOTHING_BABY_LAYER))));
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
		super.extractRenderState(entity, state, tickDelta);
		state.skinProfileId = entity.getSkinProfileId();
		state.villagerData = FIXED_PLAINS_VILLAGER_DATA;
		Minecraft client = Minecraft.getInstance();
		if (client.player != null && client.crosshairPickEntity == entity && state.displayName == null) {
			state.displayName = Component.literal(
					"Lv " + entity.getLevel() + " | " + entity.getRole().name() + " | " + entity.getBehavior().name());
		}
	}

	@Override
	protected HumanoidModel.ArmPose getArmPose(GuardEntity entity, HumanoidArm arm) {
		if (entity.isUsingItem()) {
			boolean isActiveArm = entity.getActiveHand() == InteractionHand.MAIN_HAND
					? arm == entity.getMainArm()
					: arm != entity.getMainArm();
			if (isActiveArm) {
				ItemStack stack = entity.getStackInArm(arm);
				if (stack.is(Items.BOW)) {
					return HumanoidModel.ArmPose.BOW_AND_ARROW;
				}
				if (stack.is(Items.SHIELD)) {
					return HumanoidModel.ArmPose.BLOCK;
				}
			}
		}
		return super.getArmPose(entity, arm);
	}

	public static class GuardRenderState extends BipedEntityRenderState implements VillagerDataHolderRenderState {
		public String skinProfileId = "";
		public VillagerData villagerData = FIXED_PLAINS_VILLAGER_DATA;

		@Override
		public VillagerData getVillagerData() {
			return this.villagerData;
		}
	}
}
