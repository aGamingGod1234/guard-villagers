package com.guardvillagers.gametest.client;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;

/** Shared helpers for the client screenshot tests. Everything that touches the server runs on the server thread. */
final class ClientTestSupport {
	private ClientTestSupport() {
	}

	/** Flat, structure-free, cheats-on world so the player can run the op-only /guards debug command. */
	static TestSingleplayerContext createWorld(ClientGameTestContext context) {
		TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(state -> {
					state.setAllowCommands(true);
					state.setDifficulty(Difficulty.NORMAL);
				})
				.create();
		singleplayer.getClientLevel().waitForChunksRender();
		return singleplayer;
	}

	static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	/** Block the player is standing in (feet), on the server. */
	static BlockPos playerFeet(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> player(server).blockPosition());
	}

	/** Sends a command from the client exactly like a player typing it in chat. */
	static void playerCommand(ClientGameTestContext context, String command) {
		context.runOnClient(client -> client.getConnection().sendCommand(command));
		context.waitTicks(3);
	}

	/**
	 * Moves the player (and so the camera) and waits for the client to catch up. Uses the API instead of /tp so
	 * "Teleported ..." feedback does not pile up in the chat overlay of later screenshots.
	 */
	static void placeCamera(ClientGameTestContext context, TestSingleplayerContext singleplayer, double x, double y,
			double z, float yaw, float pitch) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(server.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, true);
		});
		context.waitTicks(4);
	}

	/** Clears chat and toasts (recipe unlock popups) so they do not cover what a screenshot is about. */
	static void clearOverlays(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.gui.getChat().clearMessages(false);
			client.getToastManager().clear();
		});
		context.waitTick();
	}

	static void setHudHidden(ClientGameTestContext context, boolean hidden) {
		context.runOnClient(client -> client.options.hideGui = hidden);
	}

	/**
	 * Spawns a frozen (NoAI) guard with explicit gear, facing {@code yaw}. A guard spawned with
	 * {@link EntitySpawnReason#MOB_SUMMONED} skips the random natural loadout, so the gear is exactly what we pass.
	 */
	static UUID spawnFrozenGuard(TestSingleplayerContext singleplayer, double x, double y, double z, float yaw,
			Map<EquipmentSlot, Item> gear, UUID owner) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(level, EntitySpawnReason.MOB_SUMMONED);
			if (guard == null) {
				throw new IllegalStateException("guard entity type returned null");
			}
			guard.snapTo(x, y, z, yaw, 0.0F);
			guard.setYHeadRot(yaw);
			guard.yBodyRot = yaw;
			guard.setNoAi(true);
			guard.setPersistenceRequired();
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				guard.setItemSlot(slot, ItemStack.EMPTY);
			}
			gear.forEach((slot, item) -> guard.setItemSlot(slot, new ItemStack(item)));
			if (owner != null) {
				guard.setOwnerUuid(owner);
			}
			if (!level.addFreshEntity(guard)) {
				throw new IllegalStateException("failed to add guard");
			}
			return guard.getUUID();
		});
	}

	static void killAllGuards(TestSingleplayerContext singleplayer) {
		// discard() instead of /kill so the next screenshot has no death animation or poof particles.
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			for (GuardEntity guard : java.util.List.copyOf(level.getEntities(GuardVillagersMod.GUARD_ENTITY_TYPE, g -> true))) {
				guard.discard();
			}
		});
	}
}
