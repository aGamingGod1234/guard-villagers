package com.guardvillagers.gametest.client;

import com.guardvillagers.client.GuardTacticsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owned guards + groups, then the tactics map (zoom, pan, select) and the groups view. */
public class GuardTacticsClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = ClientTestSupport.createWorld(context)) {
			BlockPos feet = ClientTestSupport.playerFeet(singleplayer);
			UUID owner = singleplayer.getServer().computeOnServer(server -> ClientTestSupport.player(server).getUUID());

			// Spread owned guards around the player so they land in several map chunks.
			int[][] offsets = {{6, 4}, {-8, 3}, {12, -10}, {-14, -12}, {3, 18}, {-20, 9}, {22, 2}};
			List<UUID> guards = new ArrayList<>();
			for (int i = 0; i < offsets.length; i++) {
				// Every slot the roster packet sends is filled: an empty armor slot currently kicks the owner
				// (see GuardRosterSyncClientGameTest).
				Map<EquipmentSlot, Item> gear = Map.of(
						EquipmentSlot.MAINHAND, i % 2 == 0 ? Items.IRON_SWORD : Items.BOW,
						EquipmentSlot.HEAD, Items.IRON_HELMET,
						EquipmentSlot.CHEST, Items.IRON_CHESTPLATE,
						EquipmentSlot.LEGS, Items.IRON_LEGGINGS,
						EquipmentSlot.FEET, Items.IRON_BOOTS);
				guards.add(ClientTestSupport.spawnFrozenGuard(singleplayer, feet.getX() + offsets[i][0] + 0.5D, feet.getY(),
						feet.getZ() + offsets[i][1] + 0.5D, 0.0F, gear, owner));
			}

			// Fixed names so rows sort the same every run; typical first + last names, one long one.
			String[] names = {"Abigail Henderson", "Felix Carter", "Liam Ortiz", "Isabella Moreno", "Crystal Hayes",
					"Elijah Brooks", "Leah Kim"};
			singleplayer.getServer().runOnServer(server -> {
				for (int i = 0; i < guards.size(); i++) {
					net.minecraft.world.entity.Entity guard = server.overworld().getEntity(guards.get(i));
					if (guard != null) {
						guard.setCustomName(net.minecraft.network.chat.Component.literal(names[i]));
					}
				}
			});

			// Groups through the real player commands, including one long name to check text overflow.
			ClientTestSupport.playerCommand(context, "guards groups add");
			ClientTestSupport.playerCommand(context, "guards groups add");
			ClientTestSupport.playerCommand(context, "guards groups add");
			ClientTestSupport.playerCommand(context, "guards groups rename 1 Vanguard");
			ClientTestSupport.playerCommand(context, "guards groups rename 2 Archers");
			ClientTestSupport.playerCommand(context, "guards groups rename 3 Night Watch Of The Northern Wall");
			ClientTestSupport.playerCommand(context, "guards groups assign " + guards.get(0) + " 1");
			ClientTestSupport.playerCommand(context, "guards groups assign " + guards.get(2) + " 1");
			ClientTestSupport.playerCommand(context, "guards groups assign " + guards.get(1) + " 2");
			ClientTestSupport.playerCommand(context, "guards groups assign " + guards.get(3) + " 2");
			ClientTestSupport.playerCommand(context, "guards groups assign " + guards.get(4) + " 3");
			// Ranks through the uuid form of /guards appoint, the same command the screen's rank menu sends.
			ClientTestSupport.playerCommand(context, "guards appoint commander " + guards.get(0));
			ClientTestSupport.playerCommand(context, "guards appoint captain " + guards.get(1));
			context.waitTicks(10);

			// Tactics map.
			ClientTestSupport.playerCommand(context, "guards tactics");
			context.waitForScreen(GuardTacticsScreen.class);
			context.waitTicks(10);
			ClientTestSupport.clearOverlays(context);
			context.takeScreenshot("tactics_map");

			TestInput input = context.getInput();
			int[] center = context.computeOnClient(client -> new int[] {
					client.getWindow().getWidth() / 2, client.getWindow().getHeight() / 2});
			input.setCursorPos(center[0], center[1]);
			context.waitTick();
			input.scroll(3.0D);
			context.waitTicks(5);
			context.takeScreenshot("tactics_map_zoom_in");

			input.scroll(-6.0D);
			context.waitTicks(5);
			context.takeScreenshot("tactics_map_zoom_out");

			// Middle-drag pans the map.
			input.setCursorPos(center[0], center[1]);
			input.holdMouse(2);
			for (int i = 0; i < 6; i++) {
				input.moveCursor(25.0D, 15.0D);
				context.waitTick();
			}
			input.releaseMouse(2);
			context.waitTicks(3);
			context.takeScreenshot("tactics_map_panned");

			// Left-drag selects a chunk rectangle. The pan above moved the discovered area right/down by 150x90 px, and
			// a drag that starts on an undiscovered chunk clears the selection, so start inside it.
			input.setCursorPos(center[0] + 60, center[1] + 40);
			input.holdMouse(0);
			for (int i = 0; i < 6; i++) {
				input.moveCursor(15.0D, 8.0D);
				context.waitTick();
			}
			context.takeScreenshot("tactics_map_selecting");
			input.releaseMouse(0);
			context.waitTicks(3);
			context.takeScreenshot("tactics_map_selected");

			context.runOnClient(client -> client.setScreen(null));
			context.waitTicks(5);

			// Groups view.
			ClientTestSupport.playerCommand(context, "guards groups");
			context.waitForScreen(GuardTacticsScreen.class);
			context.waitTicks(10);
			ClientTestSupport.clearOverlays(context);
			context.takeScreenshot("groups_view");

			// Hover the first guard card area to surface any hover state/tooltip.
			int[] hover = context.computeOnClient(client -> new int[] {
					client.getWindow().getWidth() / 4, client.getWindow().getHeight() / 3});
			input.setCursorPos(hover[0], hover[1]);
			context.waitTicks(3);
			context.takeScreenshot("groups_view_hover");

			// Rank interactions. Window pixels for the default 854x480 test window at GUI scale 2; rows from the
			// groups_view screenshot: Vanguard = Abigail (commander), Liam; Archers = Felix (captain), Isabella.
			boolean defaultWindow = context.computeOnClient(client ->
					client.getWindow().getWidth() == 854 && client.getWindow().getHeight() == 480
							&& client.getWindow().getGuiScale() == 2);
			if (defaultWindow) {
				input.setCursorPos(566, 110);
				context.waitTicks(3);
				context.takeScreenshot("groups_rank_badge_tooltip");

				input.setCursorPos(700, 234);
				context.waitTicks(3);
				context.takeScreenshot("groups_guard_tooltip");

				// Right-click Isabella: the rank menu opens at the cursor (flipped left at the panel edge).
				input.pressMouse(1);
				context.waitTicks(3);
				context.takeScreenshot("groups_rank_menu");

				// "Make captain" is the second item: the menu is 96 GUI px wide, items start 13 px below its top.
				input.setCursorPos(2 * (350 - 96) + 60, 2 * (117 + 13 + 12) + 12);
				context.waitTicks(2);
				context.takeScreenshot("groups_rank_menu_hover");
				input.pressMouse(0);
				context.waitTicks(15);
				input.setCursorPos(300, 20);
				context.waitTicks(2);
				context.takeScreenshot("groups_captain_appointed");

				// Collapse Vanguard to chips: the commander's star sits at the chip's right edge.
				input.setCursorPos(640, 81);
				input.pressMouse(0);
				context.waitTicks(3);
				input.setCursorPos(300, 20);
				context.waitTicks(2);
				context.takeScreenshot("groups_collapsed_chips");
			}

			context.runOnClient(client -> client.setScreen(null));
			context.waitTicks(3);

			// Nameplates: the commander under the crosshair (star + stats label), then a captain (chevrons).
			ClientTestSupport.clearOverlays(context);
			ClientTestSupport.placeCamera(context, singleplayer, feet.getX() + offsets[0][0] + 0.5D, feet.getY(),
					feet.getZ() + offsets[0][1] + 0.5D - 2.5D, 0.0F, 5.0F);
			context.waitTicks(5);
			context.takeScreenshot("nameplate_commander");
			ClientTestSupport.placeCamera(context, singleplayer, feet.getX() + offsets[1][0] + 0.5D, feet.getY(),
					feet.getZ() + offsets[1][1] + 0.5D - 3.5D, 0.0F, -5.0F);
			context.waitTicks(5);
			context.takeScreenshot("nameplate_captain");
		}
	}
}
