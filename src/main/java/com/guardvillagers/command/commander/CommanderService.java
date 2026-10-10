package com.guardvillagers.command.commander;

import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardTacticsManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.command.language.Interpretation;
import com.guardvillagers.command.language.OrderInterpreter;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.ActiveOrder;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OrderBook;
import com.guardvillagers.command.order.OrderExecutor;
import com.guardvillagers.command.order.OrderSource;
import com.guardvillagers.command.order.OwnerRoster;
import com.guardvillagers.command.order.Scope;
import com.guardvillagers.data.GuardCommandState;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Entry point for player orders (voice, {@code /guards say}, chat prefix) and home of every owner's
 * {@link CommanderBrain}. One instance per server session, all static, server thread only.
 *
 * <p>Voice pipeline usage: hand a transcript to {@link #hear(ServerPlayer, String, boolean)} on the server thread, or,
 * if you already have orders, call {@link #submit(ServerPlayer, List, OrderSource)}.
 */
public final class CommanderService {
	/** How long the commander stays by the player after being addressed (when not told to "come here"). */
	public static final int REPORT_TICKS = 200;
	/** The commander stops this far from the player. */
	public static final double REPORT_DISTANCE = 2.3D;

	static final class OwnerContext {
		final UUID owner;
		final OrderBook book;
		final CommanderBrain brain;
		final Map<Long, UUID> attackTargets = new HashMap<>();
		Approach approach;

		OwnerContext(MinecraftServer server, UUID owner) {
			this.owner = owner;
			this.book = new OrderBook(() -> server.overworld().getGameTime());
			GuardCommandState.OwnerCommand saved = GuardHierarchy.state(server).peek(owner);
			if (saved != null) {
				this.book.restore(saved.orders());
			}
			this.brain = new CommanderBrain(owner);
		}
	}

	record Approach(GuardEntity guard, long until, BlockPos anchor) {
	}

	private static final Map<UUID, OwnerContext> CONTEXTS = new ConcurrentHashMap<>();
	private static volatile OrderInterpreter interpreter = OrderInterpreter.grammarOnly();
	private static long brainNanos;
	private static long brainTicks;

	private CommanderService() {
	}

	/** Replaces the interpreter (for example with an LLM-enabled one built from the voice config). */
	public static void setInterpreter(OrderInterpreter newInterpreter) {
		interpreter = newInterpreter == null ? OrderInterpreter.grammarOnly() : newInterpreter;
	}

	public static OrderInterpreter interpreter() {
		return interpreter;
	}

	// ---- order intake ----

	/**
	 * Text addressed to the guards ({@code /guards say}, chat prefix, a voice transcript). Interprets it and executes
	 * the orders; the LLM path, if any, completes later and hops back to the server thread.
	 *
	 * @param explicitlyAddressed true for channels that are always meant for guards (say command, chat prefix)
	 */
	public static void hear(ServerPlayer speaker, String text, boolean explicitlyAddressed) {
		MinecraftServer server = speaker.level().getServer();
		OwnerRoster roster = roster(speaker);
		OrderInterpreter current = interpreter;
		// Grammar first, offline. The LLM is only asked when the grammar found nothing in words addressed to the
		// guards, and only within the speaker's LLM budget.
		Interpretation local = current.interpretLocally(text, roster);
		boolean addressed = local.addressed() || explicitlyAddressed;
		if (local.understood() || !local.rejected().isEmpty() || !current.llmAvailable() || !addressed
				|| !VoiceCommandBridge.llmAllowed(speaker.getUUID())) {
			handle(speaker, new Interpretation(local.origin(), local.orders(), local.rejected(), addressed, local.reply(),
					local.llmStatus(), local.retryAfter()));
			return;
		}
		CompletableFuture<Interpretation> future = current.interpret(speaker.getUUID(), text, roster, explicitlyAddressed);
		if (future.isDone()) {
			handle(speaker, future.join());
			return;
		}
		UUID speakerId = speaker.getUUID();
		future.whenComplete((interpretation, error) -> server.execute(() -> {
			ServerPlayer online = server.getPlayerList().getPlayer(speakerId);
			if (online == null) {
				return;
			}
			if (error != null || interpretation == null) {
				CommanderCallouts.say(server, speakerId, CommanderCallouts.text("guardvillagers.order.llm_unavailable",
						"Commander couldn't hear you, try again."), CommanderCallouts.Kind.ACK);
				return;
			}
			handle(online, interpretation);
		}));
	}

	/** Applies an interpretation: orders, a clarifying reply, or "I did not catch that". */
	public static void handle(ServerPlayer speaker, Interpretation interpretation) {
		MinecraftServer server = speaker.level().getServer();
		if (!interpretation.understood()) {
			String line = null;
			if (interpretation.reply().isPresent()) {
				line = interpretation.reply().get();
			} else if (interpretation.llmTemporarilyUnavailable()) {
				line = CommanderCallouts.text("guardvillagers.order.llm_unavailable", "Commander couldn't hear you, try again.");
			} else if (interpretation.shouldSayNotUnderstood()) {
				line = CommanderCallouts.text("guardvillagers.order.not_understood", "I did not catch that.");
			}
			if (line != null) {
				CommanderCallouts.say(server, speaker.getUUID(), line, CommanderCallouts.Kind.ACK);
				reportTo(speaker, REPORT_TICKS);
			}
			return;
		}
		submit(speaker, interpretation.orders(), OrderSource.VOICE);
		interpretation.reply().ifPresent(reply -> CommanderCallouts.say(server, speaker.getUUID(), reply, CommanderCallouts.Kind.ACK));
	}

	/**
	 * Executes validated orders for the speaker's own guards and records them in the order book.
	 *
	 * @return number of guards reached
	 */
	public static int submit(ServerPlayer speaker, List<Order> orders, OrderSource source) {
		MinecraftServer server = speaker.level().getServer();
		UUID owner = speaker.getUUID();
		OwnerContext context = context(server, owner);
		List<GuardEntity> guards = livingGuards(server, owner);
		GuardEntity commander = GuardHierarchy.commanderAmong(server, owner, guards);
		int reached = 0;
		boolean commanderBusy = false;
		int reportTicks = REPORT_TICKS;
		for (Order order : orders) {
			OrderBook.IssueResult issued = context.book.issue(order, source);
			if (issued.status() == OrderBook.Status.OUTRANKED) {
				CommanderCallouts.say(server, owner, CommanderCallouts.text("guardvillagers.order.outranked",
						"Standing by: an earlier order still holds."), CommanderCallouts.Kind.ACK);
				continue;
			}
			if (issued.status() == OrderBook.Status.CLEARED) {
				for (ActiveOrder removed : issued.removed()) {
					OrderExecutor.undo(removed, guards, commander, false);
					context.attackTargets.remove(removed.id());
				}
			}
			for (ActiveOrder replaced : issued.status() == OrderBook.Status.STORED ? issued.removed() : List.<ActiveOrder>of()) {
				context.attackTargets.remove(replaced.id());
			}
			int ticks = (int) Math.min(Integer.MAX_VALUE, context.book.timedExpiryTicks());
			if (order.action() == Action.STATUS_REPORT) {
				CommanderCallouts.say(server, owner, context.brain.statusLine(server, speaker, guards, commander),
						CommanderCallouts.Kind.STATUS);
				continue;
			}
			OrderExecutor.Result result = OrderExecutor.execute(order, new OrderExecutor.Context(speaker, guards, commander, ticks));
			reached += result.affected();
			Optional<ActiveOrder> stored = issued.order();
			if (stored.isPresent()) {
				if (result.affected() == 0) {
					context.book.complete(stored.get().id());
				} else {
					OrderExecutor.targetId(result).ifPresent(id -> context.attackTargets.put(stored.get().id(), id));
				}
			}
			if (order.action() == Action.AS_YOU_WERE) {
				context.approach = null;
			}
			if (order.action() == Action.COME_HERE && commander != null
					&& (OrderExecutor.covers(order.scope(), commander, commander) || order.scope().kind() == Scope.Kind.ALL)) {
				reportTicks = ticks;
			}
			commanderBusy |= result.commanderObeys();
			if (!result.ack().isEmpty()) {
				CommanderCallouts.say(server, owner, result.ack(), CommanderCallouts.Kind.ACK);
			}
		}
		if (!commanderBusy) {
			reportTo(speaker, reportTicks);
		}
		saveOrders(server, context);
		context.brain.requestPlan();
		return reached;
	}

	/**
	 * A typed {@code /guards follow|stay|hold} (priority 100, a permanent state flip) replaces voice orders on the same
	 * scope, so the order book never fights the player's latest command.
	 */
	public static void onTypedOrder(ServerPlayer player, Scope scope) {
		MinecraftServer server = player.level().getServer();
		OwnerContext context = CONTEXTS.get(player.getUUID());
		if (context == null) {
			return;
		}
		for (ActiveOrder removed : context.book.clear(scope)) {
			context.attackTargets.remove(removed.id());
		}
		context.approach = null;
		saveOrders(server, context);
	}

	public static List<ActiveOrder> activeOrders(MinecraftServer server, UUID owner) {
		OwnerContext context = CONTEXTS.get(owner);
		return context == null ? List.of() : context.book.active();
	}

	public static boolean autoCommand(MinecraftServer server, UUID owner) {
		GuardCommandState.OwnerCommand command = GuardHierarchy.state(server).peek(owner);
		return command == null || command.autoCommand();
	}

	public static void setAutoCommand(MinecraftServer server, UUID owner, boolean enabled) {
		GuardCommandState state = GuardHierarchy.state(server);
		state.getOrCreate(owner).setAutoCommand(enabled);
		state.setDirty();
	}

	public static String statusLine(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		List<GuardEntity> guards = livingGuards(server, player.getUUID());
		GuardEntity commander = GuardHierarchy.commanderAmong(server, player.getUUID(), guards);
		return context(server, player.getUUID()).brain.statusLine(server, player, guards, commander);
	}

	/** Guard UUID to threat UUID as decided by the owner's last commander plan (auto rules only). */
	public static Map<UUID, UUID> lastAssignments(UUID owner) {
		OwnerContext context = CONTEXTS.get(owner);
		return context == null ? Map.of() : Map.copyOf(context.brain.assignments());
	}

	/** True while the commander treats this guard as wounded (below 30%, until healed past 50%). */
	public static boolean isWounded(UUID owner, UUID guard) {
		OwnerContext context = CONTEXTS.get(owner);
		return context != null && context.brain.isWounded(guard);
	}

	/** Mean server-thread nanoseconds the commander layer spent per server tick since the last reset. */
	public static double meanNanosPerTick() {
		return brainTicks == 0 ? 0.0D : (double) brainNanos / brainTicks;
	}

	public static void resetTiming() {
		brainNanos = 0L;
		brainTicks = 0L;
	}

	// ---- tick ----

	public static void tick(MinecraftServer server) {
		long start = System.nanoTime();
		try {
			GuardHierarchy.tick(server);
			long now = server.overworld().getGameTime();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				OwnerContext context = CONTEXTS.get(player.getUUID());
				if (context == null) {
					// Create lazily, on the brain's own cadence, only for players that own loaded guards.
					if (!CommanderBrain.isSnapshotTick(player.getUUID(), now)
							|| GuardOwnershipIndex.getOwnedGuards(server, player.getUUID()).isEmpty()) {
						continue;
					}
					context = context(server, player.getUUID());
				}
				if (Math.floorMod(now + CommanderBrain.phaseOf(player.getUUID()), GuardHierarchy.ELECTION_INTERVAL_TICKS) == 0) {
					VoiceCommandBridge.refreshPromptHint(player);
				}
				tickApproach(context, player, now);
				context.brain.tick(server, player, context, now);
				CommanderCallouts.flush(server, player.getUUID());
			}
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.error("Commander tick failed", exception);
		}
		brainNanos += System.nanoTime() - start;
		brainTicks++;
	}

	public static void forget(UUID owner) {
		CONTEXTS.remove(owner);
		CommanderCallouts.forget(owner);
		VoiceCommandBridge.forget(owner);
	}

	public static void clearSessionState() {
		CONTEXTS.clear();
		CommanderCallouts.clearSessionState();
		GuardHierarchy.clearSessionState();
		brainNanos = 0L;
		brainTicks = 0L;
	}

	public static void onCommandChange(MinecraftServer server, GuardHierarchy.CommandChange change) {
		String name = change.commander().getName().getString();
		String line = switch (change.kind()) {
			case FIRST -> CommanderCallouts.text("guardvillagers.commander.first", "%s takes command.", name);
			case FELL -> change.wasCaptain()
					? CommanderCallouts.text("guardvillagers.commander.fell_captain", "Commander %s has fallen. Captain %s takes command.",
							change.previousName(), name)
					: CommanderCallouts.text("guardvillagers.commander.fell", "Commander %s has fallen. %s takes command.",
							change.previousName(), name);
			case LEFT -> CommanderCallouts.text("guardvillagers.commander.left", "%s takes command.", name);
			case PROMOTED -> CommanderCallouts.text("guardvillagers.commander.promoted", "%s is promoted to commander.", name);
			case APPOINTED -> CommanderCallouts.text("guardvillagers.commander.appointed", "%s takes command, as ordered.", name);
		};
		CommanderCallouts.say(server, change.owner(), line, CommanderCallouts.Kind.SUCCESSION);
		OwnerContext context = CONTEXTS.get(change.owner());
		if (context != null) {
			context.approach = null;
			context.brain.requestPlan();
		}
	}

	// ---- commander reporting to the player ----

	/** Sends the commander to stand {@link #REPORT_DISTANCE} blocks from the player, facing them, for {@code ticks}. */
	static void reportTo(ServerPlayer player, int ticks) {
		MinecraftServer server = player.level().getServer();
		OwnerContext context = context(server, player.getUUID());
		GuardEntity commander = GuardHierarchy.commander(server, player.getUUID()).orElse(null);
		if (commander == null || commander.level() != player.level() || commander.isHoldingPosition()) {
			return;
		}
		long now = server.overworld().getGameTime();
		BlockPos post = reportPost(player, commander);
		commander.rallyTo(post, ticks, true);
		context.approach = new Approach(commander, now + ticks, player.blockPosition());
	}

	private static BlockPos reportPost(ServerPlayer player, GuardEntity commander) {
		Vec3 away = new Vec3(commander.getX() - player.getX(), 0.0D, commander.getZ() - player.getZ());
		if (away.lengthSqr() < 0.25D) {
			Vec3 look = player.getViewVector(1.0F);
			away = new Vec3(look.x, 0.0D, look.z);
		}
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(1.0D, 0.0D, 0.0D);
		}
		Vec3 post = player.position().add(away.normalize().scale(REPORT_DISTANCE));
		return BlockPos.containing(post.x, player.getY(), post.z);
	}

	private static void tickApproach(OwnerContext context, ServerPlayer player, long now) {
		Approach approach = context.approach;
		if (approach == null) {
			return;
		}
		GuardEntity guard = approach.guard();
		if (!guard.isAlive() || guard.isRemoved() || guard.level() != player.level() || now >= approach.until()
				|| guard.getRallyPoint().isEmpty()) {
			context.approach = null;
			return;
		}
		if ((now & 7L) == 0L && player.blockPosition().distSqr(approach.anchor()) > 4.0D) {
			guard.rallyTo(reportPost(player, guard), (int) (approach.until() - now), true);
			context.approach = new Approach(guard, approach.until(), player.blockPosition());
		}
		if (guard.distanceToSqr(player) < 16.0D && guard.getTarget() == null) {
			guard.getLookControl().setLookAt(player, 30.0F, 30.0F);
		}
	}

	static boolean isReporting(OwnerContext context, GuardEntity guard) {
		return context.approach != null && context.approach.guard() == guard;
	}

	// ---- helpers ----

	static OwnerContext context(MinecraftServer server, UUID owner) {
		return CONTEXTS.computeIfAbsent(owner, id -> new OwnerContext(server, id));
	}

	static void saveOrders(MinecraftServer server, OwnerContext context) {
		GuardCommandState state = GuardHierarchy.state(server);
		List<ActiveOrder> active = context.book.active();
		GuardCommandState.OwnerCommand command = active.isEmpty() ? state.peek(context.owner) : state.getOrCreate(context.owner);
		if (command == null) {
			return;
		}
		command.setOrders(active);
		state.setDirty();
	}

	static List<GuardEntity> livingGuards(MinecraftServer server, UUID owner) {
		List<GuardEntity> owned = GuardOwnershipIndex.getOwnedGuards(server, owner);
		List<GuardEntity> living = new ArrayList<>(owned.size());
		for (GuardEntity guard : owned) {
			if (guard.isAlive() && !guard.isRemoved()) {
				living.add(guard);
			}
		}
		return living;
	}

	/** Group and guard names the speaker may address, from live data. */
	public static OwnerRoster roster(ServerPlayer speaker) {
		MinecraftServer server = speaker.level().getServer();
		Set<String> groups = new LinkedHashSet<>();
		GuardTacticsState.PlayerTactics tactics = GuardTacticsManager.getState(server).getOrDefault(speaker.getUUID());
		for (int row = 0; row < tactics.groupCount(); row++) {
			groups.add(tactics.getGroupName(row));
		}
		List<String> names = new ArrayList<>();
		for (GuardEntity guard : livingGuards(server, speaker.getUUID())) {
			names.add(guard.getName().getString());
			if (guard.getGroupIndex() >= 0) {
				groups.add(guard.getGroupName());
			}
		}
		return OwnerRoster.of(groups, names);
	}

	static Optional<LivingEntity> attackTarget(MinecraftServer server, OwnerContext context, ActiveOrder order) {
		UUID id = context.attackTargets.get(order.id());
		if (id == null) {
			return Optional.empty();
		}
		for (var level : server.getAllLevels()) {
			if (level.getEntity(id) instanceof LivingEntity living) {
				return Optional.of(living);
			}
		}
		return Optional.empty();
	}
}
