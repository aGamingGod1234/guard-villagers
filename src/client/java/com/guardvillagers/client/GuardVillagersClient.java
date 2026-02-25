package com.guardvillagers.client;

import com.guardvillagers.GuardVillagersMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;

public class GuardVillagersClient implements ClientModInitializer {
	@Override
	@SuppressWarnings("deprecation")
	public void onInitializeClient() {
		EntityRendererRegistry.register(GuardVillagersMod.GUARD_ENTITY_TYPE, GuardEntityRenderer::new);
		HandledScreens.register(tacticsScreenType(), GuardTacticsScreen::new);
	}

	@SuppressWarnings("unchecked")
	private static ScreenHandlerType<GenericContainerScreenHandler> tacticsScreenType() {
		return (ScreenHandlerType<GenericContainerScreenHandler>) (ScreenHandlerType<?>) GuardVillagersMod.GUARD_TACTICS_SCREEN_HANDLER;
	}
}
