package com.guardvillagers.command.order;

import com.guardvillagers.command.commander.CommanderCallouts;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.entity.FormationType;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import org.jspecify.annotations.Nullable;

/**
 * Turns validated {@link Order}s into calls on the guards' existing inputs (stay, hold, follow, home, behavior,
 * rally, priority target). The guard's AI controller stays the only decision-maker; this never moves a guard itself.
 * Only guards in the supplied roster (the speaker's own loaded guards) are ever touched.
 */
public final class OrderExecutor {
	public static final int DEFAULT_PATROL_RADIUS = 24;
	public static final int DEFEND_RADIUS = 32;
	public static final int BELL_SEARCH_RADIUS = 64;
	/** Retreat suspends fighting this long at most, so a guard is never left defenceless for the whole order. */
	public static final int RETREAT_SUSPEND_TICKS = 200;

	/**
	 * @param guards    the speaker's loaded, living guards
	 * @param commander current commander among {@code guards}, or null
	 * @param ticks     how long timed orders last
	 */
	public record Context(ServerPlayer speaker, List<GuardEntity> guards, @Nullable GuardEntity commander, int ticks) {
	}

	/**
	 * @param affected        guards the order reached
	 * @param ack             line for the commander to say
	 * @param target          resolved attack target, if any
	 * @param commanderObeys  the commander itself is busy carrying the order out (so it should not walk over to report)
	 */
	public record Result(int affected, String ack, Optional<LivingEntity> target, boolean commanderObeys) {
	}

	private OrderExecutor() {
	}

	/** Does {@code scope} include {@code guard}? */
	public static boolean covers(Scope scope, GuardEntity guard, @Nullable GuardEntity commander) {
		return switch (scope.kind()) {
			case ALL -> true;
			case COMMANDER -> commander != null && guard == commander;
			case GROUP -> guard.getGroupIndex() >= 0 && guard.getGroupName().equalsIgnoreCase(scope.name());
			case GUARD -> guard.getName().getString().equalsIgnoreCase(scope.name());
			case ROLE -> scope.role().map(role -> role == Scope.Role.BOWMEN
					? guard.getRole() == GuardRole.BOWMAN
					: guard.getRole() == GuardRole.SWORDSMAN).orElse(false);
		};
	}

	public static List<GuardEntity> inScope(Scope scope, List<GuardEntity> guards, @Nullable GuardEntity commander) {
		List<GuardEntity> matched = new ArrayList<>();
		for (GuardEntity guard : guards) {
			if (covers(scope, guard, commander)) {
				matched.add(guard);
			}
		}
		return matched;
	}

	public static Result execute(Order order, Context context) {
		List<GuardEntity> targets = inScope(order.scope(), context.guards(), context.commander());
		boolean commanderIncluded = context.commander() != null && targets.contains(context.commander());
		String prefix = prefix(order.scope());
		if (targets.isEmpty() && !order.action().isAppointment() && order.action() != Action.STATUS_REPORT
				&& order.action() != Action.AS_YOU_WERE) {
			return new Result(0, CommanderCallouts.text("guardvillagers.order.no_guards", "Nobody answers to that."),
					Optional.empty(), false);
		}
		ServerPlayer speaker = context.speaker();
		Optional<LivingEntity> resolvedTarget = Optional.empty();
		boolean obeys = commanderIncluded;
		String ack = ack(order.action(), "");
		switch (order.action()) {
			case FOLLOW -> {
				for (GuardEntity guard : targets) {
					cancelRally(guard);
					guard.resumeCombat();
					guard.setStaying(false);
					guard.setFollowOverride(true);
				}
				obeys = false;
			}
			case STAY -> targets.forEach(guard -> {
				cancelRally(guard);
				guard.setStaying(true);
			});
			case HOLD -> targets.forEach(guard -> {
				cancelRally(guard);
				guard.setHoldPosition(true);
			});
			case COME_HERE -> {
				BlockPos here = speaker.blockPosition();
				for (GuardEntity guard : targets) {
					guard.setHoldPosition(false);
					guard.resumeCombat();
					// The commander gets its own exact post in front of the speaker (see CommanderService).
					if (guard != context.commander()) {
						guard.rallyTo(here, context.ticks());
					}
				}
				obeys = false;
			}
			case ATTACK_TARGET -> {
				resolvedTarget = TargetResolver.resolve(speaker, order.target().orElse(TargetRef.MY_TARGET));
				if (resolvedTarget.isEmpty()) {
					return new Result(0, CommanderCallouts.text("guardvillagers.order.no_target", "I don't see a target."),
							Optional.empty(), false);
				}
				LivingEntity target = resolvedTarget.get();
				for (GuardEntity guard : targets) {
					if (guard.isAlly(target)) {
						continue;
					}
					cancelRally(guard);
					guard.resumeCombat();
					guard.setHoldPosition(false);
					if (guard.isStaying()) {
						guard.setStaying(false);
					}
					guard.setPriorityTarget(target);
				}
			}
			case DEFEND_VILLAGE -> {
				ServerLevel level = speaker.level();
				Optional<BlockPos> bell = nearestBell(level, speaker.blockPosition(), BELL_SEARCH_RADIUS);
				BlockPos post = bell.orElse(speaker.blockPosition());
				for (GuardEntity guard : targets) {
					guard.resumeCombat();
					guard.setStaying(false);
					guard.setHome(post, DEFEND_RADIUS);
					guard.setBehavior(GuardBehavior.DEFENSIVE);
					guard.rallyTo(post, 240);
				}
				if (bell.isEmpty()) {
					ack = CommanderCallouts.text("guardvillagers.order.no_bell", "No bell nearby. Guarding this spot.");
				}
			}
			case PATROL_HERE -> {
				int radius = order.radius().orElse(DEFAULT_PATROL_RADIUS);
				BlockPos post = speaker.blockPosition();
				for (GuardEntity guard : targets) {
					cancelRally(guard);
					guard.resumeCombat();
					guard.setStaying(false);
					guard.setHome(post, radius);
					guard.setBehavior(GuardBehavior.PERIMETER);
				}
			}
			case FORM -> {
				FormationType type = order.formation().orElse(FormationType.LINE);
				form(speaker, targets, type, context.ticks());
				ack = ack(order.action(), type.name().toLowerCase(Locale.ROOT));
			}
			case RETREAT -> {
				BlockPos fallback = speaker.blockPosition();
				for (GuardEntity guard : targets) {
					guard.setHoldPosition(false);
					guard.clearCombatTarget();
					guard.suspendCombat();
					guard.rallyTo(fallback, Math.min(context.ticks(), RETREAT_SUSPEND_TICKS));
				}
			}
			case AS_YOU_WERE, STATUS_REPORT -> obeys = false;
			case APPOINT_COMMANDER, APPOINT_CAPTAIN -> {
				obeys = false;
				if (targets.size() != 1) {
					return new Result(0, CommanderCallouts.text("guardvillagers.order.no_guards", "Nobody answers to that."),
							Optional.empty(), false);
				}
				GuardEntity appointee = targets.getFirst();
				GuardHierarchy.AppointResult result = order.action() == Action.APPOINT_COMMANDER
						? GuardHierarchy.appointCommander(speaker.level().getServer(), speaker.getUUID(), appointee)
						: GuardHierarchy.appointCaptain(speaker.level().getServer(), speaker.getUUID(), appointee);
				String name = appointee.getName().getString();
				ack = switch (result) {
					case APPOINTED -> order.action() == Action.APPOINT_COMMANDER
							? CommanderCallouts.text("guardvillagers.order.appointed_commander", "%s is now in command.", name)
							: CommanderCallouts.text("guardvillagers.order.appointed_captain", "%s is now captain of %s.", name,
									appointee.getGroupName());
					case NO_GROUP -> CommanderCallouts.text("guardvillagers.order.appoint_no_group",
							"%s is not in a group. Assign a group first.", name);
					case NOT_OWNED -> CommanderCallouts.text("guardvillagers.order.no_guards", "Nobody answers to that.");
				};
				return new Result(result == GuardHierarchy.AppointResult.APPOINTED ? 1 : 0, ack, Optional.empty(), false);
			}
		}
		String line = prefix.isEmpty() || ack.isEmpty() ? ack : prefix + Character.toLowerCase(ack.charAt(0)) + ack.substring(1);
		return new Result(targets.size(), line, resolvedTarget, obeys);
	}

	/** Reverts what an order did when "as you were" clears it, or when a timed order ends. */
	public static void undo(ActiveOrder removed, List<GuardEntity> guards, @Nullable GuardEntity commander, boolean expired) {
		for (GuardEntity guard : guards) {
			if (!covers(removed.order().scope(), guard, commander)) {
				continue;
			}
			switch (removed.order().action()) {
				case HOLD -> {
					if (!expired && guard.isHoldingPosition()) {
						guard.setHoldPosition(false);
					}
				}
				case STAY -> {
					if (!expired && guard.isStaying()) {
						guard.setStaying(false);
					}
				}
				case FORM, COME_HERE -> {
					if (!expired) {
						cancelRally(guard);
					}
					if (removed.order().action() == Action.FORM) {
						guard.setFormationType(FormationType.FOLLOW);
					}
				}
				case RETREAT -> {
					guard.resumeCombat();
					if (!expired) {
						cancelRally(guard);
					}
				}
				default -> {
				}
			}
		}
	}

	/** Ends a rally early (the controller drops a rally whose ticks reach zero). */
	public static void cancelRally(GuardEntity guard) {
		if (guard.getRallyPoint().isPresent()) {
			guard.rallyTo(guard.blockPosition(), 0);
		}
	}

	public static Optional<BlockPos> nearestBell(ServerLevel level, BlockPos center, int radius) {
		return level.getPoiManager().findClosest(holder -> holder.is(PoiTypes.MEETING), center, radius, PoiManager.Occupancy.ANY);
	}

	/**
	 * Sends each guard to its own formation post in front of the speaker. Posts follow the speaker's facing snapped to
	 * the nearest compass direction; guards are matched to posts so they cross as little as possible.
	 */
	public static void form(ServerPlayer speaker, List<GuardEntity> guards, FormationType type, int ticks) {
		Direction facing = Direction.fromYRot(speaker.getYRot());
		Direction right = facing.getClockWise();
		List<GuardEntity> melee = new ArrayList<>();
		List<GuardEntity> ranged = new ArrayList<>();
		for (GuardEntity guard : guards) {
			(guard.getRole() == GuardRole.BOWMAN ? ranged : melee).add(guard);
		}
		// Within each role, order guards left-to-right as seen by the speaker so posts map without crossing.
		Comparator<GuardEntity> leftToRight = Comparator.comparingDouble(
				guard -> (guard.getX() - speaker.getX()) * right.getStepX() + (guard.getZ() - speaker.getZ()) * right.getStepZ());
		melee.sort(leftToRight);
		ranged.sort(leftToRight);
		List<FormationSlots.Post> posts = FormationSlots.posts(type, melee.size(), ranged.size());
		List<GuardEntity> ordered = new ArrayList<>(melee);
		ordered.addAll(ranged);
		BlockPos origin = speaker.blockPosition();
		for (int i = 0; i < ordered.size() && i < posts.size(); i++) {
			FormationSlots.Post post = posts.get(i);
			BlockPos target = origin
					.relative(facing, post.forward())
					.relative(right, post.right());
			GuardEntity guard = ordered.get(i);
			guard.setHoldPosition(false);
			guard.setFormationType(type);
			guard.rallyTo(target, ticks, true);
		}
	}

	private static String prefix(Scope scope) {
		return switch (scope.kind()) {
			case GROUP, GUARD -> scope.name() + ", ";
			case ROLE -> scope.name().substring(0, 1).toUpperCase(Locale.ROOT) + scope.name().substring(1) + ", ";
			default -> "";
		};
	}

	private static String ack(Action action, String detail) {
		String fallback = switch (action) {
			case FOLLOW -> "On you.";
			case STAY -> "Staying put.";
			case HOLD -> "Holding.";
			case COME_HERE -> "On my way.";
			case ATTACK_TARGET -> "Attacking.";
			case DEFEND_VILLAGE -> "To the bell.";
			case PATROL_HERE -> "Patrolling this area.";
			case FORM -> "Form a %s.";
			case RETREAT -> "Falling back.";
			case AS_YOU_WERE -> "As you were. I'll take it from here.";
			case STATUS_REPORT -> "";
			case APPOINT_COMMANDER, APPOINT_CAPTAIN -> "Understood.";
		};
		return CommanderCallouts.text(action.ackKey(), fallback, detail);
	}

	/** For "attack" orders: the target id to keep pressing while the order lasts. */
	public static Optional<UUID> targetId(Result result) {
		return result.target().map(LivingEntity::getUUID);
	}
}
