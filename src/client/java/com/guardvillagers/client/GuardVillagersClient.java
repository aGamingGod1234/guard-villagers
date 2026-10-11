package com.guardvillagers.client;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.network.GuardDebugDataPayload;
import com.guardvillagers.network.GuardDebugSyncPayload;
import com.guardvillagers.network.GuardGroupResultPayload;
import com.guardvillagers.network.GuardRosterSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;

public class GuardVillagersClient implements ClientModInitializer {
	private static final ChunkTerrainCache TERRAIN_CACHE = new ChunkTerrainCache();

	public static ChunkTerrainCache terrainCache() {
		return TERRAIN_CACHE;
	}

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(com.guardvillagers.network.GuardTacticsAckPayload.ID, (payload, context) ->
			context.client().execute(GuardCommandQueue::acknowledge));
		ClientPlayNetworking.registerGlobalReceiver(GuardRosterSyncPayload.ID, (payload, context) ->
			context.client().execute(() -> {
				Minecraft client = context.client();
				if (client.level == null) {
					return;
				}
				ClientTacticsDataStore.WorldContext worldContext = ClientTacticsDataStore.resolveContext(client, client.level);
				ClientGuardRosterStore.getInstance().applyPayload(worldContext, payload);
				ClientTacticsDataStore.getInstance().replaceGroupNames(worldContext, payload.groupNames());
			})
		);
		ClientPlayNetworking.registerGlobalReceiver(GuardGroupResultPayload.ID, (payload, context) ->
			context.client().execute(() -> {
				Minecraft client = context.client();
				if (client.level != null) {
					ClientTacticsDataStore.getInstance().applyGroupResult(ClientTacticsDataStore.resolveContext(client, client.level), payload);
				}
				ClientGuardRosterStore.getInstance().pushGroupResult(payload);
			})
		);
		ClientPlayNetworking.registerGlobalReceiver(GuardDebugSyncPayload.ID, (payload, context) ->
			context.client().execute(() -> {
				ClientDebugState.update(payload.enabled(), payload.range());
				if (!payload.enabled()) {
					ClientGuardDebugData.clear();
				}
			})
		);
		ClientPlayNetworking.registerGlobalReceiver(GuardDebugDataPayload.ID, (payload, context) ->
			context.client().execute(() -> ClientGuardDebugData.applyPayload(payload, context.client().level))
		);

		ModelLayerRegistry.registerModelLayer(GuardEntityModel.GUARD_LAYER, GuardEntityModel::getTexturedModelData);
		ModelLayerRegistry.registerModelLayer(
				GuardEntityModel.GUARD_BABY_LAYER,
				GuardEntityModel::getTexturedModelData);
		ModelLayerRegistry.registerModelLayer(
				GuardEntityModel.GUARD_CLOTHING_LAYER,
				GuardEntityModel::getTexturedModelData);
		ModelLayerRegistry.registerModelLayer(
				GuardEntityModel.GUARD_CLOTHING_BABY_LAYER,
				GuardEntityModel::getTexturedModelData);
		ModelLayerRegistry.registerModelLayer(GuardEntityModel.GUARD_OUTFIT_LAYER, GuardEntityModel::getOutfitModelData);
		EntityRenderers.register(GuardVillagersMod.GUARD_ENTITY_TYPE, GuardEntityRenderer::new);
		MenuScreens.register(GuardVillagersMod.GUARD_TACTICS_SCREEN_HANDLER, GuardTacticsScreen::new);
		GuardDebugRenderer.register();

		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			Minecraft client = Minecraft.getInstance();
			ClientTacticsDataStore.WorldContext context = ClientTacticsDataStore.resolveContext(client, world);
			ClientTacticsDataStore.getInstance().markDiscovered(context, chunk.getPos().x(), chunk.getPos().z());
			TERRAIN_CACHE.invalidate(context, chunk.getPos().x(), chunk.getPos().z());
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> ClientTacticsDataStore.getInstance().tickSave());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			GuardCommandQueue.clear();
			ClientTacticsDataStore.getInstance().clearPendingGroups();
			ClientTacticsDataStore.getInstance().flush();
			TERRAIN_CACHE.clearAll();
			ClientGuardRosterStore.getInstance().clear();
			ClientDebugState.reset();
			ClientGuardDebugData.clear();
			GuardDebugRenderer.reset();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			GuardCommandQueue.clear();
			ClientTacticsDataStore.getInstance().clearPendingGroups();
			ClientTacticsDataStore.getInstance().flush();
			ClientGuardRosterStore.getInstance().clear();
			ClientDebugState.reset();
			ClientGuardDebugData.clear();
			GuardDebugRenderer.reset();
		});
	}
}
