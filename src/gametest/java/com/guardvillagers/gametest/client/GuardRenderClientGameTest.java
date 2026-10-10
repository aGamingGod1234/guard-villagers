package com.guardvillagers.gametest.client;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders guards with each kind of gear, from the front and the side, plus a lineup and a vanilla /summon guard.
 * Guards are NoAI so poses and facing are deterministic.
 */
public class GuardRenderClientGameTest implements FabricClientGameTest {
	private static final double EYE_HEIGHT = 1.62D;

	@Override
	public void runTest(ClientGameTestContext context) {
		Map<String, Map<EquipmentSlot, Item>> loadouts = new LinkedHashMap<>();
		loadouts.put("no_gear", Map.of());
		loadouts.put("sword", Map.of(EquipmentSlot.MAINHAND, Items.IRON_SWORD));
		loadouts.put("bow", Map.of(EquipmentSlot.MAINHAND, Items.BOW));
		loadouts.put("sword_shield", Map.of(
				EquipmentSlot.MAINHAND, Items.IRON_SWORD,
				EquipmentSlot.OFFHAND, Items.SHIELD));
		loadouts.put("full_armor", Map.of(
				EquipmentSlot.MAINHAND, Items.DIAMOND_SWORD,
				EquipmentSlot.OFFHAND, Items.SHIELD,
				EquipmentSlot.HEAD, Items.DIAMOND_HELMET,
				EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE,
				EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS,
				EquipmentSlot.FEET, Items.DIAMOND_BOOTS));
		loadouts.put("iron_armor_bow", Map.of(
				EquipmentSlot.MAINHAND, Items.BOW,
				EquipmentSlot.HEAD, Items.IRON_HELMET,
				EquipmentSlot.CHEST, Items.IRON_CHESTPLATE,
				EquipmentSlot.LEGS, Items.IRON_LEGGINGS,
				EquipmentSlot.FEET, Items.IRON_BOOTS));

		try (TestSingleplayerContext singleplayer = ClientTestSupport.createWorld(context)) {
			BlockPos feet = ClientTestSupport.playerFeet(singleplayer);
			double y = feet.getY();
			double cx = feet.getX() + 0.5D;
			double cz = feet.getZ() + 6.5D;
			ClientTestSupport.setHudHidden(context, true);

			// One guard at a time: front and side close-ups. Guard faces north (yaw 180) toward a camera south of it.
			for (Map.Entry<String, Map<EquipmentSlot, Item>> loadout : loadouts.entrySet()) {
				ClientTestSupport.killAllGuards(singleplayer);
				ClientTestSupport.spawnFrozenGuard(singleplayer, cx, y, cz, 180.0F, loadout.getValue(), null);
				ClientTestSupport.placeCamera(context, singleplayer, cx, y, cz - 2.4D, 0.0F, 8.0F);
				singleplayer.getClientLevel().waitForChunksRender();
				context.takeScreenshot("render_" + loadout.getKey() + "_front");
				// Camera east of the guard looking west: shows the guard's left side (shield arm).
				ClientTestSupport.placeCamera(context, singleplayer, cx + 2.4D, y, cz, 90.0F, 8.0F);
				context.takeScreenshot("render_" + loadout.getKey() + "_side");
			}

			// Lineup of every loadout, viewed from the front so relative proportions are easy to compare.
			ClientTestSupport.killAllGuards(singleplayer);
			int index = 0;
			for (Map<EquipmentSlot, Item> gear : loadouts.values()) {
				double x = cx + (index - (loadouts.size() - 1) / 2.0D) * 1.4D;
				ClientTestSupport.spawnFrozenGuard(singleplayer, x, y, cz, 180.0F, gear, null);
				index++;
			}
			ClientTestSupport.placeCamera(context, singleplayer, cx, y, cz - 5.0D, 0.0F, 6.0F);
			context.takeScreenshot("render_lineup_front");

			// Crosshair label: the renderer shows "Lv | ROLE | BEHAVIOR" for the guard under the crosshair.
			ClientTestSupport.killAllGuards(singleplayer);
			ClientTestSupport.spawnFrozenGuard(singleplayer, cx, y, cz, 180.0F, loadouts.get("sword_shield"), null);
			ClientTestSupport.placeCamera(context, singleplayer, cx, y, cz - 3.0D, 0.0F, 15.0F);
			ClientTestSupport.setHudHidden(context, false);
			ClientTestSupport.clearOverlays(context);
			context.waitTicks(2);
			context.takeScreenshot("render_crosshair_label");
			ClientTestSupport.setHudHidden(context, true);

			// A guard from vanilla /summon goes through finalizeSpawn and gets the random natural loadout. Vanilla
			// skips finalizeSpawn when NBT is passed, so summon bare and freeze/rotate it afterwards.
			ClientTestSupport.killAllGuards(singleplayer);
			singleplayer.getServer().runCommand(String.format(java.util.Locale.ROOT,
					"summon guardvillagers:guard %.2f %.2f %.2f", cx, y, cz));
			singleplayer.getServer().runOnServer(server -> {
				for (GuardEntity guard : server.overworld().getEntities(GuardVillagersMod.GUARD_ENTITY_TYPE, g -> true)) {
					guard.setNoAi(true);
					guard.snapTo(cx, y, cz, 180.0F, 0.0F);
					guard.setYHeadRot(180.0F);
					guard.yBodyRot = 180.0F;
				}
			});
			ClientTestSupport.placeCamera(context, singleplayer, cx, y, cz - 2.4D, 0.0F, 8.0F);
			context.takeScreenshot("render_summoned_natural_front");
			ClientTestSupport.placeCamera(context, singleplayer, cx + 2.4D, y, cz, 90.0F, 8.0F);
			context.takeScreenshot("render_summoned_natural_side");

			ClientTestSupport.setHudHidden(context, false);
		}
	}
}
