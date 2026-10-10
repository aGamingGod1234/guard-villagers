package com.guardvillagers.gametest.client;

import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.UUID;

/** Op player toggles /guards debug, then a guard chases a frozen husk while the overlay draws path, target and label. */
public class GuardDebugOverlayClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = ClientTestSupport.createWorld(context)) {
			BlockPos feet = ClientTestSupport.playerFeet(singleplayer);
			double y = feet.getY();
			double gx = feet.getX() + 0.5D;
			double gz = feet.getZ() + 6.5D;

			ClientTestSupport.playerCommand(context, "guards debug");
			context.waitTicks(5);

			UUID guardId = ClientTestSupport.spawnFrozenGuard(singleplayer, gx - 4.0D, y, gz, 90.0F,
					java.util.Map.of(EquipmentSlot.MAINHAND, Items.IRON_SWORD), null);
			// Two more guards so the overlay has to tell the focused guard apart from the rest.
			UUID secondId = ClientTestSupport.spawnFrozenGuard(singleplayer, gx - 6.0D, y, gz + 3.0D, 90.0F,
					java.util.Map.of(EquipmentSlot.MAINHAND, Items.BOW), null);
			UUID thirdId = ClientTestSupport.spawnFrozenGuard(singleplayer, gx - 2.0D, y, gz - 2.0D, 90.0F,
					java.util.Map.of(EquipmentSlot.MAINHAND, Items.IRON_SWORD, EquipmentSlot.OFFHAND, Items.SHIELD), null);
			// Wide view of the arena, framed before anything moves so the first paths are on screen.
			ClientTestSupport.placeCamera(context, singleplayer, gx + 1.0D, y + 2.5D, gz - 10.0D, 0.0F, 12.0F);
			ClientTestSupport.clearOverlays(context);
			context.waitTicks(10);
			context.takeScreenshot("debug_overlay_idle");

			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();
				Husk husk = EntityType.HUSK.create(level, EntitySpawnReason.MOB_SUMMONED);
				husk.snapTo(gx + 8.0D, y, gz + 4.0D, 90.0F, 0.0F);
				husk.setNoAi(true);
				husk.setPersistenceRequired();
				level.addFreshEntity(husk);
				for (UUID id : java.util.List.of(guardId, secondId, thirdId)) {
					GuardEntity guard = (GuardEntity) level.getEntity(id);
					guard.setNoAi(false);
					guard.setMainTarget(husk);
					guard.setTarget(husk);
				}
			});
			context.waitTicks(3);
			context.takeScreenshot("debug_overlay_chasing");
			context.waitTicks(15);
			context.takeScreenshot("debug_overlay_later");

			// Mid distance with the crosshair on one guard: only that guard should get the ring and full label.
			Double[] midPos = singleplayer.getServer().computeOnServer(server -> {
				GuardEntity guard = (GuardEntity) server.overworld().getEntity(guardId);
				return guard == null ? null : new Double[] {guard.getX(), guard.getZ()};
			});
			if (midPos != null) {
				ClientTestSupport.placeCamera(context, singleplayer, midPos[0], y, midPos[1] - 6.0D, 0.0F, 6.0F);
				context.takeScreenshot("debug_overlay_crosshair_mid");
			}

			// Close-up on the label.
			Double[] guardPos = singleplayer.getServer().computeOnServer(server -> {
				GuardEntity guard = (GuardEntity) server.overworld().getEntity(guardId);
				return guard == null ? null : new Double[] {guard.getX(), guard.getZ()};
			});
			if (guardPos != null) {
				ClientTestSupport.placeCamera(context, singleplayer, guardPos[0], y, guardPos[1] - 3.5D, 0.0F, -5.0F);
				context.takeScreenshot("debug_overlay_label_closeup");
			}

			ClientTestSupport.playerCommand(context, "guards debug");
			context.waitTicks(3);
			context.takeScreenshot("debug_overlay_disabled");
		}
	}
}
