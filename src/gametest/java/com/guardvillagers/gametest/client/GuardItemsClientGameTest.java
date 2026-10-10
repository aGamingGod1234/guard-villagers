package com.guardvillagers.gametest.client;

import com.guardvillagers.GuardVillagersMod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Whistle and spawn egg textures in the hotbar, the survival inventory, their tooltips, and held in hand. */
public class GuardItemsClientGameTest implements FabricClientGameTest {
	// Survival inventory: 176x166, hotbar row at y=142, slots 18 apart starting at x=8.
	private static final int IMAGE_WIDTH = 176;
	private static final int IMAGE_HEIGHT = 166;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = ClientTestSupport.createWorld(context)) {
			singleplayer.getServer().runOnServer(server -> {
				var inventory = ClientTestSupport.player(server).getInventory();
				inventory.setItem(0, new ItemStack(GuardVillagersMod.GUARD_WHISTLE));
				inventory.setItem(1, new ItemStack(GuardVillagersMod.GUARD_SPAWN_EGG, 16));
				// Vanilla references next to ours for comparison.
				inventory.setItem(2, new ItemStack(Items.GOAT_HORN));
				inventory.setItem(3, new ItemStack(Items.VILLAGER_SPAWN_EGG));
				inventory.setItem(4, new ItemStack(Items.EMERALD, 32));
			});
			context.waitTicks(5);
			// The selected slot is client-authoritative, so pick it with the real hotbar keys.
			context.getInput().pressKey(options -> options.keyHotbarSlots[0]);
			ClientTestSupport.placeCamera(context, singleplayer,
					ClientTestSupport.playerFeet(singleplayer).getX() + 0.5D,
					ClientTestSupport.playerFeet(singleplayer).getY(),
					ClientTestSupport.playerFeet(singleplayer).getZ() + 0.5D, 0.0F, 20.0F);
			context.waitTicks(10);
			ClientTestSupport.clearOverlays(context);
			context.takeScreenshot("items_hotbar_whistle_held");

			context.getInput().pressKey(options -> options.keyHotbarSlots[1]);
			context.waitTicks(10);
			context.takeScreenshot("items_hotbar_egg_held");

			context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
			context.waitTicks(3);
			context.getInput().setCursorPos(0, 0);
			context.waitTick();
			context.takeScreenshot("items_inventory");

			hoverHotbarSlot(context, 0);
			context.takeScreenshot("items_tooltip_whistle");
			hoverHotbarSlot(context, 1);
			context.takeScreenshot("items_tooltip_spawn_egg");

			context.setScreen(() -> null);
			context.waitTicks(3);
		}
	}

	private static void hoverHotbarSlot(ClientGameTestContext context, int slot) {
		double[] pos = context.computeOnClient(client -> {
			int scale = client.getWindow().getGuiScale();
			int left = (client.getWindow().getGuiScaledWidth() - IMAGE_WIDTH) / 2;
			int top = (client.getWindow().getGuiScaledHeight() - IMAGE_HEIGHT) / 2;
			return new double[] {(left + 8 + slot * 18 + 8) * (double) scale, (top + 142 + 8) * (double) scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.waitTicks(2);
	}
}
