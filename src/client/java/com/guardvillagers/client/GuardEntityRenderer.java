package com.guardvillagers.client;

import com.guardvillagers.command.hierarchy.GuardRank;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

public class GuardEntityRenderer
		extends HumanoidMobRenderer<GuardEntity, GuardEntityRenderer.GuardRenderState, GuardEntityModel> {
	private static final Identifier OUTFIT_TEXTURE = Identifier.fromNamespaceAndPath("guardvillagers",
			"textures/entity/guard/guard_outfit.png");
	private static final Map<ResourceKey<VillagerType>, Identifier> TYPE_TEXTURES = new HashMap<>();
	private static final Identifier PLAINS_TEXTURE = typeTexture(VillagerType.PLAINS);
	private static final Component COMMANDER_MARKER = Component.literal("★ ").withStyle(ChatFormatting.GOLD);
	private static final Component CAPTAIN_MARKER = Component.literal("» ").withStyle(ChatFormatting.GRAY);

	public GuardEntityRenderer(EntityRendererProvider.Context context) {
		super(
				context,
				new GuardEntityModel(context.bakeLayer(GuardEntityModel.GUARD_LAYER)),
				new GuardEntityModel(context.bakeLayer(GuardEntityModel.GUARD_BABY_LAYER)),
				0.5F,
				VillagerRenderer.CUSTOM_HEAD_TRANSFORMS);
		// Zombie-villager armor geometry fits the 10px villager head: the iron/gold/diamond/netherite helmet faces are
		// open where the nose passes through, so the nose shows through the visor instead of clipping a solid face.
		this.addLayer(
				new HumanoidArmorLayer<>(
						this,
						ArmorModelSet.bake(ModelLayers.ZOMBIE_VILLAGER_ARMOR, context.getModelSet(), GuardEntityModel::new),
						ArmorModelSet.bake(ModelLayers.ZOMBIE_VILLAGER_BABY_ARMOR, context.getModelSet(), GuardEntityModel::new),
						context.getEquipmentRenderer()));
		this.addLayer(new GuardClothingLayer(
				this,
				new GuardEntityModel(context.bakeLayer(GuardEntityModel.GUARD_CLOTHING_LAYER), GuardEntityModel.Pass.CLOTHING),
				new GuardEntityModel(context.bakeLayer(GuardEntityModel.GUARD_OUTFIT_LAYER), GuardEntityModel.Pass.OUTFIT)));
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
		state.typeTexture = resolveTypeTexture(entity);
	}

	/**
	 * Dresses the guard in the robe of the biome its home (village center for village guards) sits in. The home is
	 * synced, so the choice is stable while the guard walks across biomes; guards without a home, or whose home chunk
	 * is not loaded on this client, wear plains clothing.
	 */
	private static Identifier resolveTypeTexture(GuardEntity entity) {
		Optional<BlockPos> home = entity.getSyncedHome();
		if (home.isEmpty()) {
			return PLAINS_TEXTURE;
		}
		Level level = entity.level();
		BlockPos pos = home.get();
		if (!level.isLoaded(pos)) {
			return PLAINS_TEXTURE;
		}
		return typeTexture(VillagerType.byBiome(level.getBiome(pos)));
	}

	private static Identifier typeTexture(ResourceKey<VillagerType> type) {
		return TYPE_TEXTURES.computeIfAbsent(type,
				key -> key.identifier().withPath(path -> "textures/entity/villager/type/" + path + ".png"));
	}

	// MobRenderer only shows names for custom-named mobs; also show the stats label on the guard under the crosshair.
	@Override
	protected boolean shouldShowName(GuardEntity entity, double distanceToCameraSq) {
		// The extra stats label must still honor F1 (hideGui), which vanilla checks through Minecraft.renderNames().
		return super.shouldShowName(entity, distanceToCameraSq) || (Minecraft.renderNames() && this.isLookedAt(entity));
	}

	private boolean isLookedAt(GuardEntity entity) {
		return entity == this.entityRenderDispatcher.crosshairPickEntity && !entity.isInvisible();
	}

	/**
	 * Owned guards always show their name; looking at any guard adds a compact, muted stat line:
	 * {@code Name  Lv 3 Swordsman ❤ 18/20}. The heart is colored by remaining health.
	 */
	@Override
	protected @Nullable Component getNameTag(GuardEntity entity) {
		Component name = super.getNameTag(entity);
		Component marker = rankMarker(entity.getRank());
		if (!this.isLookedAt(entity)) {
			return name == null || marker == null ? name : Component.empty().append(marker).append(name);
		}
		MutableComponent label = Component.empty();
		if (marker != null) {
			label.append(marker);
		}
		if (name != null && entity.hasCustomName()) {
			label.append(name).append(Component.literal("  "));
		}
		label.append(Component.literal("Lv " + entity.getLevel()).withStyle(ChatFormatting.YELLOW));
		label.append(Component.literal(" " + roleLabel(entity.getRole()) + " ").withStyle(ChatFormatting.WHITE));
		float maxHealth = Math.max(1.0F, entity.getMaxHealth());
		float ratio = Mth.clamp(entity.getHealth() / maxHealth, 0.0F, 1.0F);
		ChatFormatting healthColor = ratio > 0.6F ? ChatFormatting.GREEN : ratio > 0.3F ? ChatFormatting.YELLOW : ChatFormatting.RED;
		label.append(Component.literal("❤ " + Mth.ceil(entity.getHealth()) + "/" + Mth.ceil(maxHealth)).withStyle(healthColor));
		return label;
	}

	/** Gold star for the commander, silver chevrons for captains, nothing for soldiers. */
	private static @Nullable Component rankMarker(GuardRank rank) {
		return switch (rank) {
			case COMMANDER -> COMMANDER_MARKER;
			case CAPTAIN -> CAPTAIN_MARKER;
			case SOLDIER -> null;
		};
	}

	private static String roleLabel(GuardRole role) {
		String raw = role.name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
	}

	@Override
	protected HumanoidModel.ArmPose getArmPose(GuardEntity entity, HumanoidArm arm) {
		ItemStack stack = entity.getItemHeldByArm(arm);
		if (stack.isEmpty()) {
			return super.getArmPose(entity, arm);
		}
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
		// The bow goal flags the guard aggressive while it has a target: keep the bow raised between shots, like skeletons.
		if (stack.is(Items.BOW) && entity.isAggressive()) {
			return HumanoidModel.ArmPose.BOW_AND_ARROW;
		}
		HumanoidModel.ArmPose pose = super.getArmPose(entity, arm);
		// The base mob renderer returns EMPTY for any non-spear item, which swings swords and shields like bare hands.
		return pose == HumanoidModel.ArmPose.EMPTY ? HumanoidModel.ArmPose.ITEM : pose;
	}

	public static class GuardRenderState extends HumanoidRenderState {
		public String skinProfileId = "";
		public Identifier typeTexture = PLAINS_TEXTURE;
	}

	/**
	 * Draws the biome robe (vanilla villager type texture) and then the guard outfit on top, the same way vanilla
	 * stacks villager type and profession overlays. Both passes are cutout and ordered after the skin pass.
	 */
	private static final class GuardClothingLayer extends RenderLayer<GuardRenderState, GuardEntityModel> {
		private final GuardEntityModel clothingModel;
		private final GuardEntityModel outfitModel;

		private GuardClothingLayer(
				RenderLayerParent<GuardRenderState, GuardEntityModel> renderer,
				GuardEntityModel clothingModel,
				GuardEntityModel outfitModel) {
			super(renderer);
			this.clothingModel = clothingModel;
			this.outfitModel = outfitModel;
		}

		@Override
		public void submit(
				PoseStack poseStack,
				SubmitNodeCollector submitNodeCollector,
				int lightCoords,
				GuardRenderState state,
				float yRot,
				float xRot) {
			if (state.isInvisible) {
				return;
			}
			renderColoredCutoutModel(this.clothingModel, state.typeTexture, poseStack, submitNodeCollector, lightCoords, state, -1, 1);
			renderColoredCutoutModel(this.outfitModel, OUTFIT_TEXTURE, poseStack, submitNodeCollector, lightCoords, state, -1, 2);
		}
	}
}
