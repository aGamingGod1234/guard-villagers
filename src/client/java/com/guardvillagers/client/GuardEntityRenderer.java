package com.guardvillagers.client;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class GuardEntityRenderer extends MobEntityRenderer<GuardEntity, GuardEntityRenderer.GuardRenderState, GuardEntityModel> {
	private static final Identifier TEXTURE = Identifier.of("guardvillagers", "textures/entity/guard_villager.png");

	public GuardEntityRenderer(EntityRendererFactory.Context context) {
		super(context, new GuardEntityModel(context.getPart(GuardEntityModel.GUARD_LAYER)), 0.5F);
		this.addFeature(new HeldItemFeatureRenderer<>(this));
	}

	@Override
	public Identifier getTexture(GuardRenderState state) {
		return TEXTURE;
	}

	@Override
	public GuardRenderState createRenderState() {
		return new GuardRenderState();
	}

	@Override
	public void updateRenderState(GuardEntity entity, GuardRenderState state, float tickDelta) {
		super.updateRenderState(entity, state, tickDelta);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null && client.targetedEntity == entity && state.displayName == null) {
			state.displayName = Text.literal("Lv " + entity.getLevel() + " | " + entity.getRole().name() + " | " + entity.getBehavior().name());
		}
	}

	public static class GuardRenderState extends BipedEntityRenderState {
	}
}
