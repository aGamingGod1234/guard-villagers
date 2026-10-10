package com.guardvillagers.client;

import java.util.ArrayDeque;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Sends UI-issued commands without tripping the server's chat spam kick.
 *
 * <p>Vanilla adds 20 to a per-player counter for every chat or command packet, removes 1 per tick, and
 * disconnects non-operators once it reaches 200. This queue mirrors that counter and holds commands back
 * while the estimate is near the limit, so a big save drains over a second or two instead of kicking the
 * player. It keeps running after the screen closes (the Save-and-close path relies on that).
 */
public final class GuardCommandQueue {
	private static final int SPAM_INCREMENT = 20;
	// Vanilla kicks at 200; stay well below to leave room for anything the player types.
	private static final int SPAM_BUDGET = 140;
	private static final int MAX_QUEUED = 256;

	private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
	private static int spamEstimate;
	private static boolean registered;

	private GuardCommandQueue() {
	}

	/** Queues a command (without the leading slash). Returns false when there is no connection to send it on. */
	public static boolean enqueue(String command) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return false;
		}
		ensureRegistered();
		if (QUEUE.size() < MAX_QUEUED) {
			QUEUE.addLast(command);
		}
		drain(client);
		return true;
	}

	/** Commands still waiting for spam budget. */
	public static int pending() {
		return QUEUE.size();
	}

	private static void ensureRegistered() {
		if (registered) {
			return;
		}
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (spamEstimate > 0) {
				spamEstimate--;
			}
			if (!QUEUE.isEmpty()) {
				drain(client);
			}
		});
	}

	private static void drain(Minecraft client) {
		if (client.getConnection() == null) {
			QUEUE.clear();
			spamEstimate = 0;
			return;
		}
		while (!QUEUE.isEmpty() && spamEstimate + SPAM_INCREMENT <= SPAM_BUDGET) {
			client.getConnection().sendCommand(QUEUE.removeFirst());
			spamEstimate += SPAM_INCREMENT;
		}
	}
}
