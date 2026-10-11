package com.guardvillagers.gametest.client;

import com.guardvillagers.client.GuardEntityRenderer;
import com.guardvillagers.command.hierarchy.GuardRank;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/** Exercise cache invalidation through the installed entity renderer, on real tracked client guards. */
final class GuardRenderCacheChecks {
	static void run(ClientGameTestContext context, TestSingleplayerContext world, BlockPos home) {
		context.runOnClient(client -> {
			GuardEntity guard = guard(client);
			GuardEntityRenderer renderer = (GuardEntityRenderer) client.getEntityRenderDispatcher().getRenderer(guard);
			var dispatcher = client.getEntityRenderDispatcher();
			var oldPick = dispatcher.crosshairPickEntity;
			boolean hideGui = client.options.hideGui;
			try {
				client.options.hideGui = false;
				dispatcher.crosshairPickEntity = null;
				guard.setCustomName(Component.literal("Cache guard"));
				guard.setRank(GuardRank.COMMANDER);
				Component name = renderer.createRenderState(guard, 1).nameTag;
				check(name.getString().equals("★ Cache guard"), "commander marker/name");
				check(name == renderer.createRenderState(guard, 1).nameTag, "stable name Component reused");
				guard.setCustomName(Component.literal("Renamed guard"));
				guard.setRank(GuardRank.CAPTAIN);
				check(renderer.createRenderState(guard, 1).nameTag.getString().equals("» Renamed guard"), "rename/rank invalidation");
				dispatcher.crosshairPickEntity = guard;
				guard.setRole(GuardRole.BOWMAN);
				guard.setExperience(240);
				guard.setHealth(guard.getMaxHealth());
				Component stats = renderer.createRenderState(guard, 1).nameTag;
				check(stats.getString().startsWith("» Renamed guard  Lv 3 Bowman ❤ "), "focused stats content");
				check(stats == renderer.createRenderState(guard, 1).nameTag, "stable stats Component reused");
				checkColor(stats, ChatFormatting.GREEN);
				guard.setHealth(guard.getMaxHealth() * .5F);
				checkColor(renderer.createRenderState(guard, 1).nameTag, ChatFormatting.YELLOW);
				guard.setHealth(guard.getMaxHealth() * .2F);
				checkColor(renderer.createRenderState(guard, 1).nameTag, ChatFormatting.RED);
				guard.setRole(GuardRole.SWORDSMAN);
				guard.setRank(GuardRank.SOLDIER);
				check(renderer.createRenderState(guard, 1).nameTag.getString().startsWith("Renamed guard  Lv 3 Swordsman ❤ "), "role/soldier invalidation");
				client.options.hideGui = true;
				check(renderer.createRenderState(guard, 1).nameTag == null, "F1 hides focused stats");
				client.options.hideGui = false;
				dispatcher.crosshairPickEntity = null;
				check(renderer.createRenderState(guard, 1).nameTag.getString().equals("Renamed guard"), "focus loss removes stats");
				var scoreboard = client.level.getScoreboard();
				var team = scoreboard.addPlayerTeam("guard-cache-check");
				scoreboard.addPlayerToTeam(guard.getScoreboardName(), team);
				team.setPlayerPrefix(Component.literal("First "));
				check(renderer.createRenderState(guard, 1).nameTag.getString().equals("First Renamed guard"), "team formatting");
				team.setPlayerPrefix(Component.literal("Second "));
				check(renderer.createRenderState(guard, 1).nameTag.getString().equals("Second Renamed guard"), "team formatting update");
				scoreboard.removePlayerFromTeam(guard.getScoreboardName());
				check(renderer.createRenderState(guard, 1).nameTag.getString().equals("Renamed guard"), "team removal");
				checkRobe(renderer, guard, "plains");
			} finally {
				dispatcher.crosshairPickEntity = oldPick;
				client.options.hideGui = hideGui;
			}
		});
		world.getServer().runCommand("fillbiome " + (home.getX() - 8) + " " + (home.getY() - 4) + " " + (home.getZ() - 8)
				+ " " + (home.getX() + 8) + " " + (home.getY() + 4) + " " + (home.getZ() + 8) + " minecraft:desert");
		context.waitTicks(5);
		context.runOnClient(client -> {
			GuardEntity guard = guard(client);
			GuardEntityRenderer renderer = (GuardEntityRenderer) client.getEntityRenderDispatcher().getRenderer(guard);
			checkRobe(renderer, guard, "desert");
			guard.setHome(new BlockPos(600000, home.getY(), 600000));
			checkRobe(renderer, guard, "plains");
			guard.setHome(home);
			checkRobe(renderer, guard, "desert");
			guard.clearHome();
			checkRobe(renderer, guard, "plains");
		});
		System.out.println("GUARD_CLIENT_CACHE_CHECKS passed: name, rank, role, level, health colors, focus, F1, team formatting, biome update, home change");
	}

	private static GuardEntity guard(Minecraft client) {
		for (var entity : client.level.entitiesForRendering()) {
			if (entity instanceof GuardEntity guard) return guard;
		}
		throw new AssertionError("No tracked guard");
	}

	private static void checkRobe(GuardEntityRenderer renderer, GuardEntity guard, String biome) {
		check(renderer.createRenderState(guard, 1).typeTexture.getPath().equals("textures/entity/villager/type/" + biome + ".png"), "robe " + biome);
	}

	private static void checkColor(Component label, ChatFormatting color) {
		check(TextColor.fromLegacyFormat(color).equals(label.getSiblings().getLast().getStyle().getColor()), "health color " + color.name());
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
