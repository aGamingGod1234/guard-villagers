package com.guardvillagers.client;

import com.guardvillagers.command.hierarchy.GuardRank;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
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
	// Cache values never refer back to entities or levels, so despawns and disconnects can release them.
	private final Map<GuardEntity, NameCache> names = new WeakHashMap<>();
	private final Map<BlockPos, RobeCache> robes = new WeakHashMap<>();
	private WeakReference<Level> robeLevel = new WeakReference<>(null);

	public GuardEntityRenderer(EntityRendererProvider.Context context) {
		super(
				context,
				new GuardEntityModel(GuardSkinResolver.prepareSkinModel(context.bakeLayer(GuardEntityModel.GUARD_LAYER), context.getResourceManager())),
				new GuardEntityModel(GuardSkinResolver.prepareSkinModel(context.bakeLayer(GuardEntityModel.GUARD_BABY_LAYER), context.getResourceManager())),
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
	private Identifier resolveTypeTexture(GuardEntity entity) {
		Optional<BlockPos> home = entity.getSyncedHome();
		if (home.isEmpty()) {
			return PLAINS_TEXTURE;
		}
		Level level = entity.level();
		if (this.robeLevel.get() != level) {
			this.robes.clear();
			this.robeLevel = new WeakReference<>(level);
		}
		BlockPos pos = home.get();
		if (!level.isLoaded(pos)) {
			this.robes.remove(pos);
			return PLAINS_TEXTURE;
		}
		// Share exact home positions, not chunks: biome borders and height can change the robe within a chunk.
		// Recheck each game tick to observe biome packets, including changes to neighboring biome samples.
		RobeCache cache = this.robes.computeIfAbsent(pos, ignored -> new RobeCache());
		long tick = level.getGameTime();
		if (cache.tick != tick) {
			cache.texture = typeTexture(VillagerType.byBiome(level.getBiome(pos)));
			cache.tick = tick;
		}
		return cache.texture;
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
		NameCache cache = this.names.computeIfAbsent(entity, ignored -> new NameCache());
		Component customName = entity.getCustomName();
		// Teams have mutable prefixes, suffixes and styles. Keep vanilla formatting live for those rare guards.
		boolean changedName = cache.name == null || cache.customName != customName || entity.getTeam() != null || cache.hadTeam;
		if (changedName) {
			cache.customName = customName;
			cache.name = super.getNameTag(entity);
			cache.hadTeam = entity.getTeam() != null;
		}
		Component name = cache.name;
		GuardRank rank = entity.getRank();
		boolean focused = this.isLookedAt(entity);
		boolean includeName = focused && name != null && customName != null
				&& super.shouldShowName(entity, this.entityRenderDispatcher.distanceToSqr(entity));
		int level = focused ? entity.getLevel() : 0;
		GuardRole role = focused ? entity.getRole() : null;
		float health = focused ? entity.getHealth() : 0;
		float maxHealth = focused ? Math.max(1.0F, entity.getMaxHealth()) : 1;
		float ratio = Mth.clamp(health / maxHealth, 0.0F, 1.0F);
		ChatFormatting healthColor = ratio > 0.6F ? ChatFormatting.GREEN : ratio > 0.3F ? ChatFormatting.YELLOW : ChatFormatting.RED;
		int hp = Mth.ceil(health);
		int maxHp = Mth.ceil(maxHealth);
		if (!changedName && cache.rank == rank && cache.focused == focused && cache.includeName == includeName
				&& cache.level == level && cache.role == role && cache.hp == hp && cache.maxHp == maxHp && cache.healthColor == healthColor) {
			return cache.label;
		}
		cache.rank = rank;
		cache.focused = focused;
		cache.includeName = includeName;
		cache.level = level;
		cache.role = role;
		cache.hp = hp;
		cache.maxHp = maxHp;
		cache.healthColor = healthColor;
		Component marker = rankMarker(rank);
		if (!focused) {
			cache.label = name == null || marker == null ? name : Component.empty().append(marker).append(name);
			return cache.label;
		}
		MutableComponent label = Component.empty();
		if (marker != null) {
			label.append(marker);
		}
		if (includeName) {
			label.append(name).append(Component.literal("  "));
		}
		label.append(Component.literal("Lv " + level).withStyle(ChatFormatting.YELLOW));
		label.append(Component.literal(role == GuardRole.BOWMAN ? " Bowman " : " Swordsman ").withStyle(ChatFormatting.WHITE));
		label.append(Component.literal("❤ " + hp + "/" + maxHp).withStyle(healthColor));
		cache.label = label;
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

	private static final class RobeCache {
		private long tick = Long.MIN_VALUE;
		private Identifier texture = PLAINS_TEXTURE;
	}

	private static final class NameCache {
		private Component customName;
		private Component name;
		private Component label;
		private GuardRank rank;
		private GuardRole role;
		private ChatFormatting healthColor;
		private boolean hadTeam;
		private boolean focused;
		private boolean includeName;
		private int level;
		private int hp;
		private int maxHp;
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
