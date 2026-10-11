package com.guardvillagers.gametest.client;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.client.ClientDebugState;
import com.guardvillagers.client.GuardEntityRenderer.GuardRenderState;
import com.guardvillagers.command.hierarchy.GuardRank;
import com.guardvillagers.entity.GuardEntity;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.DeltaTracker;
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
	private static final int WARMUP = 120;
	private static final int FRAMES = 360;

	static void run(ClientGameTestContext context) {
		try (TestSingleplayerContext world = ClientTestSupport.createWorld(context)) {
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
				context.takeScreenshot("scale_300_debug_" + (debug ? "on" : "off"));
			}
		} finally {
			context.runOnClient(client -> ClientDebugState.reset());
		}
	}

	private static void equip(GuardEntity guard, EquipmentSlot slot, Item item) {
		guard.setItemSlot(slot, new ItemStack(item));
	}

	private static void measure(ClientGameTestContext context, boolean debug) {
		long[] nanos = new long[FRAMES];
		for (int frame = -WARMUP; frame < FRAMES; frame++) {
			// Three rendered frames per game tick exercises both cached and changed state at about 60 FPS.
			if (frame % 3 == 0) context.waitTick();
			long elapsed = context.computeOnClient(client -> {
				GL11.glFinish();
				long start = System.nanoTime();
				client.gameRenderer.extract(DeltaTracker.ONE, true);
				long guards = client.gameRenderer.getGameRenderState().levelRenderState.entityRenderStates.stream()
						.filter(state -> state instanceof GuardRenderState).count();
				client.gameRenderer.render(DeltaTracker.ONE, true);
				GL11.glFinish();
				long duration = System.nanoTime() - start;
				if (guards != GUARDS) throw new AssertionError("Expected 300 guards in view, rendered " + guards);
				return duration;
			});
			if (frame >= 0) nanos[frame] = elapsed;
		}
		Arrays.sort(nanos);
		System.out.printf(Locale.ROOT, "GUARD_CLIENT_PERF guards=%d debug=%s frames=%d mean_ms=%.3f median_ms=%.3f p95_ms=%.3f%n",
				GUARDS, debug, FRAMES, Arrays.stream(nanos).average().orElseThrow() / 1e6,
				nanos[FRAMES / 2] / 1e6, nanos[(int) (FRAMES * .95)] / 1e6);
	}
}
