package com.guardvillagers.client;

import com.guardvillagers.network.GuardTacticsCommandPayload;
import java.util.ArrayDeque;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** FIFO UI operations, with one in flight until the server acknowledges processing it on a server tick. */
public final class GuardCommandQueue {
	private static final int MAX_QUEUED = 256;
	private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
	private static boolean inFlight;

	private GuardCommandQueue() {}

	public enum EnqueueResult { QUEUED, NO_CONNECTION, QUEUE_FULL }

	public static EnqueueResult enqueue(String command) {
		if (Minecraft.getInstance().getConnection() == null || !ClientPlayNetworking.canSend(GuardTacticsCommandPayload.ID)) {
			return EnqueueResult.NO_CONNECTION;
		}
		if (!GuardTacticsCommandPayload.isAllowed(command)) {
			throw new IllegalArgumentException("Not a tactics operation: " + command);
		}
		if (pending() >= MAX_QUEUED) {
			return EnqueueResult.QUEUE_FULL;
		}
		QUEUE.addLast(command);
		drain();
		return EnqueueResult.QUEUED;
	}

	public static int pending() {
		return QUEUE.size() + (inFlight ? 1 : 0);
	}

	public static void acknowledge() {
		inFlight = false;
		drain();
	}

	public static void clear() {
		QUEUE.clear();
		inFlight = false;
	}

	private static void drain() {
		if (!inFlight && !QUEUE.isEmpty()) {
			inFlight = true;
			ClientPlayNetworking.send(new GuardTacticsCommandPayload(QUEUE.removeFirst()));
		}
	}
}
