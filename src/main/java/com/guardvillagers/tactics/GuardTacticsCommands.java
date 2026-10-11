package com.guardvillagers.tactics;

import com.guardvillagers.network.GuardTacticsCommandPayload;
import com.guardvillagers.network.GuardTacticsAckPayload;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread queue for the small set of commands exposed by the tactics UI. */
public final class GuardTacticsCommands {
	private static final int MAX_PENDING = 256;
	private static final Map<UUID, ArrayDeque<String>> PENDING = new HashMap<>();

	private GuardTacticsCommands() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(GuardTacticsCommandPayload.ID, GuardTacticsCommandPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GuardTacticsAckPayload.ID, GuardTacticsAckPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(GuardTacticsCommandPayload.ID,
				(payload, context) -> enqueue(context.player(), payload.command()));
		ServerTickEvents.END_SERVER_TICK.register(GuardTacticsCommands::tick);
		ServerPlayerEvents.LEAVE.register(player -> PENDING.remove(player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
	}

	public static boolean enqueue(ServerPlayer player, String command) {
		if (!isAllowedCommand(command)) {
			player.sendSystemMessage(Component.translatable("commands.guardvillagers.tactics.invalid_action"));
			acknowledge(player);
			return false;
		}
		var queue = PENDING.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
		// Bound work arriving on this channel now that vanilla's chat budget no longer applies.
		if (queue.size() >= MAX_PENDING) {
			player.sendSystemMessage(Component.translatable("commands.guardvillagers.tactics.queue_full"));
			acknowledge(player);
			return false;
		}
		queue.addLast(command);
		return true;
	}

	static boolean isAllowedCommand(String command) {
		return GuardTacticsCommandPayload.isAllowed(command);
	}

	private static void acknowledge(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, GuardTacticsAckPayload.ID)) {
			ServerPlayNetworking.send(player, new GuardTacticsAckPayload());
		}
	}

	private static void tick(MinecraftServer server) {
		var iterator = PENDING.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				iterator.remove();
				continue;
			}
			String command = entry.getValue().removeFirst();
			if (entry.getValue().isEmpty()) {
				iterator.remove();
			}
			// Execute as the sender, preserving Brigadier's argument, permission and ownership checks.
			try {
				server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
			} finally {
				acknowledge(player);
			}
		}
	}
}
