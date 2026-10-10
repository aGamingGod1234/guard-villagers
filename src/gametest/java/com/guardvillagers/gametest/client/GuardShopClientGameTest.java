package com.guardvillagers.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;

/** /guards shop, plus tooltips for the info, hire and armor cards. */
public class GuardShopClientGameTest implements FabricClientGameTest {
	// Chest container layout: 176 wide, 114 + 3 rows * 18 tall; slots start at (8, 18) and are 18 apart.
	private static final int IMAGE_WIDTH = 176;
	private static final int IMAGE_HEIGHT = 114 + 3 * 18;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = ClientTestSupport.createWorld(context)) {
			singleplayer.getServer().runCommand("give @a minecraft:emerald 40");
			context.waitTicks(3);

			ClientTestSupport.playerCommand(context, "guards shop");
			context.waitForScreen(ContainerScreen.class);
			context.waitTicks(5);
			context.getInput().setCursorPos(0, 0);
			ClientTestSupport.clearOverlays(context);
			context.takeScreenshot("shop_screen");

			hoverSlot(context, 4);
			context.takeScreenshot("shop_tooltip_info");
			hoverSlot(context, 10);
			context.takeScreenshot("shop_tooltip_hire");
			hoverSlot(context, 12);
			context.takeScreenshot("shop_tooltip_armor");
			hoverSlot(context, 22);
			context.takeScreenshot("shop_tooltip_status");

			context.runOnClient(client -> client.setScreen(null));
			context.waitTicks(3);
		}
	}

	private static void hoverSlot(ClientGameTestContext context, int slot) {
		double[] pos = context.computeOnClient(client -> {
			int scale = client.getWindow().getGuiScale();
			int left = (client.getWindow().getGuiScaledWidth() - IMAGE_WIDTH) / 2;
			int top = (client.getWindow().getGuiScaledHeight() - IMAGE_HEIGHT) / 2;
			int guiX = left + 8 + (slot % 9) * 18 + 8;
			int guiY = top + 18 + (slot / 9) * 18 + 8;
			return new double[] {guiX * (double) scale, guiY * (double) scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.waitTicks(2);
	}
}
