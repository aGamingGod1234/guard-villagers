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
import com.guardvillagers.command.order.TargetRef;
import com.guardvillagers.command.order.TargetResolver;
import com.guardvillagers.data.GuardCommandState;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.voice.VoiceConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
		/**
		 * Guards a retreat order took out of combat, with the game tick their suspension ends at the latest. Only
		 * {@code resumeCombat} lifts a suspension and the guard does not save it, so the commander owns the undo:
		 * see {@link #releaseSuspensions}.
		 */
		final Map<GuardEntity, Long> suspended = new IdentityHashMap<>();
		Approach approach;
		long inputSequence;

		OwnerContext(MinecraftServer server, UUID owner) {
			this.owner = owner;
			this.book = new OrderBook(() -> server.overworld().getGameTime(), settings.voiceOrderExpirySeconds() * 20L);
			GuardCommandState.OwnerCommand saved = GuardHierarchy.state(server).peek(owner);
			if (saved != null) {
				List<ActiveOrder> standing = new ArrayList<>();
				for (ActiveOrder order : saved.orders()) {
					if (order.order().action().persistence() == Action.Persistence.TIMED) {
						saved.queueCleanup(order);
					} else {
						standing.add(order);
					}
				}
				this.book.restore(standing);
				saved.setOrders(standing);
				GuardHierarchy.state(server).setDirty();
			}
			this.brain = new CommanderBrain(owner);
		}
	}

	record Approach(GuardEntity guard, long until, BlockPos anchor, long generation) {
	}

	private static final Map<UUID, OwnerContext> CONTEXTS = new ConcurrentHashMap<>();
	private static volatile OrderInterpreter interpreter = OrderInterpreter.grammarOnly();
	private static volatile VoiceConfig.Commander settings = VoiceConfig.defaults().commander();
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

	public static void configure(VoiceConfig.Commander config) {
		settings = config;
		for (OwnerContext context : CONTEXTS.values()) {
			context.book.setTimedExpiryTicks(config.voiceOrderExpirySeconds() * 20L);
			context.brain.requestPlan();
		}
	}

	public static int planIntervalTicks() {
		return settings.planIntervalTicks();
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
		OwnerContext intake = context(server, speaker.getUUID());
		long sequence = ++intake.inputSequence;
		// Grammar first, offline. The LLM is only asked when the grammar found nothing in words addressed to the
		// guards, the speaker has loaded guards to command, and the per-player and server-wide LLM budgets allow it.
		Interpretation local = current.interpretLocally(text, roster);
		boolean addressed = local.addressed() || explicitlyAddressed;
		boolean askLlm = !local.understood() && local.rejected().isEmpty() && current.llmAvailable() && addressed
				&& !roster.guardNames().isEmpty() && current.allowsLlmFallback(text, roster);
		Runnable releaseLlm = askLlm ? VoiceCommandBridge.acquireLlm(speaker.getUUID()) : null;
		if (releaseLlm == null) {
			handle(speaker, new Interpretation(local.origin(), local.orders(), local.rejected(), addressed, local.reply(),
					local.llmStatus(), local.retryAfter()));
			return;
		}
		PendingOrderContext spokenContext = PendingOrderContext.capture(speaker);
		CompletableFuture<Interpretation> future;
		try {
			future = current.interpret(speaker.getUUID(), text, roster, explicitlyAddressed);
		} catch (RuntimeException exception) {
			releaseLlm.run();
			throw exception;
		}
		future.whenComplete((ignored, error) -> releaseLlm.run());
		if (future.isDone()) {
			handle(speaker, future.join());
			return;
		}
		UUID speakerId = speaker.getUUID();
		future.whenComplete((interpretation, error) -> server.execute(() -> {
			ServerPlayer online = server.getPlayerList().getPlayer(speakerId);
			if (online != speaker || interpreter != current || CONTEXTS.get(speakerId) != intake
					|| intake.inputSequence != sequence) {
				return;
			}
			if (error != null || interpretation == null) {
				CommanderCallouts.say(server, speakerId, CommanderCallouts.text("guardvillagers.order.llm_unavailable",
						"Commander couldn't hear you, try again."), CommanderCallouts.Kind.ACK);
				return;
			}
			if (!spokenContext.stillValid(online, interpretation.orders())) {
				CommanderCallouts.say(server, speakerId, "The order's target or location changed. Please repeat it.", CommanderCallouts.Kind.ACK);
				return;
			}
			handle(online, interpretation);
		}));
	}

	/** A delayed interpretation may execute only while its spoken references still denote the same context. */
	private record PendingOrderContext(net.minecraft.server.level.ServerLevel level, BlockPos anchor,
			net.minecraft.core.Direction facing, Map<TargetRef, UUID> targets) {
		static PendingOrderContext capture(ServerPlayer player) {
			Map<TargetRef, UUID> targets = new EnumMap<>(TargetRef.class);
			for (var ref : TargetRef.values()) {
				TargetResolver.resolve(player, ref).ifPresent(target -> targets.put(ref, target.getUUID()));
			}
			return new PendingOrderContext(player.level(), player.blockPosition().immutable(), player.getDirection(), Map.copyOf(targets));
		}

		boolean stillValid(ServerPlayer player, List<Order> orders) {
			if (player.level() != level) return false;
			for (Order order : orders) {
				if (order.action() == Action.ATTACK_TARGET) {
					var ref = order.target().orElse(TargetRef.MY_TARGET);
					UUID current = TargetResolver.resolve(player, ref).map(LivingEntity::getUUID).orElse(null);
					if (current == null || !current.equals(targets.get(ref))) return false;
				}
				if (switch (order.action()) {
					case PATROL_HERE, COME_HERE, FORM, RETREAT, DEFEND_VILLAGE -> true;
					default -> false;
				} && (!player.blockPosition().equals(anchor) || player.getDirection() != facing)) return false;
			}
			return true;
		}
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
		context.inputSequence++;
		List<GuardEntity> allGuards = livingGuards(server, owner);
		for (ActiveOrder expired : context.book.expire()) undoOrder(server, context, expired, true);
		reconcileCleanup(server, context);
		GuardEntity commander = GuardHierarchy.commanderAmong(server, owner, allGuards);
		// Orders act at the speaker's position and on things the speaker sees, so only guards in the speaker's
		// dimension carry them out. Undoing still reaches every loaded guard.
		List<GuardEntity> guards = new ArrayList<>(allGuards.size());
		for (GuardEntity guard : allGuards) {
			if (guard.level() == speaker.level()) {
				guards.add(guard);
			}
		}
		GuardEntity localCommander = commander != null && commander.level() == speaker.level() ? commander : null;
		int reached = 0;
		boolean commanderBusy = false;
		int reportTicks = REPORT_TICKS;
		for (Order order : orders) {
			int ticks = (int) Math.min(Integer.MAX_VALUE, context.book.timedExpiryTicks());
			if (order.action() == Action.STATUS_REPORT) {
				CommanderCallouts.say(server, owner, context.brain.statusLine(server, speaker, allGuards, commander),
						CommanderCallouts.Kind.STATUS);
				continue;
			}
			Optional<LivingEntity> target = order.action() == Action.ATTACK_TARGET
					? TargetResolver.resolve(speaker,
							order.target().orElse(TargetRef.MY_TARGET)) : Optional.empty();
			List<GuardEntity> recipients = OrderExecutor.inScope(order.scope(), guards, localCommander);
			OrderExecutor.Context execution = new OrderExecutor.Context(speaker, recipients, localCommander, ticks, target);
			// An order that would reach nobody must not replace the orders standing on its scope.
			Optional<OrderExecutor.Result> refused = OrderExecutor.precheck(order, execution);
			if (refused.isPresent()) {
				CommanderCallouts.say(server, owner, refused.get().ack(), CommanderCallouts.Kind.ACK);
				continue;
			}
			if (order.action() == Action.ATTACK_TARGET) {
				recipients = recipients.stream().filter(guard -> target.filter(entity -> OrderExecutor.canAttack(guard, entity)).isPresent()).toList();
			}
			List<GuardEntity> boundRecipients = recipients;
			if (order.action().persistence() != Action.Persistence.INSTANT && order.action() != Action.AS_YOU_WERE) {
				// Keep differently scoped recipients for fallback after their narrower order ends. Same-scope
				// stronger entries block admission only for the recipients they already own.
				List<ActiveOrder> standing = context.book.active();
				boundRecipients = recipients.stream().filter(guard -> standing.stream().noneMatch(old ->
						old.priority() > source.priority() && old.order().scope().sameAs(order.scope())
								&& OrderExecutor.covers(old, guard, localCommander))).toList();
				recipients = recipients.stream().filter(guard -> context.book.wouldBeEffectiveForGuard(order, source,
						guard.getUUID(), scope -> OrderExecutor.covers(scope, guard, localCommander))).toList();
				if (recipients.isEmpty()) {
					CommanderCallouts.say(server, owner, "Standing by: an earlier order still holds.", CommanderCallouts.Kind.ACK);
					continue;
				}
			}
			execution = new OrderExecutor.Context(speaker, recipients, localCommander, ticks, target);
			Map<UUID, ActiveOrder> previous = new HashMap<>();
			for (GuardEntity guard : recipients) {
				context.book.effectiveForGuard(guard.getUUID(), scope -> OrderExecutor.covers(scope, guard, localCommander))
						.ifPresent(active -> previous.put(guard.getUUID(), active));
			}
			OrderBook.IssueResult issued = context.book.issue(order, source, boundRecipients.stream().map(GuardEntity::getUUID).collect(Collectors.toSet()));
			if (issued.status() == OrderBook.Status.OUTRANKED) {
				CommanderCallouts.say(server, owner, CommanderCallouts.text("guardvillagers.order.outranked",
						"Standing by: an earlier order still holds."), CommanderCallouts.Kind.ACK);
				continue;
			}
			// Cleared, replaced and evicted orders are all undone before the new one runs, so nothing an order set
			// (a hold, a retreat's suspended combat, a rally) outlives it unnoticed.
			for (ActiveOrder removed : issued.removed()) {
				undoOrder(server, context, removed, false);
			}
			if (issued.order().isPresent()) {
				long issuedId = issued.order().get().id();
				recipients = recipients.stream().filter(guard -> context.book.effectiveForGuard(guard.getUUID(),
						scope -> OrderExecutor.covers(scope, guard, localCommander)).map(active -> active.id() == issuedId).orElse(false)).toList();
				execution = new OrderExecutor.Context(speaker, recipients, localCommander, ticks, target);
			}
			for (GuardEntity guard : recipients) {
				ActiveOrder old = previous.get(guard.getUUID());
				if (old != null && old.order().action() == Action.ATTACK_TARGET
						&& context.book.effectiveForGuard(guard.getUUID(), scope -> OrderExecutor.covers(scope, guard, localCommander))
								.map(entry -> entry.id() != old.id()).orElse(true)) {
					UUID oldTarget = context.attackTargets.get(old.id());
					if (oldTarget != null) guard.clearPriorityTarget(oldTarget);
				}
			}
			if (recipients.contains(localCommander)) context.approach = null;
			OrderExecutor.Result result = recipients.isEmpty() && issued.order().isPresent()
					? new OrderExecutor.Result(0, "", Optional.empty(), false) : OrderExecutor.execute(order, execution);
			reached += result.affected();
			Optional<ActiveOrder> stored = issued.order();
			if (stored.isPresent()) {
				if (result.affected() > 0) {
					OrderExecutor.targetId(result).ifPresent(id -> context.attackTargets.put(stored.get().id(), id));
				}
			}
			if (order.action() == Action.RETREAT && result.affected() > 0) {
				long until = server.overworld().getGameTime() + OrderExecutor.RETREAT_SUSPEND_TICKS;
				for (GuardEntity guard : recipients) {
					if (guard.isCombatSuspended()) {
						context.suspended.put(guard, until);
					}
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
		List<GuardEntity> guards = livingGuards(server, player.getUUID());
		GuardEntity commander = GuardHierarchy.commanderAmong(server, player.getUUID(), guards);
		onTypedOrder(player, OrderExecutor.inScope(scope, guards, commander));
	}

	public static void onTypedOrder(ServerPlayer player, List<GuardEntity> addressed) {
		if (addressed.isEmpty()) return;
		MinecraftServer server = player.level().getServer();
		// Created if missing: after a relog the saved orders are only in the book once a context exists, and they
		// must be cleared too or they come back and pin the guards.
		OwnerContext context = context(server, player.getUUID());
		context.inputSequence++;
		for (ActiveOrder expired : context.book.expire()) undoOrder(server, context, expired, true);
		reconcileCleanup(server, context);
		List<GuardEntity> guards = livingGuards(server, player.getUUID());
		Set<UUID> recipients = addressed.stream().filter(guard -> guard.isOwnedBy(player.getUUID()))
				.map(GuardEntity::getUUID).collect(Collectors.toSet());
		for (ActiveOrder removed : context.book.removeRecipients(recipients)) {
			undoOrder(server, context, removed, false);
		}
		for (GuardEntity guard : guards) {
			if (recipients.contains(guard.getUUID())) {
				guard.setCommanderRecovery(false);
				OrderExecutor.cancelRally(guard);
				guard.resumeCombat();
			}
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
		return command == null ? settings.autoCommandDefault() : command.autoCommandChoice().orElse(settings.autoCommandDefault());
	}

	public static void setAutoCommand(MinecraftServer server, UUID owner, boolean enabled) {
		GuardCommandState state = GuardHierarchy.state(server);
		state.getOrCreate(owner).setAutoCommand(enabled);
		OwnerContext context = CONTEXTS.get(owner);
		if (!enabled) {
			if (context != null) context.brain.stopAutomatic(server, context.book.active());
			else CommanderBrain.restoreRaidBehaviors(server, owner, List.of());
		}
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
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.error("Commander elections failed", exception);
		}
		long now = server.overworld().getGameTime();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// One owner's failure must not stop every other owner's commander.
			try {
				tickOwner(server, player, now);
			} catch (RuntimeException exception) {
				GuardVillagersMod.LOGGER.error("Commander tick failed for {}", player.getUUID(), exception);
			}
		}
		brainNanos += System.nanoTime() - start;
		brainTicks++;
	}

	private static void tickOwner(MinecraftServer server, ServerPlayer player, long now) {
		OwnerContext context = CONTEXTS.get(player.getUUID());
		if (context == null) {
			// Create lazily, on the brain's own cadence, only for players that own loaded guards.
			if (!CommanderBrain.isSnapshotTick(player.getUUID(), now)
					|| GuardOwnershipIndex.getOwnedGuards(server, player.getUUID()).isEmpty()) {
				return;
			}
			context = context(server, player.getUUID());
		}
		if (Math.floorMod(now + CommanderBrain.phaseOf(player.getUUID()), GuardHierarchy.ELECTION_INTERVAL_TICKS) == 0) {
			VoiceCommandBridge.refreshPromptHint(player);
			VoiceCommandBridge.maybeOfferConsent(server, player);
		}
		reconcileCleanup(server, context);
		releaseSuspensions(server, context, now);
		tickApproach(context, player, now);
		context.brain.tick(server, player, context, now);
		CommanderCallouts.flush(server, player.getUUID());
	}

	/**
	 * Lets suspended guards fight again once no live retreat order covers them (it expired, was replaced, cleared,
	 * evicted, or the guard left its scope or changed owner) or the suspension cap passed. A guard that unloaded or
	 * changed dimension comes back as a new entity that is not suspended, so it is simply dropped.
	 */
	static void releaseSuspensions(MinecraftServer server, OwnerContext context, long now) {
		if (context.suspended.isEmpty()) {
			return;
		}
		List<ActiveOrder> retreats = new ArrayList<>();
		for (ActiveOrder order : context.book.active()) {
			if (order.order().action() == Action.RETREAT) {
				retreats.add(order);
			}
		}
		GuardEntity commander = retreats.isEmpty() ? null : GuardHierarchy.commander(server, context.owner).orElse(null);
		for (Iterator<Map.Entry<GuardEntity, Long>> it = context.suspended.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<GuardEntity, Long> entry = it.next();
			GuardEntity guard = entry.getKey();
			if (guard.isRemoved() || !guard.isAlive()) {
				it.remove();
				continue;
			}
			boolean covered = false;
			if (now < entry.getValue() && guard.isOwnedBy(context.owner)) {
				for (ActiveOrder retreat : retreats) {
					if (OrderExecutor.covers(retreat, guard, commander)) {
						covered = true;
						break;
					}
				}
			}
			if (!covered) {
				guard.resumeCombat();
				it.remove();
			}
		}
	}

	private static void resumeAll(OwnerContext context) {
		for (GuardEntity guard : context.suspended.keySet()) {
			if (!guard.isRemoved()) {
				guard.resumeCombat();
			}
		}
		context.suspended.clear();
	}

	/** The owner left: nothing of theirs keeps running, and nothing the commander changed stays changed. */
	public static void forget(MinecraftServer server, UUID owner) {
		OwnerContext context = CONTEXTS.remove(owner);
		if (context != null) {
			for (GuardEntity guard : livingGuards(server, owner)) {
				for (UUID target : context.attackTargets.values()) guard.clearPriorityTarget(target);
			}
			resumeAll(context);
			context.brain.stopAutomatic(server, context.book.active());
		}
		// Without the owner nobody runs the raid response, so its behavior changes are put back now; guards that
		// are not loaded keep their saved entry until the owner's commander next sees them.
		CommanderBrain.restoreRaidBehaviors(server, owner, context != null ? context.book.active() : List.of());
		CommanderCallouts.forget(owner);
		VoiceCommandBridge.forget(owner);
	}

	public static void clearSessionState() {
		CONTEXTS.values().forEach(CommanderService::resumeAll);
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
		if (commander == null || commander.level() != player.level() || commander.isHoldingPosition()
				|| commander.isStaying()
				|| (commander.getRallyPoint().isPresent() && !isReporting(context, commander))
				|| context.book.effectiveForGuard(commander.getUUID(), scope -> OrderExecutor.covers(scope, commander, commander))
					.filter(order -> order.order().action() != Action.FOLLOW && order.order().action() != Action.COME_HERE).isPresent()) {
			return;
		}
		long now = server.overworld().getGameTime();
		BlockPos post = reportPost(player, commander);
		commander.rallyTo(post, ticks, true);
		context.approach = new Approach(commander, now + ticks, player.blockPosition(), commander.getRallyGeneration());
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
				|| guard.getRallyPoint().isEmpty() || guard.getRallyGeneration() != approach.generation()) {
			context.approach = null;
			return;
		}
		if ((now & 7L) == 0L && player.blockPosition().distSqr(approach.anchor()) > 4.0D) {
			guard.rallyTo(reportPost(player, guard), (int) (approach.until() - now), true);
			context.approach = new Approach(guard, approach.until(), player.blockPosition(), guard.getRallyGeneration());
		}
		if (guard.distanceToSqr(player) < 16.0D && guard.getTarget() == null) {
			guard.getLookControl().setLookAt(player, 30.0F, 30.0F);
		}
	}

	static boolean isReporting(OwnerContext context, GuardEntity guard) {
		return context.approach != null && context.approach.guard() == guard
				&& context.approach.generation() == guard.getRallyGeneration();
	}

	// ---- helpers ----

	static OwnerContext context(MinecraftServer server, UUID owner) {
		return CONTEXTS.computeIfAbsent(owner, id -> new OwnerContext(server, id));
	}

	/** Undo loaded recipients now; retain unloaded recipients until they return. */
	static void undoOrder(MinecraftServer server, OwnerContext context, ActiveOrder removed, boolean expired) {
		List<GuardEntity> guards = livingGuards(server, context.owner);
		GuardEntity commander = GuardHierarchy.commanderAmong(server, context.owner, guards);
		UUID attack = context.attackTargets.get(removed.id());
		if (attack != null) {
			for (GuardEntity guard : guards) {
				if (!OrderExecutor.covers(removed, guard, commander)) continue;
				boolean stillDirected = context.book.effectiveForGuard(guard.getUUID(), scope -> OrderExecutor.covers(scope, guard, commander))
						.filter(order -> order.order().action() == Action.ATTACK_TARGET)
						.map(order -> attack.equals(context.attackTargets.get(order.id()))).orElse(false);
				if (!stillDirected) guard.clearPriorityTarget(attack);
			}
		}
		OrderExecutor.undo(removed, guards, commander, expired, context.book.active());
		if (context.book.active().stream().noneMatch(order -> order.id() == removed.id())) context.attackTargets.remove(removed.id());
		Set<UUID> remaining = new HashSet<>(removed.recipients().orElse(Set.of()));
		guards.forEach(guard -> remaining.remove(guard.getUUID()));
		if (!remaining.isEmpty()) {
			GuardCommandState state = GuardHierarchy.state(server);
			state.getOrCreate(context.owner).queueCleanup(removed.withRecipients(remaining));
			state.setDirty();
		}
	}

	public static void onGuardLoaded(MinecraftServer server, GuardEntity guard) {
		UUID owner = guard.getOwnerUuid();
		if (owner == null || GuardHierarchy.state(server).peek(owner) == null) return;
		OwnerContext context = context(server, owner);
		reconcileCleanup(server, context, List.of(guard));
	}

	private static void reconcileCleanup(MinecraftServer server, OwnerContext context) {
		GuardCommandState.OwnerCommand saved = GuardHierarchy.state(server).peek(context.owner);
		if (saved == null || saved.pendingCleanup().isEmpty()) return;
		reconcileCleanup(server, context, livingGuards(server, context.owner));
	}

	private static void reconcileCleanup(MinecraftServer server, OwnerContext context, List<GuardEntity> guards) {
		GuardCommandState state = GuardHierarchy.state(server);
		GuardCommandState.OwnerCommand saved = state.peek(context.owner);
		if (saved == null || saved.pendingCleanup().isEmpty()) return;
		GuardEntity commander = GuardHierarchy.commanderAmong(server, context.owner, guards);
		List<ActiveOrder> remaining = new ArrayList<>();
		for (ActiveOrder removed : saved.pendingCleanup()) {
			OrderExecutor.undo(removed, guards, commander, false, context.book.active());
			Set<UUID> ids = new HashSet<>(removed.recipients().orElse(Set.of()));
			guards.forEach(guard -> ids.remove(guard.getUUID()));
			if (!ids.isEmpty()) remaining.add(removed.withRecipients(ids));
		}
		if (!remaining.equals(saved.pendingCleanup())) {
			saved.setPendingCleanup(remaining);
			state.setDirty();
		}
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
		return GuardOwnershipIndex.getOwnedGuards(server, owner);
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
