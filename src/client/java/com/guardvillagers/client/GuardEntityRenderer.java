package com.guardvillagers.client;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.client.render.entity.state.VillagerEntityRenderState;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

public class GuardEntityRenderer extends MobEntityRenderer<GuardEntity, VillagerEntityRenderState, VillagerResemblingModel> {
	private static final Identifier TEXTURE = GuardVillagersMod.id("textures/entity/guard_villager.png");

	public GuardEntityRenderer(EntityRendererFactory.Context context) {
		super(context, new VillagerResemblingModel(context.getPart(EntityModelLayers.VILLAGER)), 0.5F);
	}

	@Override
	public Identifier getTexture(VillagerEntityRenderState state) {
		return TEXTURE;
	}

	@Override
	public VillagerEntityRenderState createRenderState() {
		return new VillagerEntityRenderState();
	}

	@Override
	public void updateRenderState(GuardEntity entity, VillagerEntityRenderState state, float tickDelta) {
		super.updateRenderState(entity, state, tickDelta);
		state.headRolling = false;

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.targetedEntity != entity) {
			return;
		}

		boolean detailed = client.player.isSneaking();
		if (detailed) {
			state.displayName = Text.literal(
				"Lv " + entity.getLevel()
					+ " | XP " + entity.getExperience()
					+ " | " + entity.getBehavior().name()
					+ " | " + Math.round(entity.getHealth()) + "/" + Math.round(entity.getMaxHealth()))
				.formatted(Formatting.GOLD, Formatting.BOLD);
		} else {
			state.displayName = Text.literal("Lv " + entity.getLevel()).formatted(Formatting.GOLD, Formatting.BOLD);
		}
	}

	@Override
	protected boolean hasLabel(GuardEntity entity, double distance) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player != null && client.targetedEntity == entity;
	}
}
