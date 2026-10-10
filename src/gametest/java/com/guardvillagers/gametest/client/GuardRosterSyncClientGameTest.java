package com.guardvillagers.gametest.client;

import com.guardvillagers.client.GuardTacticsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;

/**
 * Regression: an owned guard with an empty equipment slot must not break the roster sync packet. Runs last because
 * a failure disconnects the player and ends the client test run.
 */
public class GuardRosterSyncClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = ClientTestSupport.createWorld(context)) {
			BlockPos feet = ClientTestSupport.playerFeet(singleplayer);
			UUID owner = singleplayer.getServer().computeOnServer(server -> ClientTestSupport.player(server).getUUID());
			ClientTestSupport.spawnFrozenGuard(singleplayer, feet.getX() + 3.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F,
					Map.of(EquipmentSlot.MAINHAND, Items.IRON_SWORD), owner);

			ClientTestSupport.playerCommand(context, "guards tactics");
			context.waitTicks(10);
			boolean connected = context.computeOnClient(client -> client.getConnection() != null);
			if (!connected) {
				throw new AssertionError("Opening /guards tactics with an unarmored owned guard disconnected the player"
						+ " (roster sync failed to encode)");
			}
			context.waitForScreen(GuardTacticsScreen.class);
			ClientTestSupport.clearOverlays(context);
			context.takeScreenshot("roster_unarmored_guard_tactics");
			context.runOnClient(client -> client.setScreen(null));
			context.waitTicks(3);
		}
	}
}
