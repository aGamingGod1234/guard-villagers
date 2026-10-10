package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
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
import org.jspecify.annotations.Nullable;

public class GuardEntityRenderer
		extends HumanoidMobRenderer<GuardEntity, GuardEntityRenderer.GuardRenderState, GuardEntityModel> {
	private static final VillagerData FIXED_PLAINS_VILLAGER_DATA = new VillagerData(
			BuiltInRegistries.VILLAGER_TYPE.getOrThrow(VillagerType.PLAINS),
			BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.NONE),
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
	public Identifier getTextureLocation(GuardRenderState state) {
		return GuardSkinResolver.resolveTexture(state.skinProfileId);
	}

	@Override
	public GuardRenderState createRenderState() {
		return new GuardRenderState();
	}

	@Override
	public void extractRenderState(GuardEntity entity, GuardRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.skinProfileId = entity.getSkinProfileId();
	}

	// MobRenderer only shows names for custom-named mobs; also show the stats label on the guard under the crosshair.
	@Override
	protected boolean shouldShowName(GuardEntity entity, double distanceToCameraSq) {
		return super.shouldShowName(entity, distanceToCameraSq)
				|| (entity == this.entityRenderDispatcher.crosshairPickEntity && !entity.isInvisible());
	}

	@Override
	protected @Nullable Component getNameTag(GuardEntity entity) {
		if (entity.hasCustomName() || entity != this.entityRenderDispatcher.crosshairPickEntity) {
			return super.getNameTag(entity);
		}
		return Component.literal(
				"Lv " + entity.getLevel() + " | " + entity.getRole().name() + " | " + entity.getBehavior().name());
	}

	@Override
	protected HumanoidModel.ArmPose getArmPose(GuardEntity entity, HumanoidArm arm) {
		ItemStack stack = entity.getItemHeldByArm(arm);
		if (entity.isUsingItem()) {
			boolean isActiveArm = entity.getUsedItemHand() == InteractionHand.MAIN_HAND
					? arm == entity.getMainArm()
					: arm != entity.getMainArm();
			if (isActiveArm) {
				if (stack.is(Items.BOW)) {
					return HumanoidModel.ArmPose.BOW_AND_ARROW;
				}
				if (stack.is(Items.SHIELD)) {
					return HumanoidModel.ArmPose.BLOCK;
				}
			}
		}
		HumanoidModel.ArmPose pose = super.getArmPose(entity, arm);
		// The base mob renderer returns EMPTY for any non-spear item, which swings swords and shields like bare hands.
		if (pose == HumanoidModel.ArmPose.EMPTY && !stack.isEmpty()) {
			return HumanoidModel.ArmPose.ITEM;
		}
		return pose;
	}

	public static class GuardRenderState extends HumanoidRenderState implements VillagerDataHolderRenderState {
		public String skinProfileId = "";

		@Override
		public VillagerData getVillagerData() {
			return FIXED_PLAINS_VILLAGER_DATA;
		}
	}
}
