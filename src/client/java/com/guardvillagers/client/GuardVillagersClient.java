package com.guardvillagers.client;

import com.guardvillagers.GuardVillagersMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class GuardVillagersClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(GuardVillagersMod.GUARD_ENTITY_TYPE, GuardEntityRenderer::new);
	}
}
