package com.guardvillagers.client;

import com.guardvillagers.GuardVillagersMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public class GuardVillagersClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(GuardVillagersMod.GUARD_ENTITY_TYPE, GuardEntityRenderer::new);
		HandledScreens.register(GuardVillagersMod.GUARD_TACTICS_SCREEN_HANDLER, GuardTacticsScreen::new);
	}
}
