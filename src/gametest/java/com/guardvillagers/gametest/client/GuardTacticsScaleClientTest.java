package com.guardvillagers.gametest.client;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.client.ClientGuardRosterStore;
import com.guardvillagers.client.ClientTacticsDataStore;
import com.guardvillagers.client.GuardTacticsScreen;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.lwjgl.opengl.GL11;

/** End-to-end large roster: real packets, mouse input, saved assignments, and uncapped rendering. */
final class GuardTacticsScaleClientTest {
	private static final int GUARDS = 300;
	private static final int FRAMES = 360;
	private static final String[] GROUPS = {"Vanguard", "Archers", "River Watch", "North Gate", "Reserve"};

	static void run(ClientGameTestContext context) {
		Settings saved = context.computeOnClient(client -> new Settings(client.options.enableVsync().get(),
				client.options.framerateLimit().get(), client.options.inactivityFpsLimit().get()));
		try (TestSingleplayerContext world = ClientTestSupport.createWorld(context)) {
			context.runOnClient(client -> {
				client.options.enableVsync().set(false);
				client.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
				client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
				client.getFramerateLimitTracker().onInputReceived();
				int limit = client.getFramerateLimitTracker().getFramerateLimit();
				check(!client.options.enableVsync().get() && limit == Options.UNLIMITED_FRAMERATE_CUTOFF, "uncapped options");
				client.gameRenderer.getGameRenderState().framerateLimit = limit;
				System.out.printf("GUARD_TACTICS_SETTINGS vsync=false limit=%d throttle=%s size=%dx%d%n", limit,
						client.getFramerateLimitTracker().getThrottleReason(), client.getMainRenderTarget().width, client.getMainRenderTarget().height);
			});
			BlockPos feet = ClientTestSupport.playerFeet(world);
			world.getServer().runCommand("gamerule minecraft:advance_time false");
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			world.getServer().runOnServer(server -> {
				var player = ClientTestSupport.player(server);
				player.setGameMode(GameType.SPECTATOR);
				var source = player.createCommandSourceStack().withSuppressedOutput();
				var dispatcher = server.getCommands().getDispatcher();
				List<UUID> ids = new ArrayList<>();
				for (int i = 0; i < GUARDS; i++) {
					GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(server.overworld(), EntitySpawnReason.MOB_SUMMONED);
					check(guard != null, "create guard");
					guard.setUUID(new UUID(0x54414354494353L, i + 1));
					guard.snapTo(feet.getX() + (i % 20 - 9.5) * 1.5, feet.getY(), feet.getZ() + i / 20 * 1.5, 180, 0);
					guard.setYHeadRot(180);
					guard.yBodyRot = 180;
					guard.setNoAi(true);
					guard.setPersistenceRequired();
					guard.setOwnerUuid(player.getUUID());
					guard.setCustomName(Component.literal(String.format(Locale.ROOT, "Guard %03d", i + 1)));
					for (EquipmentSlot slot : EquipmentSlot.values()) guard.setItemSlot(slot, ItemStack.EMPTY);
					guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(i % 3 == 0 ? Items.BOW : Items.IRON_SWORD));
					guard.setRole(i % 3 == 0 ? GuardRole.BOWMAN : GuardRole.SWORDSMAN);
					if (i % 4 == 0) guard.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
					check(server.overworld().addFreshEntity(guard), "spawn guard");
					ids.add(guard.getUUID());
				}
				try {
					for (int group = 0; group < GROUPS.length; group++) {
						dispatcher.execute("guards groups add", source);
						dispatcher.execute("guards groups rename " + (group + 1) + " " + GROUPS[group], source);
						String members = ids.subList(group * 50, (group + 1) * 50).stream().map(UUID::toString).collect(Collectors.joining(" "));
						check(dispatcher.execute("guards groups assignmany " + (group + 1) + " " + members, source) == 50, "assign 50 members");
					}
				} catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
					throw new AssertionError("Cannot prepare tactics groups", exception);
				}
			});
			ClientTestSupport.placeCamera(context, world, feet.getX(), feet.getY() + 19, feet.getZ() - 25, 0, 27);
			world.getClientLevel().waitForChunksRender();
			ClientTestSupport.playerCommand(context, "guards tactics");
			context.waitForScreen(GuardTacticsScreen.class);
			if (context.computeOnClient(client -> (Boolean) field(screen(client), "helpVisible"))) {
				context.getInput().pressMouse(0);
				context.waitTicks(4);
			}
			for (int tick = 0; tick < 100 && !context.computeOnClient(client -> roster(client).size() == GUARDS); tick++) context.waitTick();
			context.runOnClient(client -> {
				var rows = roster(client);
				check(rows.size() == GUARDS && new HashSet<>(rows.stream().map(ClientGuardRosterStore.GuardRosterEntry::guardUuid).toList()).size() == GUARDS,
						"300 unique guards survive chunked roster sync");
				for (int group = -1; group < 5; group++) {
					int expected = group;
					check(rows.stream().filter(row -> row.groupIndex() == expected).count() == 50, "50 members in group " + group);
				}
			});
			ClientTestSupport.clearOverlays(context);
			context.waitTicks(10);
			capture(context, "top");
			measure(context, "top");
			scrollTo(context, .5);
			capture(context, "middle");
			measure(context, "middle");
			scrollTo(context, 1);
			context.runOnClient(client -> {
				var screen = screen(client);
				List<ClientGuardRosterStore.GuardRosterEntry> unassigned = field(screen, "unassignedList");
				UUID last = unassigned.getLast().guardUuid();
				check(hits(screen, "card").stream().anyMatch(hit -> ((ClientGuardRosterStore.GuardRosterEntry) field(hit, "guard")).guardUuid().equals(last)),
						"last unassigned guard can be reached");
			});
			capture(context, "bottom");
			measure(context, "bottom");

			scrollTo(context, 0);
			clickHeader(context, 0);
			checkCollapsed(context, true);
			capture(context, "collapsed");
			// Separate clicks by more than the GUI double-click threshold.
			context.waitTicks(8);
			clickHeader(context, 0);
			checkCollapsed(context, false);
			capture(context, "expanded");
			context.waitTicks(8);
			clickHeader(context, 0);
			checkCollapsed(context, true);
			dragAndSave(context, world);
			context.runOnClient(client -> client.setScreen(null));
			ClientTestSupport.playerCommand(context, "guards tactics");
			context.waitForScreen(GuardTacticsScreen.class);
			context.waitTicks(10);
			context.runOnClient(client -> {
				check(roster(client).size() == GUARDS, "reopened roster retains all guards");
				check(roster(client).stream().filter(row -> row.groupIndex() == 0).count() == 49, "reopened source group");
				check(roster(client).stream().filter(row -> row.groupIndex() == 1).count() == 51, "reopened destination group");
			});
			capture(context, "reopened");
			context.runOnClient(client -> client.setScreen(null));
			System.out.println("GUARD_TACTICS_SCALE_CHECKS passed: 300 guards, five groups plus unassigned, middle/bottom, expand/collapse, drag, save, reopen");
		} finally {
			context.runOnClient(client -> {
				client.options.enableVsync().set(saved.vsync());
				client.options.framerateLimit().set(saved.limit());
				client.options.inactivityFpsLimit().set(saved.inactivity());
			});
		}
	}

	private static void scrollTo(ClientGameTestContext context, double fraction) {
		int[] pane = context.computeOnClient(client -> {
			var screen = screen(client);
			return new int[]{number(screen, "sideX") + number(screen, "sideW") / 2,
					(number(screen, "listTop") + number(screen, "listBottom")) / 2,
					(int) Math.round(number(screen, "listMaxScroll") * fraction) - number(screen, "listScrollTarget")};
		});
		move(context, pane[0], pane[1]);
		context.getInput().scroll(fraction == 1 ? -10000 : fraction == 0 ? 10000 : -pane[2] / 20.0);
		context.waitTicks(15);
		context.runOnClient(client -> {
			var screen = screen(client);
			double position = field(screen, "listScrollAnimated");
			check(Math.abs(position - number(screen, "listMaxScroll") * fraction) <= 21, "scroll reaches " + fraction);
		});
	}

	private static void clickHeader(ClientGameTestContext context, int group) {
		int[] point = context.computeOnClient(client -> {
			Object row = hits(screen(client), "row").stream().filter(hit -> number(hit, "row") == group).findFirst().orElseThrow();
			return new int[]{number(row, "x") + 10, number(row, "y") + number(row, "h") / 2};
		});
		move(context, point[0], point[1]);
		context.getInput().pressMouse(0);
		context.waitTicks(4);
	}

	private static void checkCollapsed(ClientGameTestContext context, boolean expected) {
		context.runOnClient(client -> check(((BitSet) field(screen(client), "collapsedRows")).get(0) == expected, "group expansion toggles"));
	}

	private static void dragAndSave(ClientGameTestContext context, TestSingleplayerContext world) {
		DragStart start = context.computeOnClient(client -> {
			Object card = hits(screen(client), "card").stream().filter(hit -> number(hit, "group") == 0 && number(hit, "h") >= 10).findFirst().orElseThrow();
			ClientGuardRosterStore.GuardRosterEntry guard = field(card, "guard");
			return new DragStart(guard.guardUuid(), number(card, "x") + number(card, "w") / 2, number(card, "y") + number(card, "h") / 2);
		});
		move(context, start.x(), start.y());
		context.getInput().holdMouse(0);
		move(context, start.x() + 10, start.y() + 2);
		context.waitTick();
		int[] edge = context.computeOnClient(client -> new int[]{number(screen(client), "sideX") + 30, number(screen(client), "listBottom") - 3});
		move(context, edge[0], edge[1]);
		int[] target = null;
		for (int tick = 0; tick < 100 && target == null; tick++) {
			context.waitTick();
			target = context.computeOnClient(client -> {
				var screen = screen(client);
				return hits(screen, "row").stream().filter(row -> number(row, "row") == 1
						&& number(row, "y") > number(screen, "listTop") + 20 && number(row, "y") < number(screen, "listBottom") - 25)
						.map(row -> new int[]{number(row, "x") + 30, number(row, "y") + number(row, "h") / 2}).findFirst().orElse(null);
			});
		}
		check(target != null, "drag auto-scroll reaches next group");
		move(context, target[0], target[1]);
		context.waitTicks(2);
		capture(context, "dragging");
		context.getInput().releaseMouse(0);
		context.waitTicks(5);
		context.runOnClient(client -> {
			Map<UUID, Integer> pending = field(screen(client), "pendingAssignments");
			check(Integer.valueOf(1).equals(pending.get(start.guard())), "drop queues move into second group");
		});
		capture(context, "pending_move");
		int[] save = context.computeOnClient(client -> {
			Object button = field(screen(client), "saveButton");
			return new int[]{number(button, "x") + number(button, "w") / 2, number(button, "y") + number(button, "h") / 2};
		});
		move(context, save[0], save[1]);
		context.getInput().pressMouse(0);
		for (int tick = 0; tick < 100; tick++) {
			context.waitTick();
			if (context.computeOnClient(client -> roster(client).stream().anyMatch(row -> row.guardUuid().equals(start.guard()) && row.groupIndex() == 1))) break;
		}
		world.getServer().runOnServer(server -> {
			var guard = (GuardEntity) server.overworld().getEntity(start.guard());
			check(guard != null && guard.getGroupIndex() == 1, "saved drag reaches server");
		});
		context.runOnClient(client -> {
			check(((Map<?, ?>) field(screen(client), "pendingAssignments")).isEmpty(), "no unsaved move after save");
			check(((Map<?, ?>) field(screen(client), "awaitingConfirmation")).isEmpty(), "server confirms saved move");
			check(roster(client).stream().filter(row -> row.groupIndex() == 0).count() == 49, "source group count");
			check(roster(client).stream().filter(row -> row.groupIndex() == 1).count() == 51, "destination group count");
		});
		capture(context, "saved_move");
		move(context, save[0], save[1]);
		capture(context, "save_tooltip");
	}

	private static void capture(ClientGameTestContext context, String name) {
		if (!name.equals("dragging") && !name.equals("save_tooltip")) move(context, 1, 1);
		context.runOnClient(client -> {
			var screen = screen(client);
			List<Object> cards = hits(screen, "card");
			check(!cards.isEmpty() && cards.size() < 100, "only visible cards have hitboxes");
			for (Object card : cards) {
				check(number(card, "y") >= number(screen, "listTop")
						&& number(card, "y") + number(card, "h") <= number(screen, "listBottom"), "hitboxes clipped to viewport");
			}
		});
		context.takeScreenshot("tactics_300_" + name);
	}

	private static void measure(ClientGameTestContext context, String position) {
		long[] full = new long[FRAMES];
		long[] extract = new long[FRAMES];
		long[] allocation = new long[FRAMES];
		long[] sample = new long[3];
		var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		for (int frame = -120; frame < FRAMES; frame++) {
			if (frame % 3 == 0) context.waitTick();
			context.runOnClient(client -> {
				check(client.screen instanceof GuardTacticsScreen, "tactics remains open during timing");
				GL11.glFinish();
				long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
				long start = System.nanoTime();
				client.gameRenderer.extract(DeltaTracker.ONE, true);
				long extracted = System.nanoTime();
				client.gameRenderer.render(DeltaTracker.ONE, true);
				GL11.glFinish();
				sample[0] = System.nanoTime() - start;
				sample[1] = extracted - start;
				sample[2] = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
			});
			if (frame >= 0) {
				full[frame] = sample[0];
				extract[frame] = sample[1];
				allocation[frame] = sample[2];
			}
		}
		Arrays.sort(full);
		Arrays.sort(extract);
		System.out.printf(Locale.ROOT,
				"GUARD_TACTICS_PERF guards=300 position=%s frames=%d mean_ms=%.3f median_ms=%.3f p95_ms=%.3f extract_median_ms=%.3f allocated_kib=%.1f%n",
				position, FRAMES, Arrays.stream(full).average().orElseThrow() / 1e6, full[FRAMES / 2] / 1e6,
				full[(int) (FRAMES * .95)] / 1e6, extract[FRAMES / 2] / 1e6, Arrays.stream(allocation).average().orElseThrow() / 1024);
	}

	private static void move(ClientGameTestContext context, int x, int y) {
		double scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
		context.getInput().setCursorPos(x * scale, y * scale);
		context.waitTick();
	}

	private static GuardTacticsScreen screen(Minecraft client) {
		return (GuardTacticsScreen) client.screen;
	}

	private static List<ClientGuardRosterStore.GuardRosterEntry> roster(Minecraft client) {
		return ClientGuardRosterStore.getInstance().roster(ClientTacticsDataStore.resolveContext(client, client.level));
	}

	private static List<Object> hits(Object screen, String kind) {
		List<Object> hits = field(screen, kind + "Hits");
		return hits.subList(0, number(screen, kind + "HitCount"));
	}

	private static int number(Object target, String name) {
		return (Integer) field(target, name);
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object target, String name) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(target);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Screen fixture no longer matches " + name, exception);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record Settings(boolean vsync, int limit, InactivityFpsLimit inactivity) {}
	private record DragStart(UUID guard, int x, int y) {}
}
