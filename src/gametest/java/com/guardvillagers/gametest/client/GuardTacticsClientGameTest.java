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
				// Fill equipment slots for the visual fixture; empty slots are covered by GuardRosterSyncClientGameTest.
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
			context.runOnClient(client -> checkPendingEdits((GuardTacticsScreen) client.screen, client));
			singleplayer.getServer().runOnServer(server ->
					com.guardvillagers.tactics.GuardTacticsMenus.syncOwnedGuardRoster(ClientTestSupport.player(server)));
			context.waitTicks(3);

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
			if (!defaultWindow) {
				throw new AssertionError("Rank click fixture requires 854x480 at GUI scale 2; update its coordinates for this window");
			}
			{
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
				for (int tick = 0; tick < 100; tick++) {
					boolean appointed = singleplayer.getServer().computeOnServer(server ->
							com.guardvillagers.command.hierarchy.GuardHierarchy.isPinned(server, owner, guards.get(3)));
					if (appointed) break;
					context.waitTick();
				}
				input.setCursorPos(300, 20);
				context.waitTicks(2);
				singleplayer.getServer().runOnServer(server -> {
					com.guardvillagers.entity.GuardEntity appointed = (com.guardvillagers.entity.GuardEntity) server.overworld().getEntity(guards.get(3));
					if (appointed == null || appointed.getRank() != com.guardvillagers.command.hierarchy.GuardRank.CAPTAIN
							|| !com.guardvillagers.command.hierarchy.GuardHierarchy.isPinned(server, owner, appointed.getUUID())) {
						throw new AssertionError("Click must appoint Isabella as captain and persist her appointment pin");
					}
				});
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
	/** Exercise refresh races against the actual screen/store state before taking interaction screenshots. */
	@SuppressWarnings("unchecked")
	private static void checkPendingEdits(GuardTacticsScreen screen, net.minecraft.client.Minecraft client) {
		try {
			var world = com.guardvillagers.client.ClientTacticsDataStore.resolveContext(client, client.level);
			var store = com.guardvillagers.client.ClientTacticsDataStore.getInstance();
			var roster = com.guardvillagers.client.ClientGuardRosterStore.getInstance();
			List<String> names = new ArrayList<>();
			for (int row = 0; row < store.groupCount(world); row++) names.add(store.getGroupName(world, row));
			store.expectGroup(world, names.size());
			store.replaceGroupNames(world, names);
			if (store.groupCount(world) != names.size() + 1) throw new AssertionError("Older roster discarded a queued group add");
			store.applyGroupResult(world, new com.guardvillagers.network.GuardGroupResultPayload(
					com.guardvillagers.network.GuardGroupResultPayload.Action.ADD, -1, 0, 1, "test.rejected"));
			if (store.groupCount(world) != names.size()) throw new AssertionError("Rejected group add was not rolled back");
			store.expectGroup(world, names.size());
			store.replaceGroupNames(world, names);
			List<String> acceptedNames = new ArrayList<>(names);
			acceptedNames.add("New row");
			store.replaceGroupNames(world, acceptedNames);
			store.applyGroupResult(world, new com.guardvillagers.network.GuardGroupResultPayload(
					com.guardvillagers.network.GuardGroupResultPayload.Action.ADD, names.size(), 1, 0, ""));
			if (store.groupCount(world) != acceptedNames.size()) throw new AssertionError("Accepted group add disappeared");
			store.replaceGroupNames(world, names);

			var snapshot = roster.roster(world).stream().filter(guard -> guard.groupIndex() == 0).findFirst().orElseThrow();
			for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
				if (entity.getUUID().equals(snapshot.guardUuid()) && entity instanceof com.guardvillagers.entity.GuardEntity guard) {
					var dispatcher = client.getEntityRenderDispatcher();
					var previousPick = dispatcher.crosshairPickEntity;
					var scoreboard = client.level.getScoreboard();
					var hidden = scoreboard.addPlayerTeam("gv_hidden_test");
					hidden.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.NEVER);
					scoreboard.addPlayerToTeam(guard.getScoreboardName(), hidden);
					try {
						dispatcher.crosshairPickEntity = guard;
						var nameTag = com.guardvillagers.client.GuardEntityRenderer.class.getDeclaredMethod("getNameTag", com.guardvillagers.entity.GuardEntity.class);
						nameTag.setAccessible(true);
						var label = (net.minecraft.network.chat.Component) nameTag.invoke(dispatcher.getRenderer(guard), guard);
						if (label == null || label.getString().contains(guard.getName().getString())) {
							throw new AssertionError("Crosshair stats exposed a team-hidden custom name");
						}
					} finally {
						dispatcher.crosshairPickEntity = previousPick;
						scoreboard.removePlayerTeam(hidden);
					}
					break;
				}
			}
			var pendingField = GuardTacticsScreen.class.getDeclaredField("pendingAssignments");
			var awaitingField = GuardTacticsScreen.class.getDeclaredField("awaitingConfirmation");
			var drop = GuardTacticsScreen.class.getDeclaredMethod("setPendingAssignment",
					com.guardvillagers.client.ClientGuardRosterStore.GuardRosterEntry.class, int.class);
			pendingField.setAccessible(true);
			awaitingField.setAccessible(true);
			drop.setAccessible(true);
			Map<UUID, Integer> pending = (Map<UUID, Integer>) pendingField.get(screen);
			Map<UUID, Integer> awaiting = (Map<UUID, Integer>) awaitingField.get(screen);
			awaiting.put(snapshot.guardUuid(), 1);
			drop.invoke(screen, snapshot, 0);
			if (!Integer.valueOf(0).equals(pending.get(snapshot.guardUuid())) || !awaiting.containsKey(snapshot.guardUuid())) {
				throw new AssertionError("Return drop must compensate for a saved move still in flight");
			}
			pending.clear();
			awaiting.clear();
			roster.applyPayload(world, new com.guardvillagers.network.GuardRosterSyncPayload(names, List.of(
					com.guardvillagers.network.GuardRosterSyncPayload.GuardSummary.unloaded(snapshot.guardUuid(), snapshot.displayName(), 1, names.get(1)))));
			drop.invoke(screen, snapshot, 0);
			if (!Integer.valueOf(0).equals(pending.get(snapshot.guardUuid()))) throw new AssertionError("Drop used stale drag snapshot instead of latest roster");
			pending.clear();

			var identity = com.guardvillagers.client.ClientTacticsDataStore.class.getDeclaredMethod("singleplayerId", java.nio.file.Path.class);
			identity.setAccessible(true);
			Object first = identity.invoke(null, java.nio.file.Path.of("saves", "New World"));
			Object second = identity.invoke(null, java.nio.file.Path.of("saves", "New World (1)"));
			Object normalized = identity.invoke(null, java.nio.file.Path.of("saves", "New World", "."));
			if (first.equals(second) || !first.equals(normalized)) throw new AssertionError("Save identity must use normalized folders, not display names");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Client regression fixture no longer matches the screen", error);
		}
	}

}
