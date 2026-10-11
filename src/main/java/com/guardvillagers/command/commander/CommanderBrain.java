package com.guardvillagers.command.commander;

import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.ActiveOrder;
import com.guardvillagers.command.order.OrderExecutor;
import com.guardvillagers.data.GuardCommandState;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The commander's intuition for one owner: deterministic rules, no LLM. Every {@link #SNAPSHOT_INTERVAL} ticks
 * (staggered by owner) it reads the guards' existing state and scans for hostiles near the owner and near homed
 * squads; every {@link #PLAN_INTERVAL} ticks, or at once after an order or when the owner is hurt, it plans:
 *
 * <ol>
 * <li>Guards under a player order, stay, hold, a whistle/bell rally or a retreat are left alone (they still defend
 * themselves). A "follow me" order still lets the commander defend the owner.</li>
 * <li>Guard the owner: whoever hurt the owner, or a hostile within 12 blocks of them, gets the nearest guards.</li>
 * <li>Raid response: homed squads near a raid switch to raid tactics and rally to the bell.</li>
 * <li>Assign threats: highest score first, to the nearest free guards, bowmen preferred against slow melee and
 * creepers, swordsmen against ranged mobs. A small bodyguard detail always stays with the owner.</li>
 * <li>Reinforce: a fight whose guards are below 60% health on average gets two more.</li>
 * <li>Pull the wounded back: below 30% health a guard is rallied to the owner and not reassigned until above 50%.</li>
 * <li>Archers step behind the owner, away from a nearby threat, so the swordsmen take the front.</li>
 * <li>Patrol rotation: when quiet for 30 s, homed squads walk a loop around their home every minute.</li>
 * </ol>
 *
 * Moves go only through the guards' public inputs ({@code setMainTarget}, {@code setPriorityTarget},
 * {@code rallyTo}, {@code setBehavior}), so each guard's AI controller stays the single decision-maker.
 */
final class CommanderBrain {
	static final int SNAPSHOT_INTERVAL = 20;
	static final int PLAN_INTERVAL = 40;
	static final double OWNER_SCAN_RADIUS = 24.0D;
	static final double FOLLOW_LEASH = 24.0D;
	static final double OWNER_DANGER_RADIUS = 12.0D;
	static final int MAX_THREATS = 16;
	static final int MAX_SCAN_BOXES = 4;
	static final float WOUNDED_ENTER = 0.30F;
	static final float WOUNDED_EXIT = 0.50F;
	static final float REINFORCE_HEALTH = 0.60F;
	static final int OWNER_HURT_WINDOW_TICKS = 100;
	static final int QUIET_BEFORE_PATROL_TICKS = 600;
	static final int PATROL_ROTATE_TICKS = 1200;
	static final int AREA_CLEAR_TICKS = 100;
	static final int BELL_REFRESH_TICKS = 200;
	static final int BELL_RADIUS = 64;
	static final int RAID_RADIUS = 96;

	enum Control {
		/** The commander may assign, move and pull back this guard. */
		FREE,
		/** Under a player order, stay, hold, a foreign rally or a retreat. */
		LOCKED
	}

	private final UUID owner;
	private final int phase;
	private final List<GuardEntity> roster = new ArrayList<>();
	private final Map<GuardEntity, Control> controls = new IdentityHashMap<>();
	private final List<Threat> threats = new ArrayList<>();
	private final Map<UUID, Long> ownRallies = new HashMap<>();
	private final Set<UUID> wounded = new HashSet<>();
	private final Set<UUID> announcedThreats = new HashSet<>();
	/** Guard to threat, as decided by the last plan (rules 2 and 4). */
	private final Map<UUID, UUID> assignments = new HashMap<>();
	private boolean planRequested = true;
	private int lastOwnerHurtStamp = Integer.MIN_VALUE;
	private long lastThreatTick = Long.MIN_VALUE / 2;
	private boolean clearAnnounced = true;
	private long lastPatrolTick;
	private int patrolStep;
	private BlockPos bell;
	private long bellCheckedTick = Long.MIN_VALUE / 2;
	private boolean raidActive;

	CommanderBrain(UUID owner) {
		this.owner = owner;
		this.phase = phaseOf(owner);
	}

	static int phaseOf(UUID owner) {
		return owner.hashCode() & 0xFFFF;
	}

	static boolean isSnapshotTick(UUID owner, long now) {
		return Math.floorMod(now + phaseOf(owner), SNAPSHOT_INTERVAL) == 0;
	}

	void requestPlan() {
		this.planRequested = true;
	}

	void tick(MinecraftServer server, ServerPlayer player, CommanderService.OwnerContext context, long now) {
		int hurtStamp = player.getLastHurtByMobTimestamp();
		if (hurtStamp != this.lastOwnerHurtStamp) {
			this.lastOwnerHurtStamp = hurtStamp;
			if (player.getLastHurtByMob() != null) {
				this.planRequested = true;
			}
		}
		boolean snapshotTick = Math.floorMod(now + this.phase, SNAPSHOT_INTERVAL) == 0;
		if (!snapshotTick && !this.planRequested) {
			return;
		}
		this.snapshot(server, player, context, now);
		boolean planTick = Math.floorMod(now + this.phase, PLAN_INTERVAL) == 0;
		if (planTick || this.planRequested) {
			this.planRequested = false;
			this.plan(server, player, context, now);
		}
	}

	// ---- snapshot ----

	private void snapshot(MinecraftServer server, ServerPlayer player, CommanderService.OwnerContext context, long now) {
		this.roster.clear();
		this.controls.clear();
		this.threats.clear();
		ServerLevel level = player.level();
		for (GuardEntity guard : CommanderService.livingGuards(server, this.owner)) {
			if (guard.level() == level) {
				this.roster.add(guard);
			}
		}

		List<ActiveOrder> expired = context.book.expire();
		GuardEntity commander = com.guardvillagers.command.hierarchy.GuardHierarchy.commanderAmong(server, this.owner, this.roster);
		if (!expired.isEmpty()) {
			// Undo on every loaded guard, not just this dimension's roster: a guard elsewhere was ordered too.
			List<GuardEntity> everyone = CommanderService.livingGuards(server, this.owner);
			GuardEntity anyCommander = com.guardvillagers.command.hierarchy.GuardHierarchy.commanderAmong(server, this.owner, everyone);
			List<ActiveOrder> stillActive = context.book.active();
			for (ActiveOrder order : expired) {
				OrderExecutor.undo(order, everyone, anyCommander, true, stillActive);
				context.attackTargets.remove(order.id());
			}
			CommanderService.saveOrders(server, context);
		}
		if (this.roster.isEmpty()) {
			return;
		}

		boolean auto = CommanderService.autoCommand(server, this.owner);
		List<ActiveOrder> active = context.book.active();
		this.ownRallies.values().removeIf(until -> until <= now);
		for (GuardEntity guard : this.roster) {
			this.controls.put(guard, auto ? this.controlOf(guard, active, commander, context) : Control.LOCKED);
			float ratio = guard.getHealth() / guard.getMaxHealth();
			if (ratio < WOUNDED_ENTER) {
				if (this.wounded.add(guard.getUUID()) && this.controls.get(guard) != Control.LOCKED) {
					CommanderCallouts.announce(server, this.owner, CommanderCallouts.text("guardvillagers.commander.wounded",
							"%s is wounded. Pulling them back.", guard.getName().getString()), 3);
				}
			} else if (ratio > WOUNDED_EXIT) {
				this.wounded.remove(guard.getUUID());
			}
		}

		if (now - this.bellCheckedTick >= BELL_REFRESH_TICKS) {
			this.bellCheckedTick = now;
			this.bell = OrderExecutor.nearestBell(level, player.blockPosition(), BELL_RADIUS).orElse(null);
		}
		this.scanThreats(level, player);
		if (!this.threats.isEmpty()) {
			this.lastThreatTick = now;
		}
	}

	private Control controlOf(GuardEntity guard, List<ActiveOrder> active, GuardEntity commander,
			CommanderService.OwnerContext context) {
		if (guard.isStaying() || guard.isHoldingPosition() || guard.isCombatSuspended()
				|| CommanderService.isReporting(context, guard)) {
			return Control.LOCKED;
		}
		if (guard.getRallyPoint().isPresent() && !this.ownRallies.containsKey(guard.getUUID())) {
			return Control.LOCKED;
		}
		ActiveOrder best = null;
		for (ActiveOrder order : active) {
			if (OrderExecutor.covers(order.order().scope(), guard, commander)
					&& (best == null || order.priority() > best.priority()
							|| (order.priority() == best.priority() && order.order().scope().specificity() > best.order().scope().specificity())
							|| (order.priority() == best.priority() && order.order().scope().specificity() == best.order().scope().specificity()
									&& order.id() > best.id()))) {
				best = order;
			}
		}
		if (best == null) {
			return Control.FREE;
		}
		// Following is the default stance and every assignment stays inside the follow leash, so a "follow me"
		// order keeps the commander's intuition; every other order pins the guard.
		return best.order().action() == Action.FOLLOW ? Control.FREE : Control.LOCKED;
	}

	private void scanThreats(ServerLevel level, ServerPlayer player) {
		List<AABB> boxes = new ArrayList<>(MAX_SCAN_BOXES);
		boxes.add(player.getBoundingBox().inflate(OWNER_SCAN_RADIUS, 8.0D, OWNER_SCAN_RADIUS));
		// Homed squads far from the owner get their own box (merged by home, at most three).
		List<BlockPos> homes = new ArrayList<>(MAX_SCAN_BOXES - 1);
		for (GuardEntity guard : this.roster) {
			if (boxes.size() + homes.size() >= MAX_SCAN_BOXES) {
				break;
			}
			Optional<BlockPos> home = guard.getHome();
			if (home.isEmpty() || guard.hasFollowOverride() || guard.getPatrolRadius() <= 0
					|| home.get().distToCenterSqr(player.position()) <= OWNER_SCAN_RADIUS * OWNER_SCAN_RADIUS) {
				continue;
			}
			boolean merged = false;
			for (BlockPos known : homes) {
				if (known.distSqr(home.get()) <= OWNER_SCAN_RADIUS * OWNER_SCAN_RADIUS) {
					merged = true;
					break;
				}
			}
			if (!merged) {
				homes.add(home.get());
				double radius = Math.min(32, guard.getPatrolRadius());
				boxes.add(new AABB(home.get()).inflate(radius, 8.0D, radius));
			}
		}
		Set<LivingEntity> seen = new HashSet<>();
		BlockPos reference = this.bell != null ? this.bell : player.blockPosition();
		for (AABB box : boxes) {
			for (Mob mob : level.getEntitiesOfClass(Mob.class, box, mob -> mob instanceof Enemy && mob.isAlive())) {
				if (seen.add(mob)) {
					this.addThreat(mob, player, reference);
				}
			}
		}
		LivingEntity attacker = player.getLastHurtByMob();
		if (attacker != null && attacker.isAlive() && attacker.level() == level && seen.add(attacker)
				&& player.tickCount - player.getLastHurtByMobTimestamp() <= OWNER_HURT_WINDOW_TICKS
				&& !(attacker instanceof GuardEntity guard && guard.isOwnedBy(this.owner))) {
			this.addThreat(attacker, player, reference);
		}
		if (this.threats.size() > MAX_THREATS) {
			this.threats.sort((a, b) -> Double.compare(a.ownerDistanceSq, b.ownerDistanceSq));
			this.threats.subList(MAX_THREATS, this.threats.size()).clear();
		}
		// Count who already fights what, and score.
		Map<UUID, Threat> byId = new HashMap<>();
		for (Threat threat : this.threats) {
			byId.put(threat.entity.getUUID(), threat);
		}
		for (GuardEntity guard : this.roster) {
			UUID target = guard.getMainTargetUuid();
			Threat threat = target == null ? null : byId.get(target);
			if (threat != null) {
				threat.assigned++;
				threat.engagedHealth += guard.getHealth() / guard.getMaxHealth();
				threat.engagedCount++;
			}
		}
		for (Threat threat : this.threats) {
			boolean nearOwner = threat.ownerDistanceSq <= OWNER_DANGER_RADIUS * OWNER_DANGER_RADIUS;
			boolean nearBell = this.bell != null && threat.entity.blockPosition().distSqr(this.bell) <= 16 * 16;
			int stillNeeded = Math.max(0, Math.min(threat.need, this.roster.size()) - threat.assigned);
			threat.score = threat.kind.weight() + 2 * stillNeeded + (nearOwner ? 20 : 0) + (nearBell ? 10 : 0);
		}
		this.threats.sort((a, b) -> Integer.compare(b.score, a.score));
	}

	private void addThreat(LivingEntity entity, ServerPlayer player, BlockPos reference) {
		if (this.roster.getFirst().isAlly(entity)) {
			return;
		}
		double dx = entity.getX() - (reference.getX() + 0.5D);
		double dz = entity.getZ() - (reference.getZ() + 0.5D);
		this.threats.add(new Threat(entity, entity.distanceToSqr(player), Threat.sectorOf(dx, dz)));
	}

	// ---- plan ----

	private void plan(MinecraftServer server, ServerPlayer player, CommanderService.OwnerContext context, long now) {
		if (this.roster.isEmpty()) {
			return;
		}
		this.assignments.clear();
		Set<GuardEntity> busy = new HashSet<>();
		this.pressAttackOrders(server, player, context, busy);
		this.guardOwner(player, busy);
		this.respondToRaid(server, player, context, now);
		Set<GuardEntity> bodyguards = this.pickBodyguards(player, busy);
		this.assignThreats(server, player, busy, bodyguards);
		this.pullBackWounded(player, now);
		this.archersBehindOwner(player, busy, now);
		this.rotatePatrols(now);
		this.announceClear(server, now);
	}

	/** Keeps player "attack" orders pressed while their target lives; ends them when it dies. */
	private void pressAttackOrders(MinecraftServer server, ServerPlayer player, CommanderService.OwnerContext context,
			Set<GuardEntity> busy) {
		if (context.attackTargets.isEmpty()) {
			return;
		}
		GuardEntity commander = com.guardvillagers.command.hierarchy.GuardHierarchy.commanderAmong(server, this.owner, this.roster);
		boolean changed = false;
		for (ActiveOrder order : context.book.active()) {
			if (order.order().action() != Action.ATTACK_TARGET || !context.attackTargets.containsKey(order.id())) {
				continue;
			}
			Optional<LivingEntity> target = CommanderService.attackTarget(server, context, order);
			if (target.isEmpty() || !target.get().isAlive()) {
				context.book.complete(order.id());
				context.attackTargets.remove(order.id());
				changed = true;
				CommanderCallouts.say(server, this.owner, CommanderCallouts.text("guardvillagers.commander.target_down",
						"Target down."), CommanderCallouts.Kind.ACK);
				continue;
			}
			for (GuardEntity guard : this.roster) {
				if (context.book.effectiveFor(scope -> OrderExecutor.covers(scope, guard, commander))
						.map(effective -> effective.id() == order.id()).orElse(false) && !guard.isAlly(target.get())) {
					guard.setPriorityTarget(target.get());
					busy.add(guard);
				}
			}
		}
		if (changed) {
			CommanderService.saveOrders(server, context);
		}
	}

	/** Rule 2: whoever hurt the owner, or a hostile within 12 blocks of them, gets the nearest guards. */
	private void guardOwner(ServerPlayer player, Set<GuardEntity> busy) {
		for (Threat threat : this.threats) {
			boolean attackedOwner = threat.entity == player.getLastHurtByMob()
					&& player.tickCount - player.getLastHurtByMobTimestamp() <= OWNER_HURT_WINDOW_TICKS;
			if (!attackedOwner && threat.ownerDistanceSq > OWNER_DANGER_RADIUS * OWNER_DANGER_RADIUS) {
				continue;
			}
			int needed = Math.min(threat.need, this.roster.size()) - threat.assigned;
			for (GuardEntity guard : this.nearestAvailable(threat, busy, true, needed)) {
				// Only the mob that actually hurt the owner gets the owner-directed priority order. A
				// hostile that is merely close is a normal assignment: as a priority order it outranked
				// the real attacker (OWNER_DAMAGED) for the order's whole lifetime once the owner was hit.
				if (attackedOwner) {
					guard.setPriorityTarget(threat.entity);
				} else {
					guard.setMainTarget(threat.entity);
				}
				this.assignments.put(guard.getUUID(), threat.entity.getUUID());
				busy.add(guard);
				threat.assigned++;
			}
			// Guards already on it are busy too, so rule 4 does not pull them elsewhere.
			for (GuardEntity guard : this.roster) {
				if (threat.entity.getUUID().equals(guard.getMainTargetUuid())) {
					busy.add(guard);
				}
			}
		}
	}

	private void respondToRaid(MinecraftServer server, ServerPlayer player, CommanderService.OwnerContext context, long now) {
		Raid raid = player.level().getRaids().getNearbyRaid(player.blockPosition(), RAID_RADIUS * RAID_RADIUS);
		boolean active = raid != null && raid.isActive();
		if (active && !this.raidActive) {
			CommanderCallouts.announce(server, this.owner, CommanderCallouts.text("guardvillagers.commander.raid",
					"Raid at the village. All squads to the bell."), 5);
		}
		if (active) {
			BlockPos post = this.bell != null ? this.bell : raid.getCenter();
			Map<Integer, int[]> roles = new HashMap<>();
			for (GuardEntity guard : this.roster) {
				if (guard.getHome().isPresent() && !guard.hasFollowOverride()) {
					int[] counts = roles.computeIfAbsent(guard.getGroupIndex(), ignored -> new int[2]);
					counts[guard.getRole() == GuardRole.BOWMAN ? 1 : 0]++;
				}
			}
			GuardCommandState state = GuardHierarchy.state(server);
			GuardCommandState.OwnerCommand command = null;
			boolean changed = false;
			for (GuardEntity guard : this.roster) {
				if (this.controls.get(guard) != Control.FREE || guard.getHome().isEmpty() || guard.hasFollowOverride()) {
					continue;
				}
				int[] counts = roles.get(guard.getGroupIndex());
				GuardBehavior wanted = counts != null && counts[0] >= counts[1] ? GuardBehavior.OFFENSIVE : GuardBehavior.DEFENSIVE;
				if (guard.getBehavior() != wanted) {
					// Saved, so the change is put back even if the guard unloads or the owner leaves before the raid ends.
					command = command != null ? command : state.getOrCreate(this.owner);
					changed |= command.recordRaidBehavior(guard.getUUID(), guard.getBehavior(), wanted);
					guard.setBehavior(wanted);
				}
				if (!this.raidActive && guard.getTarget() == null) {
					this.rally(guard, post, 240, now);
				}
			}
			if (changed) {
				state.setDirty();
			}
		} else {
			restoreRaidBehaviors(server, this.owner, context.book.active());
		}
		this.raidActive = active;
	}

	/**
	 * Puts back what raid responses changed, for every loaded guard of the owner: only where the behavior is still the
	 * one the raid set and no player order (defend, patrol) has set it since. Entries for guards that are not loaded
	 * wait for them.
	 */
	static void restoreRaidBehaviors(MinecraftServer server, UUID owner, List<ActiveOrder> active) {
		GuardCommandState state = GuardHierarchy.state(server);
		GuardCommandState.OwnerCommand command = state.peek(owner);
		if (command == null || command.raidBehaviors().isEmpty()) {
			return;
		}
		List<GuardEntity> guards = CommanderService.livingGuards(server, owner);
		GuardEntity commander = GuardHierarchy.commanderAmong(server, owner, guards);
		boolean changed = false;
		for (GuardEntity guard : guards) {
			GuardCommandState.RaidBehavior saved = command.raidBehaviors().get(guard.getUUID());
			if (saved == null) {
				continue;
			}
			if (guard.getBehavior() == saved.applied() && !behaviorOrdered(guard, active, commander)) {
				guard.setBehavior(saved.original());
			}
			command.clearRaidBehavior(guard.getUUID());
			changed = true;
		}
		if (changed) {
			state.setDirty();
		}
	}

	private static boolean behaviorOrdered(GuardEntity guard, List<ActiveOrder> active, GuardEntity commander) {
		for (ActiveOrder order : active) {
			Action action = order.order().action();
			if ((action == Action.DEFEND_VILLAGE || action == Action.PATROL_HERE)
					&& OrderExecutor.covers(order.order().scope(), guard, commander)) {
				return true;
			}
		}
		return false;
	}

	/** Up to two healthy following guards nearest the owner stay with them while squads fight elsewhere. */
	private Set<GuardEntity> pickBodyguards(ServerPlayer player, Set<GuardEntity> busy) {
		if (this.roster.size() < 4 || this.threats.isEmpty()) {
			return Set.of();
		}
		List<GuardEntity> candidates = new ArrayList<>();
		for (GuardEntity guard : this.roster) {
			if (this.controls.get(guard) == Control.FREE && !busy.contains(guard) && guard.getHome().isEmpty()
					&& !this.wounded.contains(guard.getUUID())) {
				candidates.add(guard);
			}
		}
		candidates.sort((a, b) -> {
			int role = Boolean.compare(a.getRole() == GuardRole.BOWMAN, b.getRole() == GuardRole.BOWMAN);
			return role != 0 ? role : Double.compare(a.distanceToSqr(player), b.distanceToSqr(player));
		});
		Set<GuardEntity> picked = new HashSet<>();
		for (int i = 0; i < candidates.size() && picked.size() < 2 && picked.size() < this.roster.size() / 4; i++) {
			picked.add(candidates.get(i));
		}
		return picked;
	}

	/** Rules 4 and 5: greedy, highest score first, nearest suitable free guards. */
	private void assignThreats(MinecraftServer server, ServerPlayer player, Set<GuardEntity> busy, Set<GuardEntity> bodyguards) {
		Map<Threat, List<GuardEntity>> sent = new LinkedHashMap<>();
		for (Threat threat : this.threats) {
			int needed = Math.min(threat.need, this.roster.size()) - threat.assigned;
			if (threat.engagedCount > 0 && threat.engagedHealth / threat.engagedCount < REINFORCE_HEALTH) {
				needed = Math.max(needed, 2);
			}
			if (needed <= 0) {
				continue;
			}
			Set<GuardEntity> excluded = new HashSet<>(busy);
			excluded.addAll(bodyguards);
			List<GuardEntity> chosen = this.nearestAvailable(threat, excluded, false, needed);
			for (GuardEntity guard : chosen) {
				guard.setMainTarget(threat.entity);
				this.assignments.put(guard.getUUID(), threat.entity.getUUID());
				busy.add(guard);
				threat.assigned++;
			}
			if (!chosen.isEmpty()) {
				sent.put(threat, chosen);
			}
		}
		this.announceThreats(server, sent);
	}

	/**
	 * Free guards that can legally take {@code threat}, best first:
	 * preferred role, then distance. Wounded guards are never chosen.
	 */
	private List<GuardEntity> nearestAvailable(Threat threat, Set<GuardEntity> excluded, boolean ownerDefence, int count) {
		if (count <= 0) {
			return List.of();
		}
		List<GuardEntity> candidates = new ArrayList<>();
		for (GuardEntity guard : this.roster) {
			Control control = this.controls.get(guard);
			if (excluded.contains(guard) || this.wounded.contains(guard.getUUID())
					|| control != Control.FREE) {
				continue;
			}
			if (!ownerDefence && !this.canReach(guard, threat)) {
				continue;
			}
			UUID current = guard.getMainTargetUuid();
			if (current != null && !current.equals(threat.entity.getUUID()) && guard.getTarget() != null && guard.getTarget().isAlive()) {
				// Already fighting something else; leave it to finish.
				continue;
			}
			candidates.add(guard);
		}
		GuardRole preferred = switch (threat.kind) {
			case RANGED -> GuardRole.SWORDSMAN;
			case MELEE, CREEPER -> GuardRole.BOWMAN;
			default -> null;
		};
		candidates.sort((a, b) -> Double.compare(cost(a, threat, preferred), cost(b, threat, preferred)));
		return candidates.size() > count ? candidates.subList(0, count) : candidates;
	}

	private static double cost(GuardEntity guard, Threat threat, GuardRole preferred) {
		double distance = Math.sqrt(guard.distanceToSqr(threat.entity));
		return preferred != null && guard.getRole() != preferred ? distance + 12.0D : distance;
	}

	/** Following guards stay on their leash; homed guards stay inside their zone. */
	private boolean canReach(GuardEntity guard, Threat threat) {
		Optional<BlockPos> home = guard.getHome();
		if (home.isPresent() && !guard.hasFollowOverride() && guard.getPatrolRadius() > 0) {
			return guard.canTargetWithinZone(threat.entity.blockPosition());
		}
		return threat.ownerDistanceSq <= FOLLOW_LEASH * FOLLOW_LEASH;
	}

	private void announceThreats(MinecraftServer server, Map<Threat, List<GuardEntity>> sent) {
		Threat first = null;
		List<GuardEntity> who = new ArrayList<>();
		Map<String, Integer> nouns = new LinkedHashMap<>();
		for (Map.Entry<Threat, List<GuardEntity>> entry : sent.entrySet()) {
			if (!this.announcedThreats.add(entry.getKey().entity.getUUID())) {
				continue;
			}
			if (first == null) {
				first = entry.getKey();
			}
			nouns.merge(entry.getKey().noun(), 1, Integer::sum);
			who.addAll(entry.getValue());
		}
		if (first == null) {
			return;
		}
		if (this.announcedThreats.size() > 256) {
			this.announcedThreats.clear();
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> noun : nouns.entrySet()) {
			if (parts.size() == 2) {
				break;
			}
			parts.add(noun.getValue() > 1 ? noun.getKey() + "s" : (parts.isEmpty() ? "a " : "") + noun.getKey());
		}
		String what = String.join(" and ", parts);
		String where = this.bell != null
				? CommanderCallouts.text("guardvillagers.commander.where_village", "at the %s wall", first.sectorName())
				: CommanderCallouts.text("guardvillagers.commander.where_owner", "to your %s", first.sectorName());
		String line = CommanderCallouts.text("guardvillagers.commander.threat", "%s %s. Sending %s.",
				capitalize(what), where, describe(who));
		CommanderCallouts.announce(server, this.owner, line, 4);
		this.clearAnnounced = false;
	}

	/** Rule 6: wounded free guards fall back to the owner (or the bell) until they heal past 50%. */
	private void pullBackWounded(ServerPlayer player, long now) {
		if (this.wounded.isEmpty()) {
			return;
		}
		BlockPos safe = player.blockPosition();
		for (GuardEntity guard : this.roster) {
			if (!this.wounded.contains(guard.getUUID()) || this.controls.get(guard) != Control.FREE) {
				continue;
			}
			BlockPos point = guard.getHome().isPresent() && !guard.hasFollowOverride() && this.bell != null ? this.bell : safe;
			if (guard.blockPosition().distSqr(point) > 9.0D) {
				this.rally(guard, point, PLAN_INTERVAL + 20, now);
			}
		}
	}

	/** Rule 7: free bowmen caught between the owner and a nearby threat step back behind the owner. */
	private void archersBehindOwner(ServerPlayer player, Set<GuardEntity> busy, long now) {
		Threat nearest = null;
		for (Threat threat : this.threats) {
			if (threat.ownerDistanceSq <= FOLLOW_LEASH * FOLLOW_LEASH && (nearest == null || threat.ownerDistanceSq < nearest.ownerDistanceSq)) {
				nearest = threat;
			}
		}
		if (nearest == null) {
			return;
		}
		Vec3 away = player.position().subtract(nearest.entity.position());
		away = new Vec3(away.x, 0.0D, away.z);
		if (away.lengthSqr() < 1.0E-4D) {
			return;
		}
		Vec3 behind = player.position().add(away.normalize().scale(4.0D));
		BlockPos post = BlockPos.containing(behind.x, player.getY(), behind.z);
		double ownerToThreat = Math.sqrt(nearest.ownerDistanceSq);
		for (GuardEntity guard : this.roster) {
			if (guard.getRole() != GuardRole.BOWMAN || busy.contains(guard) || this.controls.get(guard) != Control.FREE
					|| guard.getHome().isPresent() || guard.getTarget() != null) {
				continue;
			}
			if (Math.sqrt(guard.distanceToSqr(nearest.entity)) < ownerToThreat + 2.0D) {
				this.rally(guard, post, 60, now);
			}
		}
	}

	/** Rule 8: quiet for 30 s, homed free guards walk a four-point loop around home, one leg a minute. */
	private void rotatePatrols(long now) {
		if (now - this.lastThreatTick < QUIET_BEFORE_PATROL_TICKS || now - this.lastPatrolTick < PATROL_ROTATE_TICKS) {
			return;
		}
		this.lastPatrolTick = now;
		this.patrolStep = (this.patrolStep + 1) & 3;
		int[][] steps = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
		for (GuardEntity guard : this.roster) {
			Optional<BlockPos> home = guard.getHome();
			if (home.isEmpty() || guard.hasFollowOverride() || guard.getPatrolRadius() < 8
					|| this.controls.get(guard) != Control.FREE || guard.getBehavior() == GuardBehavior.CROWD_CONTROL) {
				continue;
			}
			int reach = (int) (guard.getPatrolRadius() * 0.6D);
			int[] step = steps[this.patrolStep];
			this.rally(guard, home.get().offset(step[0] * reach, 0, step[1] * reach), 200, now);
		}
	}

	private void announceClear(MinecraftServer server, long now) {
		if (!this.clearAnnounced && this.threats.isEmpty() && now - this.lastThreatTick >= AREA_CLEAR_TICKS) {
			this.clearAnnounced = true;
			this.announcedThreats.clear();
			CommanderCallouts.announce(server, this.owner, CommanderCallouts.text("guardvillagers.commander.clear", "Area clear."), 1);
		}
	}

	private void rally(GuardEntity guard, BlockPos point, int ticks, long now) {
		guard.rallyTo(point, ticks);
		this.ownRallies.put(guard.getUUID(), now + ticks);
	}

	// ---- reporting ----

	String statusLine(MinecraftServer server, ServerPlayer player, List<GuardEntity> guards, GuardEntity commander) {
		int woundedCount = 0;
		Map<String, Integer> holding = new LinkedHashMap<>();
		for (GuardEntity guard : guards) {
			if (guard.getHealth() < guard.getMaxHealth() * WOUNDED_EXIT) {
				woundedCount++;
			}
			if (guard.isHoldingPosition() || guard.isStaying()) {
				holding.merge(guard.getGroupIndex() >= 0 ? guard.getGroupName() : "others", 1, Integer::sum);
			}
		}
		StringBuilder line = new StringBuilder();
		line.append(guards.size()).append(guards.size() == 1 ? " guard" : " guards");
		if (commander != null) {
			line.append(" under ").append(commander.getName().getString());
		}
		line.append(", ").append(woundedCount).append(" wounded.");
		if (!holding.isEmpty()) {
			line.append(" Holding: ").append(String.join(", ", holding.keySet())).append('.');
		}
		if (this.threats.isEmpty()) {
			line.append(' ').append(CommanderCallouts.text("guardvillagers.commander.status_quiet", "All quiet."));
		} else {
			line.append(' ').append(CommanderCallouts.text("guardvillagers.commander.status_threats", "%s hostiles, closest to the %s.",
					this.threats.size(), this.closestThreat().sectorName()));
		}
		if (!CommanderService.autoCommand(server, this.owner)) {
			line.append(" Awaiting your orders.");
		}
		return line.toString();
	}

	private Threat closestThreat() {
		Threat best = this.threats.getFirst();
		for (Threat threat : this.threats) {
			if (threat.ownerDistanceSq < best.ownerDistanceSq) {
				best = threat;
			}
		}
		return best;
	}

	private static String describe(List<GuardEntity> guards) {
		if (guards.size() == 1) {
			return guards.getFirst().getName().getString();
		}
		int group = guards.getFirst().getGroupIndex();
		boolean sameGroup = group >= 0;
		boolean allBowmen = true;
		boolean allSwords = true;
		for (GuardEntity guard : guards) {
			sameGroup &= guard.getGroupIndex() == group;
			allBowmen &= guard.getRole() == GuardRole.BOWMAN;
			allSwords &= guard.getRole() == GuardRole.SWORDSMAN;
		}
		if (sameGroup) {
			return guards.getFirst().getGroupName();
		}
		if (allBowmen) {
			return "the archers";
		}
		if (allSwords) {
			return "the swordsmen";
		}
		return guards.size() + " guards";
	}

	private static String capitalize(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	// Visible for tests.
	List<Threat> threats() {
		return this.threats;
	}

	Map<UUID, UUID> assignments() {
		return this.assignments;
	}

	boolean isWounded(UUID guard) {
		return this.wounded.contains(guard);
	}
}
