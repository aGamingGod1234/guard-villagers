package com.guardvillagers;

import com.guardvillagers.data.GuardDiplomacyState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Set;
import java.util.UUID;

public final class GuardDiplomacyManager {
	private GuardDiplomacyManager() {
	}

	public static GuardDiplomacyState getState(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(GuardDiplomacyState.TYPE);
	}

	public static boolean toggleWhitelist(MinecraftServer server, UUID owner, UUID target) {
		return getState(server).toggleWhitelist(owner, target);
	}

	public static boolean toggleBlacklist(MinecraftServer server, UUID owner, UUID target) {
		return getState(server).toggleBlacklist(owner, target);
	}

	public static boolean isBlacklisted(MinecraftServer server, UUID owner, UUID target) {
		return getState(server).isBlacklisted(owner, target);
	}

	public static boolean isWhitelisted(MinecraftServer server, UUID owner, UUID target) {
		return getState(server).isWhitelisted(owner, target);
	}

	public static Set<UUID> getBlacklist(MinecraftServer server, UUID owner) {
		return getState(server).getBlacklist(owner);
	}

	public static Set<UUID> getWhitelist(MinecraftServer server, UUID owner) {
		return getState(server).getWhitelist(owner);
	}

	public static boolean canInteract(MinecraftServer server, UUID owner, UUID target) {
		GuardDiplomacyState state = getState(server);
		if (state.isBlacklisted(owner, target)) {
			return false;
		}
		Set<UUID> whitelist = state.getWhitelist(owner);
		if (!whitelist.isEmpty()) {
			return whitelist.contains(target);
		}
		return true;
	}

	public static void requestReview(ServerPlayerEntity requester) {
		MinecraftServer server = requester.getCommandSource().getServer();
		if (server == null) {
			return;
		}

		Set<UUID> blacklist = getBlacklist(server, requester.getUuid());
		if (blacklist.isEmpty()) {
			requester.sendMessage(Text.literal("No blacklisted players to review."), true);
			return;
		}

		StringBuilder listBuilder = new StringBuilder();
		for (UUID uuid : blacklist) {
			ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
			String name = online == null ? uuid.toString() : online.getName().getString();
			if (listBuilder.length() > 0) {
				listBuilder.append(", ");
			}
			listBuilder.append(name);
		}
		String list = listBuilder.toString();

		Text message = Text.literal("[Guard Review] " + requester.getName().getString() + " requested review: " + list);
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			PlayerConfigEntry entry = new PlayerConfigEntry(player.getUuid(), player.getName().getString());
			if (server.getPlayerManager().isOperator(entry)) {
				player.sendMessage(message, false);
			}
		}
		requester.sendMessage(Text.literal("Review request sent to online operators."), true);
	}
}
