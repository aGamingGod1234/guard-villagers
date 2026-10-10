package com.guardvillagers.command.commander;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.entity.GuardEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Short commander lines to the owner: chat plus action bar always, and voice through {@link CalloutSpeaker} when one is
 * registered. Order acknowledgements go out at once; announcements (threats, raids, wounded, area clear) are rate
 * limited to one per {@link #ANNOUNCE_INTERVAL_TICKS} per owner, a waiting announcement is replaced by a more important
 * one, and the same line is not repeated within {@link #REPEAT_WINDOW_TICKS}.
 */
public final class CommanderCallouts {
	public static final int MAX_LINE_LENGTH = 120;
	public static final int ANNOUNCE_INTERVAL_TICKS = 80;
	public static final int REPEAT_WINDOW_TICKS = 600;
	private static final int HISTORY = 16;

	public enum Kind {
		/** Reply to an order or question. Never rate limited. */
		ACK,
		/** Answer to "status". Never rate limited. */
		STATUS,
		/** Change of command. Never rate limited. */
		SUCCESSION,
		/** Commander's own decisions and sightings. Rate limited. */
		ANNOUNCE
	}

	private record Pending(String line, int priority) {
	}

	private static final class OwnerLog {
		private long lastAnnounceTick = Long.MIN_VALUE / 2;
		private Pending pending;
		private final Map<String, Long> lastSaid = new HashMap<>();
		private final Deque<String> history = new ArrayDeque<>();
	}

	private static final Map<UUID, OwnerLog> LOGS = new ConcurrentHashMap<>();
	private static volatile CalloutSpeaker speaker = CalloutSpeaker.NONE;

	private CommanderCallouts() {
	}

	/** Registers the voice output; pass {@code null} or {@link CalloutSpeaker#NONE} to go back to chat only. */
	public static void setSpeaker(CalloutSpeaker newSpeaker) {
		speaker = newSpeaker == null ? CalloutSpeaker.NONE : newSpeaker;
	}

	public static CalloutSpeaker speaker() {
		return speaker;
	}

	/** Sends an acknowledgement, status or succession line now. */
	public static void say(MinecraftServer server, UUID owner, String line, Kind kind) {
		deliver(server, owner, line, kind, log(owner), server.getTickCount());
	}

	/**
	 * Queues an announcement. Sent now when the owner's announcement slot is free and the line was not said recently;
	 * otherwise it waits for {@link #flush}, unless a more important line is already waiting.
	 */
	public static void announce(MinecraftServer server, UUID owner, String line, int priority) {
		OwnerLog log = log(owner);
		long now = server.getTickCount();
		Long last = log.lastSaid.get(line);
		if (last != null && now - last < REPEAT_WINDOW_TICKS) {
			return;
		}
		if (now - log.lastAnnounceTick >= ANNOUNCE_INTERVAL_TICKS) {
			log.lastAnnounceTick = now;
			deliver(server, owner, line, Kind.ANNOUNCE, log, now);
			return;
		}
		if (log.pending == null || priority >= log.pending.priority()) {
			log.pending = new Pending(line, priority);
		}
	}

	/** Sends a waiting announcement once the owner's slot frees up. Called from the commander's tick. */
	public static void flush(MinecraftServer server, UUID owner) {
		OwnerLog log = LOGS.get(owner);
		if (log == null || log.pending == null) {
			return;
		}
		long now = server.getTickCount();
		if (now - log.lastAnnounceTick < ANNOUNCE_INTERVAL_TICKS) {
			return;
		}
		Pending pending = log.pending;
		log.pending = null;
		Long last = log.lastSaid.get(pending.line());
		if (last != null && now - last < REPEAT_WINDOW_TICKS) {
			return;
		}
		log.lastAnnounceTick = now;
		deliver(server, owner, pending.line(), Kind.ANNOUNCE, log, now);
	}

	/** Last lines sent to this owner, oldest first (for tests and {@code /guards status}). */
	public static List<String> recent(UUID owner) {
		OwnerLog log = LOGS.get(owner);
		return log == null ? List.of() : new ArrayList<>(log.history);
	}

	public static void forget(UUID owner) {
		LOGS.remove(owner);
	}

	public static void clearSessionState() {
		LOGS.clear();
	}

	/** Lang lookup with an English fallback, so the same text works for chat and speech. */
	public static String text(String key, String fallback, Object... args) {
		String pattern = Language.getInstance().getOrDefault(key, fallback);
		try {
			return String.format(pattern, args);
		} catch (java.util.IllegalFormatException exception) {
			return pattern;
		}
	}

	private static OwnerLog log(UUID owner) {
		return LOGS.computeIfAbsent(owner, ignored -> new OwnerLog());
	}

	private static void deliver(MinecraftServer server, UUID owner, String rawLine, Kind kind, OwnerLog log, long now) {
		String line = rawLine.length() > MAX_LINE_LENGTH ? rawLine.substring(0, MAX_LINE_LENGTH) : rawLine;
		log.lastSaid.put(line, now);
		if (log.lastSaid.size() > 64) {
			log.lastSaid.values().removeIf(tick -> now - tick >= REPEAT_WINDOW_TICKS);
		}
		log.history.addLast(line);
		while (log.history.size() > HISTORY) {
			log.history.removeFirst();
		}
		ServerPlayer player = server.getPlayerList().getPlayer(owner);
		if (player == null) {
			return;
		}
		GuardEntity commander = GuardHierarchy.commander(server, owner).orElse(null);
		String speakerName = commander != null ? commander.getName().getString() : "Guards";
		player.sendSystemMessage(Component.literal("[" + speakerName + "] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(line).withStyle(ChatFormatting.YELLOW)));
		player.sendOverlayMessage(Component.literal(line));
		try {
			speaker.speak(player, commander, line, kind);
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.warn("Commander speech failed: {}", exception.toString());
		}
	}
}
