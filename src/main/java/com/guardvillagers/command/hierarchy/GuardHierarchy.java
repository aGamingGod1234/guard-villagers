package com.guardvillagers.command.hierarchy;

import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.data.GuardCommandState;
import com.guardvillagers.entity.GuardEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Chain of command for owned guards: one commander per owner, one captain per tactics group with at least two loaded
 * members, everyone else a soldier. Ranks are deterministic ({@link GuardSeniority}), stable (only a strictly higher
 * level unseats a rank holder) and can be pinned by appointment. Elections run every {@link #ELECTION_INTERVAL_TICKS}
 * per online owner and at the end of the tick after any hire, death, unload, group change or appointment.
 *
 * <p>Server thread only, except {@link #requestElection} which may be called from anywhere.
 */
public final class GuardHierarchy {
	public static final int ELECTION_INTERVAL_TICKS = 100;
	/** A fallen commander is succeeded by the most senior captain this close to the owner, if there is one. */
	public static final double SUCCESSION_RADIUS = 64.0D;

	/** What changed at the top, for callouts. */
	public enum ChangeKind {
		/** First commander for this owner (or after every guard was gone). */
		FIRST,
		/** The previous commander died. */
		FELL,
		/** The previous commander left the loaded world (unload, dimension change). */
		LEFT,
		/** A more senior guard took over. */
		PROMOTED,
		/** The player appointed this commander. */
		APPOINTED
	}

	public record CommandChange(UUID owner, ChangeKind kind, String previousName, GuardEntity commander, boolean wasCaptain) {
	}

	@FunctionalInterface
	public interface Listener {
		void onCommandChange(MinecraftServer server, CommandChange change);
	}

	public enum AppointResult {
		APPOINTED,
		NOT_OWNED,
		NO_GROUP
	}

	private record LostCommander(String name, boolean died) {
	}

	private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, LostCommander> LOST_COMMANDERS = new ConcurrentHashMap<>();
	private static final Map<UUID, ChangeKind> FORCED_KINDS = new ConcurrentHashMap<>();
	private static volatile Listener listener = (server, change) -> {
	};

	private GuardHierarchy() {
	}

	public static void setListener(Listener newListener) {
		listener = newListener == null ? (server, change) -> {
		} : newListener;
	}

	public static void requestElection(UUID owner) {
		if (owner != null) {
			PENDING.add(owner);
		}
	}

	/** Called by {@link GuardEntity} when an owned guard dies ({@code died}) or leaves the loaded world. */
	public static void onGuardLost(GuardEntity guard, boolean died) {
		UUID owner = guard.getOwnerUuid();
		if (owner == null || !(guard.level() instanceof net.minecraft.server.level.ServerLevel level)) {
			return;
		}
		GuardCommandState.OwnerCommand command = peek(level.getServer(), owner);
		if (command != null && died) {
			command.unpin(guard.getUUID());
			state(level.getServer()).setDirty();
		}
		if (guard.getRank() == GuardRank.COMMANDER || (command != null && guard.getUUID().equals(command.commander()))) {
			LOST_COMMANDERS.put(owner, new LostCommander(guard.getName().getString(), died));
		}
		requestElection(owner);
	}

	/** Runs pending and periodic elections. Called once per server tick. */
	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (Math.floorMod(now + (player.getUUID().hashCode() & 0xFFFF), ELECTION_INTERVAL_TICKS) == 0) {
				PENDING.add(player.getUUID());
			}
		}
		if (PENDING.isEmpty()) {
			return;
		}
		List<UUID> owners = new ArrayList<>(PENDING);
		PENDING.clear();
		for (UUID owner : owners) {
			elect(server, owner);
		}
	}

	public static void clearSessionState() {
		PENDING.clear();
		LOST_COMMANDERS.clear();
		FORCED_KINDS.clear();
	}

	/** Re-assigns ranks for one owner's loaded guards. */
	public static void elect(MinecraftServer server, UUID owner) {
		List<GuardEntity> guards = livingGuards(server, owner);
		if (guards.isEmpty()) {
			// Keep the stored UUIDs; the next load re-validates them. The loss being reported is over: a later
			// election must not announce it as news.
			LOST_COMMANDERS.remove(owner);
			FORCED_KINDS.remove(owner);
			return;
		}
		GuardCommandState state = state(server);
		GuardCommandState.OwnerCommand command = state.getOrCreate(owner);
		UUID previousCommanderId = command.commander();
		GuardEntity previousCommander = find(guards, previousCommanderId);

		GuardEntity commander = chooseCommander(server, owner, guards, command, previousCommander);
		Map<Integer, UUID> captains = chooseCaptains(guards, command, commander);

		boolean changed = false;
		boolean commanderWasCaptain = commander.getRank() == GuardRank.CAPTAIN;
		for (GuardEntity guard : guards) {
			GuardRank wanted = guard == commander ? GuardRank.COMMANDER
					: captains.containsValue(guard.getUUID()) ? GuardRank.CAPTAIN : GuardRank.SOLDIER;
			if (guard.getRank() != wanted) {
				guard.setRank(wanted);
			}
		}
		UUID commanderId = commander.getUUID();
		if (!commanderId.equals(previousCommanderId)) {
			command.setCommander(commanderId);
			changed = true;
			announce(server, owner, previousCommanderId, previousCommander, commander, commanderWasCaptain);
		}
		if (!captains.equals(command.captainByGroup())) {
			command.setCaptains(captains);
			changed = true;
		}
		LOST_COMMANDERS.remove(owner);
		FORCED_KINDS.remove(owner);
		if (changed) {
			state.setDirty();
		}
	}

	public static Optional<GuardEntity> commander(MinecraftServer server, UUID owner) {
		GuardCommandState.OwnerCommand command = peek(server, owner);
		if (command == null || command.commander() == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(find(livingGuards(server, owner), command.commander()));
	}

	/** Commander among an already resolved roster (no extra lookups). */
	public static GuardEntity commanderAmong(MinecraftServer server, UUID owner, List<GuardEntity> guards) {
		GuardCommandState.OwnerCommand command = peek(server, owner);
		if (command == null || command.commander() == null) {
			return null;
		}
		GuardEntity found = find(guards, command.commander());
		return found != null && found.isAlive() ? found : null;
	}

	public static Optional<GuardEntity> captain(MinecraftServer server, UUID owner, int group) {
		GuardCommandState.OwnerCommand command = peek(server, owner);
		if (command == null) {
			return Optional.empty();
		}
		UUID id = command.captainByGroup().get(group);
		return id == null ? Optional.empty() : Optional.ofNullable(find(livingGuards(server, owner), id));
	}

	public static AppointResult appointCommander(MinecraftServer server, UUID owner, GuardEntity guard) {
		if (!guard.isOwnedBy(owner) || !guard.isAlive()) {
			return AppointResult.NOT_OWNED;
		}
		GuardCommandState state = state(server);
		GuardCommandState.OwnerCommand command = state.getOrCreate(owner);
		command.unpin(guard.getUUID());
		command.setPinnedCommander(guard.getUUID());
		state.setDirty();
		FORCED_KINDS.put(owner, ChangeKind.APPOINTED);
		elect(server, owner);
		return AppointResult.APPOINTED;
	}

	public static AppointResult appointCaptain(MinecraftServer server, UUID owner, GuardEntity guard) {
		if (!guard.isOwnedBy(owner) || !guard.isAlive()) {
			return AppointResult.NOT_OWNED;
		}
		if (guard.getGroupIndex() < 0) {
			return AppointResult.NO_GROUP;
		}
		GuardCommandState state = state(server);
		GuardCommandState.OwnerCommand command = state.getOrCreate(owner);
		// One pinned captain per group: the new appointment replaces any other pin in the same group.
		for (GuardEntity other : livingGuards(server, owner)) {
			if (other != guard && other.getGroupIndex() == guard.getGroupIndex()) {
				command.unpin(other.getUUID());
			}
		}
		command.unpin(guard.getUUID());
		if (!command.pinCaptain(guard.getUUID())) {
			// Full of pins, which means some belong to guards that are not loaded (loaded pins are one per group).
			// Those go first so the appointment the player just made always takes.
			Set<UUID> loaded = new java.util.HashSet<>();
			for (GuardEntity other : livingGuards(server, owner)) {
				loaded.add(other.getUUID());
			}
			for (UUID pinned : List.copyOf(command.pinnedCaptains())) {
				if (!loaded.contains(pinned)) {
					command.unpin(pinned);
				}
			}
			command.pinCaptain(guard.getUUID());
		}
		state.setDirty();
		elect(server, owner);
		return AppointResult.APPOINTED;
	}

	/**
	 * Drops {@code guard}'s appointments from {@code formerOwner}'s chain of command. For a guard that stops serving
	 * that owner other than by dying (dismissed, discarded, given to another player); deaths already unpin.
	 */
	public static void onGuardLeftOwner(MinecraftServer server, UUID formerOwner, UUID guard) {
		if (formerOwner == null || guard == null) {
			return;
		}
		GuardCommandState.OwnerCommand command = peek(server, formerOwner);
		if (command != null && (guard.equals(command.pinnedCommander()) || command.pinnedCaptains().contains(guard))) {
			command.unpin(guard);
			state(server).setDirty();
		}
		requestElection(formerOwner);
	}

	/** Drops every appointment; ranks go back to pure seniority on the spot. */
	public static void clearAppointments(MinecraftServer server, UUID owner) {
		GuardCommandState state = state(server);
		GuardCommandState.OwnerCommand command = state.getOrCreate(owner);
		command.clearPins();
		state.setDirty();
		elect(server, owner);
	}

	public static boolean isPinned(MinecraftServer server, UUID owner, UUID guard) {
		GuardCommandState.OwnerCommand command = peek(server, owner);
		return command != null && (guard.equals(command.pinnedCommander()) || command.pinnedCaptains().contains(guard));
	}

	public static GuardCommandState state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(GuardCommandState.TYPE);
	}

	// ---- election rules ----

	private static GuardEntity chooseCommander(MinecraftServer server, UUID owner, List<GuardEntity> guards,
			GuardCommandState.OwnerCommand command, GuardEntity incumbent) {
		GuardEntity pinned = find(guards, command.pinnedCommander());
		if (pinned != null) {
			return pinned;
		}
		Set<UUID> pinnedCaptains = command.pinnedCaptains();
		// Pinned captains are skipped only while someone unpinned is left; count the loaded ones, since pins for
		// unloaded guards would otherwise make that test fail and hand command to a pinned captain.
		int loadedPinned = 0;
		for (GuardEntity guard : guards) {
			if (pinnedCaptains.contains(guard.getUUID())) {
				loadedPinned++;
			}
		}
		GuardEntity mostSenior = null;
		for (GuardEntity guard : guards) {
			if (pinnedCaptains.contains(guard.getUUID()) && guards.size() > loadedPinned) {
				continue;
			}
			if (mostSenior == null || GuardSeniority.ORDER.compare(guard, mostSenior) > 0) {
				mostSenior = guard;
			}
		}
		if (mostSenior == null) {
			mostSenior = guards.getFirst();
		}
		if (incumbent != null && !pinnedCaptains.contains(incumbent.getUUID())) {
			return GuardSeniority.outranksIncumbent(mostSenior, incumbent) ? mostSenior : incumbent;
		}
		// Succession: keep continuity by promoting the most senior captain near the owner.
		ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
		GuardEntity bestCaptain = null;
		if (ownerPlayer != null) {
			double radiusSq = SUCCESSION_RADIUS * SUCCESSION_RADIUS;
			for (GuardEntity guard : guards) {
				if (guard.getRank() != GuardRank.CAPTAIN || guard.level() != ownerPlayer.level()
						|| guard.distanceToSqr(ownerPlayer) > radiusSq) {
					continue;
				}
				if (bestCaptain == null || GuardSeniority.ORDER.compare(guard, bestCaptain) > 0) {
					bestCaptain = guard;
				}
			}
		}
		return bestCaptain != null ? bestCaptain : mostSenior;
	}

	private static Map<Integer, UUID> chooseCaptains(List<GuardEntity> guards, GuardCommandState.OwnerCommand command,
			GuardEntity commander) {
		Map<Integer, List<GuardEntity>> groups = new HashMap<>();
		for (GuardEntity guard : guards) {
			if (guard.getGroupIndex() >= 0 && guard.getGroupIndex() < GuardCommandState.MAX_CAPTAINS) {
				groups.computeIfAbsent(guard.getGroupIndex(), ignored -> new ArrayList<>()).add(guard);
			}
		}
		Map<Integer, UUID> captains = new HashMap<>();
		Set<UUID> pinned = command.pinnedCaptains();
		for (Map.Entry<Integer, List<GuardEntity>> entry : groups.entrySet()) {
			List<GuardEntity> members = entry.getValue();
			GuardEntity pinnedCaptain = null;
			GuardEntity mostSenior = null;
			for (GuardEntity member : members) {
				if (member == commander) {
					continue;
				}
				if (pinned.contains(member.getUUID())
						&& (pinnedCaptain == null || GuardSeniority.ORDER.compare(member, pinnedCaptain) > 0)) {
					pinnedCaptain = member;
				}
				if (mostSenior == null || GuardSeniority.ORDER.compare(member, mostSenior) > 0) {
					mostSenior = member;
				}
			}
			if (pinnedCaptain != null) {
				captains.put(entry.getKey(), pinnedCaptain.getUUID());
				continue;
			}
			if (members.size() < 2 || mostSenior == null) {
				continue;
			}
			GuardEntity incumbent = find(members, command.captainByGroup().get(entry.getKey()));
			if (incumbent != null && incumbent != commander && !GuardSeniority.outranksIncumbent(mostSenior, incumbent)) {
				captains.put(entry.getKey(), incumbent.getUUID());
			} else {
				captains.put(entry.getKey(), mostSenior.getUUID());
			}
		}
		return captains;
	}

	private static void announce(MinecraftServer server, UUID owner, UUID previousId, GuardEntity previous,
			GuardEntity commander, boolean wasCaptain) {
		LostCommander lost = LOST_COMMANDERS.get(owner);
		ChangeKind forced = FORCED_KINDS.get(owner);
		ChangeKind kind;
		String previousName = previous != null ? previous.getName().getString() : null;
		if (forced != null) {
			kind = forced;
		} else if (lost != null) {
			kind = lost.died() ? ChangeKind.FELL : ChangeKind.LEFT;
			previousName = lost.name();
		} else if (previousId == null) {
			kind = ChangeKind.FIRST;
		} else if (previous != null) {
			kind = ChangeKind.PROMOTED;
		} else {
			kind = ChangeKind.LEFT;
		}
		try {
			listener.onCommandChange(server, new CommandChange(owner, kind, previousName, commander, wasCaptain));
		} catch (RuntimeException exception) {
			com.guardvillagers.GuardVillagersMod.LOGGER.error("Commander change listener failed", exception);
		}
	}

	private static List<GuardEntity> livingGuards(MinecraftServer server, UUID owner) {
		List<GuardEntity> owned = GuardOwnershipIndex.getOwnedGuards(server, owner);
		List<GuardEntity> living = new ArrayList<>(owned.size());
		for (GuardEntity guard : owned) {
			if (guard.isAlive() && !guard.isRemoved()) {
				living.add(guard);
			}
		}
		return living;
	}

	private static GuardEntity find(List<GuardEntity> guards, UUID id) {
		if (id == null) {
			return null;
		}
		for (GuardEntity guard : guards) {
			if (guard.getUUID().equals(id)) {
				return guard;
			}
		}
		return null;
	}

	/** The owner's row if it exists; never creates one. */
	private static GuardCommandState.OwnerCommand peek(MinecraftServer server, UUID owner) {
		GuardCommandState state = state(server);
		return state.trackedOwnerCount() == 0 ? null : state.peek(owner);
	}
}
