package com.guardvillagers.gametest.client;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.client.ClientDebugState;
import com.guardvillagers.client.GuardEntityRenderer.GuardRenderState;
import com.guardvillagers.command.hierarchy.GuardRank;
import com.guardvillagers.entity.GuardEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.lang.management.ManagementFactory;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.lwjgl.opengl.GL11;

/** Fixed scene, real extract/render entry points, with GPU completion included and tick waits excluded. */
final class GuardScaleClientBenchmark {
	private static final int GUARDS = 300;
	private static final int WARMUP = 240;
	private static final int FRAMES = 720;

	static void run(ClientGameTestContext context) {
		Settings saved = context.computeOnClient(client -> new Settings(client.options.enableVsync().get(),
				client.options.framerateLimit().get(), client.options.inactivityFpsLimit().get()));
		try (TestSingleplayerContext world = ClientTestSupport.createWorld(context)) {
			context.runOnClient(client -> {
				client.options.enableVsync().set(false);
				client.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
				client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
				client.getFramerateLimitTracker().onInputReceived();
				int effectiveLimit = client.getFramerateLimitTracker().getFramerateLimit();
				if (client.options.enableVsync().get() || effectiveLimit != Options.UNLIMITED_FRAMERATE_CUTOFF) {
					throw new AssertionError("Benchmark is throttled: " + effectiveLimit);
				}
				client.gameRenderer.getGameRenderState().framerateLimit = effectiveLimit;
				System.out.printf("GUARD_CLIENT_SETTINGS vsync=%s limit=%d throttle=%s gpu=%s size=%dx%d%n",
						client.options.enableVsync().get(), effectiveLimit, client.getFramerateLimitTracker().getThrottleReason(),
						GL11.glGetString(GL11.GL_RENDERER), client.getMainRenderTarget().width, client.getMainRenderTarget().height);
			});
			BlockPos feet = ClientTestSupport.playerFeet(world);
			world.getServer().runCommand("gamerule minecraft:advance_time false");
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			world.getServer().runOnServer(server -> {
				ClientTestSupport.player(server).setGameMode(GameType.SPECTATOR);
				for (int i = 0; i < GUARDS; i++) {
					GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(server.overworld(), EntitySpawnReason.MOB_SUMMONED);
					if (guard == null) throw new AssertionError("Cannot create guard");
					guard.setUUID(new UUID(0x4755415244L, i + 1));
					guard.snapTo(feet.getX() + (i % 20 - 9.5) * 1.5, feet.getY(), feet.getZ() + i / 20 * 1.5, 180, 0);
					guard.setYHeadRot(180);
					guard.yBodyRot = 180;
					guard.setNoAi(true);
					guard.setPersistenceRequired();
					guard.setHome(feet);
					guard.setCustomName(Component.literal("Guard " + (i + 1)));
					guard.setCustomNameVisible(true);
					guard.setRank(i % 20 == 0 ? GuardRank.COMMANDER : i % 5 == 0 ? GuardRank.CAPTAIN : GuardRank.SOLDIER);
					for (EquipmentSlot slot : EquipmentSlot.values()) guard.setItemSlot(slot, ItemStack.EMPTY);
					if (i % 6 != 0) equip(guard, EquipmentSlot.MAINHAND, i % 3 == 0 ? Items.BOW : Items.IRON_SWORD);
					if (i % 3 == 1) equip(guard, EquipmentSlot.OFFHAND, Items.SHIELD);
					if (i % 6 >= 4) {
						boolean diamond = i % 6 == 5;
						equip(guard, EquipmentSlot.HEAD, diamond ? Items.DIAMOND_HELMET : Items.IRON_HELMET);
						equip(guard, EquipmentSlot.CHEST, diamond ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE);
						equip(guard, EquipmentSlot.LEGS, diamond ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS);
						equip(guard, EquipmentSlot.FEET, diamond ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS);
					}
					if (!server.overworld().addFreshEntity(guard)) throw new AssertionError("Cannot spawn guard " + i);
				}
			});
			ClientTestSupport.placeCamera(context, world, feet.getX(), feet.getY() + 19, feet.getZ() - 25, 0, 27);
			world.getClientLevel().waitForChunksRender();
			ClientTestSupport.clearOverlays(context);
			context.waitTicks(30);
			for (boolean debug : new boolean[]{false, true}) {
				context.runOnClient(client -> ClientDebugState.update(debug, 96));
				measure(context, debug);
				measureCpu(context, debug);
				context.runOnClient(client -> {
					// Keep idle arm animation at the same phase in before/after screenshots.
					for (var entity : client.level.entitiesForRendering()) {
						if (entity instanceof GuardEntity) entity.tickCount = 100;
					}
				});
				context.takeScreenshot("scale_300_debug_" + (debug ? "on" : "off"));
			}
			captureProfiles(context);
			context.runOnClient(client -> GuardDebugLayoutChecks.run());
			// The reference renderer deliberately lacks Component reuse; its screenshot suite and layout checks still run.
			if (!Boolean.getBoolean("guardvillagers.clientPerfReference")) {
				GuardRenderCacheChecks.run(context, world, feet);
				context.runOnClient(GuardSkinMeshChecks::run);
			}
		} finally {
			context.runOnClient(client -> {
				ClientDebugState.reset();
				client.options.enableVsync().set(saved.vsync());
				client.options.framerateLimit().set(saved.limit());
				client.options.inactivityFpsLimit().set(saved.inactivity());
			});
		}
	}

	private record Settings(boolean vsync, int limit, InactivityFpsLimit inactivity) {}

	/** Profile real rendering only after all timed samples, so JFR does not affect the comparison. */
	private static void captureProfiles(ClientGameTestContext context) {
		if (!Boolean.getBoolean("guardvillagers.clientPerfJfr")) return;
		for (boolean debug : new boolean[]{false, true}) {
			context.runOnClient(client -> ClientDebugState.update(debug, 96));
			try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
				recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
				recording.start();
				for (int frame = 0; frame < 480; frame++) {
					if (frame % 3 == 0) context.waitTick();
					context.runOnClient(client -> {
						client.gameRenderer.extract(DeltaTracker.ONE, true);
						client.gameRenderer.render(DeltaTracker.ONE, true);
						GL11.glFinish();
					});
				}
				recording.stop();
				recording.dump(Path.of("../../client-perf-debug-" + debug + ".jfr"));
			} catch (java.io.IOException | java.text.ParseException exception) {
				throw new AssertionError("Cannot record client profile", exception);
			}
		}
	}

	private static void equip(GuardEntity guard, EquipmentSlot slot, Item item) {
		guard.setItemSlot(slot, new ItemStack(item));
	}

	private static void measure(ClientGameTestContext context, boolean debug) {
		long[] nanos = new long[FRAMES];
		long[] extraction = new long[FRAMES];
		long[] allocation = new long[FRAMES];
		long[] sample = new long[2];
		var threadBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		for (int frame = -WARMUP; frame < FRAMES; frame++) {
			// Fixed tick cadence exercises cached and changed state identically in both versions; no FPS pacing.
			if (frame % 3 == 0) context.waitTick();
			long elapsed = context.computeOnClient(client -> {
				GL11.glFinish();
				long threadId = Thread.currentThread().threadId();
				long allocated = threadBean.getThreadAllocatedBytes(threadId);
				long start = System.nanoTime();
				client.gameRenderer.extract(DeltaTracker.ONE, true);
				sample[0] = System.nanoTime() - start;
				long countStart = System.nanoTime();
				long guards = client.gameRenderer.getGameRenderState().levelRenderState.entityRenderStates.stream()
						.filter(state -> state instanceof GuardRenderState).count();
				long countDuration = System.nanoTime() - countStart;
				client.gameRenderer.render(DeltaTracker.ONE, true);
				GL11.glFinish();
				long duration = System.nanoTime() - start - countDuration;
				sample[1] = threadBean.getThreadAllocatedBytes(threadId) - allocated;
				if (guards != GUARDS) throw new AssertionError("Expected 300 guards in view, rendered " + guards);
				return duration;
			});
			if (frame >= 0) {
				nanos[frame] = elapsed;
				extraction[frame] = sample[0];
				allocation[frame] = sample[1];
			}
		}
		Arrays.sort(nanos);
		Arrays.sort(extraction);
		System.out.printf(Locale.ROOT, "GUARD_CLIENT_PERF guards=%d debug=%s frames=%d mean_ms=%.3f median_ms=%.3f p95_ms=%.3f extract_median_ms=%.3f allocated_kib=%.1f%n",
				GUARDS, debug, FRAMES, Arrays.stream(nanos).average().orElseThrow() / 1e6,
				nanos[FRAMES / 2] / 1e6, nanos[(int) (FRAMES * .95)] / 1e6, extraction[FRAMES / 2] / 1e6,
				Arrays.stream(allocation).average().orElseThrow() / 1024);
	}

	/** Real level extraction and guard submits only. Rendering/GL completion happen outside these timers. */
	private static void measureCpu(ClientGameTestContext context, boolean debug) {
		long[] extract = new long[FRAMES];
		long[] submit = new long[FRAMES];
		SubmitNodeStorage storage = new SubmitNodeStorage();
		PoseStack poses = new PoseStack();
		long[] sample = new long[2];
		for (int frame = -WARMUP; frame < FRAMES; frame++) {
			if (frame % 3 == 0) context.waitTick();
			context.runOnClient(client -> {
				GL11.glFinish();
				long start = System.nanoTime();
				client.gameRenderer.extract(DeltaTracker.ONE, true);
				long extracted = System.nanoTime();
				var state = client.gameRenderer.getGameRenderState().levelRenderState;
				var camera = state.cameraRenderState;
				int guards = 0;
				for (var entity : state.entityRenderStates) {
					if (entity instanceof GuardRenderState) {
						client.getEntityRenderDispatcher().submit(entity, camera,
								entity.x - camera.pos.x, entity.y - camera.pos.y, entity.z - camera.pos.z, poses, storage);
						guards++;
					}
				}
				sample[0] = extracted - start;
				sample[1] = System.nanoTime() - extracted;
				storage.clear();
				storage.endFrame();
				if (guards != GUARDS) throw new AssertionError("CPU probe rendered " + guards + " guards");
				// Consume this frame normally to keep render state, gizmos, and native buffers in their real lifecycle.
				client.gameRenderer.render(DeltaTracker.ONE, true);
				GL11.glFinish();
			});
			if (frame >= 0) {
				extract[frame] = sample[0];
				submit[frame] = sample[1];
			}
		}
		Arrays.sort(extract);
		Arrays.sort(submit);
		System.out.printf(Locale.ROOT,
				"GUARD_CLIENT_CPU guards=%d debug=%s frames=%d extract_mean_ms=%.3f extract_median_ms=%.3f extract_p95_ms=%.3f submit_mean_ms=%.3f submit_median_ms=%.3f submit_p95_ms=%.3f%n",
				GUARDS, debug, FRAMES, Arrays.stream(extract).average().orElseThrow() / 1e6,
				extract[FRAMES / 2] / 1e6, extract[(int) (FRAMES * .95)] / 1e6,
				Arrays.stream(submit).average().orElseThrow() / 1e6, submit[FRAMES / 2] / 1e6, submit[(int) (FRAMES * .95)] / 1e6);
	}
}
