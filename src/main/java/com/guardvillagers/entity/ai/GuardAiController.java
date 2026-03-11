package com.guardvillagers.entity.ai;

import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.raid.Raid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GuardAiController {
	private static final float RETREAT_ENTER_HEALTH_RATIO = 0.25F;
	private static final float RETREAT_EXIT_HEALTH_RATIO = 0.40F;
	private static final float ALERT_REJECTION_HEALTH_RATIO = 0.30F;
	private static final int MIN_OWNER_ALERT_RANGE = 8;
	private static final int HOSTILE_SCAN_RANGE = 20;
	private static final int RAID_SCAN_RANGE = 48;
	private static final int TARGET_LOS_GRACE_TICKS = 40;
	private static final int TARGET_STUCK_GRACE_TICKS = 60;
	private static final double TARGET_PROGRESS_MIN_SQUARED = 0.01D;
	private static final double TARGET_PROGRESS_RESET_DISTANCE_SQUARED = 9.0D;
	private static final int TARGET_SUPPRESSION_TICKS = 40;
	private static final int OWNER_HOSTILITY_CHECK_RANGE_SQUARED = 256;
	private static final int FOLLOW_HOME_EXIT_BUFFER = 8;

	private static final Comparator<TargetCandidate> TARGET_COMPARATOR = Comparator
			.comparing(TargetCandidate::urgent)
			.reversed()
			.thenComparingInt(TargetCandidate::sourcePriority)
			.reversed()
			.thenComparingInt(TargetCandidate::threatPriority)
			.reversed()
			.thenComparing(TargetCandidate::sticky)
			.reversed()
			.thenComparingLong(TargetCandidate::latestTick)
			.reversed()
			.thenComparingDouble(TargetCandidate::distanceSq)
			.thenComparing(candidate -> candidate.entity().getUuid().toString());

	private final GuardEntity guard;
	private final List<GuardAlert> alerts = new ArrayList<>();
	private final Map<UUID, Long> suppressedTargets = new HashMap<>();

	private GuardAiIntent currentIntent = GuardAiIntent.IDLE;
	private GuardBehaviorExecutor behaviorExecutor = GuardBehaviorExecutor.NONE;
	private UUID mainTargetUuid;
	private UUID urgentTargetUuid;
	private int combatCooldown;
	private int noSightTicks;
	private int stuckTargetTicks;
	private Vec3d lastProgressPos = Vec3d.ZERO;
	private long lastAcceptedOwnerAlertTick = Long.MIN_VALUE;
	private boolean combatSuspended;
	private boolean retreating;
	private BlockPos rallyPoint;
	private int rallyTicks;

	public GuardAiController(GuardEntity guard) {
		this.guard = guard;
	}

	public void tick(ServerWorld world) {
		this.tickTimers(world);
		this.tickCombatTracking(world);

		Decision decision = this.computeDecision(world);
		this.currentIntent = decision.intent();
		this.behaviorExecutor = decision.behaviorExecutor();
		this.mainTargetUuid = decision.mainTargetUuid();
		this.urgentTargetUuid = decision.urgentTargetUuid();
		this.retreating = decision.intent() == GuardAiIntent.RETREAT;
		this.applyActiveTarget(decision.combatTarget());

		if (decision.intent() == GuardAiIntent.ENGAGE_TARGET && decision.combatTarget() != null) {
			this.combatCooldown = Math.max(this.combatCooldown, 80);
			if (this.guard.age % 20 == 0) {
				this.broadcastToAlliedGuards(world, decision.combatTarget(), AlertReason.ALLY_BROADCAST, world.getTime());
			}
		}
	}

	public GuardAiIntent getCurrentIntent() {
		return this.currentIntent;
	}

	public boolean isIntent(GuardAiIntent intent) {
		return this.currentIntent == intent;
	}

	public GuardBehaviorExecutor getBehaviorExecutor() {
		return this.behaviorExecutor;
	}

	public boolean isBehaviorExecutor(GuardBehaviorExecutor executor) {
		return this.behaviorExecutor == executor;
	}

	public UUID getMainTargetUuid() {
		return this.mainTargetUuid;
	}

	public UUID getUrgentTargetUuid() {
		return this.urgentTargetUuid;
	}

	public boolean isCombatSuspended() {
		return this.combatSuspended;
	}

	public void suspendCombat() {
		this.combatSuspended = true;
	}

	public void resumeCombat() {
		this.combatSuspended = false;
	}

	public int getCombatCooldown() {
		return this.combatCooldown;
	}

	public boolean hasActiveCombatTarget() {
		LivingEntity target = this.guard.getTarget();
		return target != null && target.isAlive();
	}

	public LivingEntity getTrackedCombatTarget(ServerWorld world) {
		LivingEntity urgent = this.resolveEntity(world, this.urgentTargetUuid);
		if (this.isTargetStillTrackable(urgent)) {
			return urgent;
		}
		LivingEntity main = this.resolveEntity(world, this.mainTargetUuid);
		return this.isTargetStillTrackable(main) ? main : null;
	}

	public boolean isRetreating() {
		return this.retreating;
	}

	public void setRetreating(boolean retreating) {
		this.retreating = retreating;
	}

	public void rallyTo(BlockPos rallyPoint, int ticks) {
		if (rallyPoint == null) {
			return;
		}
		this.rallyPoint = rallyPoint.toImmutable();
		this.rallyTicks = Math.max(0, ticks);
	}

	public Optional<BlockPos> getRallyPoint() {
		return Optional.ofNullable(this.rallyPoint);
	}

	public boolean canExecuteBehaviorGoals() {
		return this.currentIntent == GuardAiIntent.BEHAVIOR_PATROL_OR_ANCHOR;
	}

	public boolean canFollowOwnerFormation() {
		return this.currentIntent == GuardAiIntent.FOLLOW_OWNER;
	}

	public boolean shouldTacticallyRetreat() {
		return this.currentIntent == GuardAiIntent.RETREAT;
	}

	public boolean shouldContinueRetreat() {
		return this.currentIntent == GuardAiIntent.RETREAT;
	}

	public void clearCombatTarget() {
		this.alerts.clear();
		this.suppressedTargets.clear();
		this.mainTargetUuid = null;
		this.urgentTargetUuid = null;
		this.combatCooldown = 0;
		this.noSightTicks = 0;
		this.stuckTargetTicks = 0;
		this.lastProgressPos = this.guard.getEntityPos();
		this.guard.setTarget(null);
		this.guard.getNavigation().stop();
	}

	public void setMainTarget(LivingEntity target) {
		this.ingestAlert(target, AlertReason.DIRECTIVE_PRIMARY, this.currentWorldTime(), false);
	}

	public void setPriorityTarget(LivingEntity target) {
		long alertTick = this.currentWorldTime();
		this.ingestAlert(target, AlertReason.DIRECTIVE_PRIORITY, alertTick, true);
	}

	public void receiveOwnerAttackAlert(LivingEntity target, long alertTick) {
		if (!this.canAcceptOwnerAlert()) {
			return;
		}
		this.ingestAlert(target, AlertReason.OWNER_ATTACK, alertTick, true);
	}

	public void receiveOwnerDamagedAlert(LivingEntity target, long alertTick) {
		if (!this.canAcceptOwnerAlert()) {
			return;
		}
		this.ingestAlert(target, AlertReason.OWNER_DAMAGED, alertTick, true);
	}

	public void receiveAlliedAlert(LivingEntity target, long alertTick) {
		this.ingestAlert(target, AlertReason.ALLY_BROADCAST, alertTick, false);
	}

	public void receiveUrgentPeel(LivingEntity urgentThreat, ServerWorld world) {
		if (!this.isValidCombatTarget(urgentThreat, false) || this.hasReachedPeelCap(world)) {
			return;
		}
		this.ingestAlert(urgentThreat, AlertReason.URGENT_PEEL, world.getTime(), false);
	}

	public void receiveDamageAlert(LivingEntity attacker, long alertTick) {
		if (!this.isValidCombatTarget(attacker, false)) {
			return;
		}
		AlertReason reason = this.mainTargetUuid != null && !attacker.getUuid().equals(this.mainTargetUuid)
				? AlertReason.URGENT_PEEL
				: AlertReason.DIRECT_DAMAGE;
		this.ingestAlert(attacker, reason, alertTick, true);
	}

	private Decision computeDecision(ServerWorld world) {
		Map<UUID, TargetAccumulator> candidates = new HashMap<>();
		LivingEntity currentTarget = this.guard.getTarget();
		this.addCandidate(candidates, currentTarget, AlertReason.CURRENT_TARGET, world.getTime(), true);

		for (GuardAlert alert : this.alerts) {
			LivingEntity entity = this.resolveEntity(world, alert.targetUuid());
			this.addCandidate(candidates, entity, alert.reason(), alert.alertTick(), false);
		}

		Raid raid = this.getActiveRaid(world);
		this.addCandidate(candidates, this.findNearestRaidTarget(world), AlertReason.RAID_TARGET, world.getTime(), false);
		this.addCandidate(candidates, this.findNearbyHostile(world), AlertReason.HOSTILE_SCAN, world.getTime(), false);
		this.addCandidate(candidates, this.resolveOwnerHostilityTarget(world), AlertReason.OWNER_HOSTILE, world.getTime(), false);

		List<TargetCandidate> sorted = candidates.values()
				.stream()
				.map(TargetAccumulator::freeze)
				.sorted(TARGET_COMPARATOR)
				.toList();

		TargetCandidate selected = sorted.isEmpty() ? null : sorted.getFirst();
		TargetCandidate urgent = sorted.stream().filter(TargetCandidate::urgent).findFirst().orElse(null);
		TargetCandidate primary = sorted.stream().filter(candidate -> !candidate.urgent()).findFirst().orElse(selected);

		LivingEntity combatTarget = selected == null || this.combatSuspended ? null : selected.entity();
		GuardAiIntent intent = this.selectIntent(world, combatTarget, raid);
		GuardBehaviorExecutor executor = intent == GuardAiIntent.BEHAVIOR_PATROL_OR_ANCHOR
				? this.selectBehaviorExecutor(world, raid)
				: GuardBehaviorExecutor.NONE;

		return new Decision(
				intent,
				executor,
				combatTarget,
				primary == null ? null : primary.entity().getUuid(),
				urgent == null ? null : urgent.entity().getUuid());
	}

	private GuardAiIntent selectIntent(ServerWorld world, LivingEntity combatTarget, Raid raid) {
		if (this.shouldSeekAir()) {
			return GuardAiIntent.SEEK_AIR;
		}
		if (this.shouldReturnToLand()) {
			return GuardAiIntent.RETURN_TO_LAND;
		}
		if (this.shouldRetreat(combatTarget)) {
			return GuardAiIntent.RETREAT;
		}
		if (combatTarget != null) {
			return GuardAiIntent.ENGAGE_TARGET;
		}
		if (this.rallyPoint != null && this.rallyTicks > 0) {
			return GuardAiIntent.RALLY;
		}
		if (this.shouldFollowOwner(world)) {
			return GuardAiIntent.FOLLOW_OWNER;
		}
		if (this.selectBehaviorExecutor(world, raid) != GuardBehaviorExecutor.NONE) {
			return GuardAiIntent.BEHAVIOR_PATROL_OR_ANCHOR;
		}
		return GuardAiIntent.IDLE;
	}

	private GuardBehaviorExecutor selectBehaviorExecutor(ServerWorld world, Raid raid) {
		if (this.guard.isStaying()) {
			return GuardBehaviorExecutor.NONE;
		}

		Optional<BlockPos> home = this.guard.getHome();
		if (home.isPresent() && this.guard.getPatrolRadius() > 0 && !this.guard.hasFollowOverride()) {
			int tetherRange = this.guard.getPatrolRadius() + FOLLOW_HOME_EXIT_BUFFER;
			if (home.get().getSquaredDistance(this.guard.getBlockPos()) > (double) tetherRange * tetherRange) {
				return GuardBehaviorExecutor.HOME_ANCHOR;
			}
		}

		GuardBehavior behavior = this.guard.getBehavior();
		if (raid != null) {
			if (behavior == GuardBehavior.OFFENSIVE) {
				return GuardBehaviorExecutor.RAID_OFFENSIVE;
			}
			if (behavior == GuardBehavior.DEFENSIVE) {
				return GuardBehaviorExecutor.RAID_DEFENSIVE;
			}
		}

		if (behavior == GuardBehavior.PERIMETER) {
			return GuardBehaviorExecutor.PERIMETER;
		}
		if (behavior == GuardBehavior.CROWD_CONTROL) {
			return GuardBehaviorExecutor.CROWD_CONTROL;
		}
		return GuardBehaviorExecutor.NONE;
	}

	private boolean shouldSeekAir() {
		return this.guard.isSubmergedInWater() && this.guard.getAir() < 60;
	}

	private boolean shouldReturnToLand() {
		return this.guard.getLastLandPos() != null
				&& this.guard.isTouchingWater()
				&& (this.guard.isSubmergedInWater() || !this.guard.isOnGround());
	}

	private boolean shouldRetreat(LivingEntity combatTarget) {
		float currentHealth = this.guard.getHealth();
		float maxHealth = this.guard.getMaxHealth();
		if (this.currentIntent == GuardAiIntent.RETREAT) {
			return currentHealth < maxHealth * RETREAT_EXIT_HEALTH_RATIO && (combatTarget != null || this.combatCooldown > 0);
		}
		return currentHealth <= maxHealth * RETREAT_ENTER_HEALTH_RATIO && (combatTarget != null || this.combatCooldown > 0);
	}

	private boolean shouldFollowOwner(ServerWorld world) {
		if (!this.guard.hasOwner() || this.guard.isStaying()) {
			return false;
		}
		ServerPlayerEntity owner = this.guard.resolveOwner(world);
		if (owner == null || owner.isSpectator()) {
			return false;
		}
		if (!GuardReputationManager.isTrustedByGuards(world, this.guard.getOwnerUuid(), this.guard.getBlockPos())) {
			return false;
		}
		return this.guard.hasFollowOverride() || this.guard.getHome().isEmpty();
	}

	private void applyActiveTarget(LivingEntity target) {
		if (this.currentIntent != GuardAiIntent.ENGAGE_TARGET || target == null) {
			this.guard.setTarget(null);
			return;
		}
		this.guard.setTarget(target);
		if (this.lastProgressPos == Vec3d.ZERO) {
			this.lastProgressPos = this.guard.getEntityPos();
		}
	}

	private void tickTimers(ServerWorld world) {
		if (this.combatCooldown > 0) {
			this.combatCooldown--;
		}
		if (this.rallyTicks > 0) {
			this.rallyTicks--;
		}
		if (this.rallyTicks <= 0) {
			this.rallyTicks = 0;
			this.rallyPoint = null;
		}

		long now = world.getTime();
		this.alerts.removeIf(alert -> alert.expiresAt() <= now || !this.isTargetStillTrackable(this.resolveEntity(world, alert.targetUuid())));

		Iterator<Map.Entry<UUID, Long>> iterator = this.suppressedTargets.entrySet().iterator();
		while (iterator.hasNext()) {
			if (iterator.next().getValue() <= now) {
				iterator.remove();
			}
		}
	}

	private void tickCombatTracking(ServerWorld world) {
		LivingEntity target = this.guard.getTarget();
		if (!this.isTargetStillTrackable(target)) {
			this.noSightTicks = 0;
			this.stuckTargetTicks = 0;
			this.lastProgressPos = this.guard.getEntityPos();
			return;
		}

		if (this.guard.canSee(target) || this.guard.getGuardNavigation().isCrowdBlocked()) {
			this.noSightTicks = 0;
		} else if (++this.noSightTicks > TARGET_LOS_GRACE_TICKS) {
			this.suppressTarget(target.getUuid(), world.getTime());
			this.guard.setTarget(null);
			this.noSightTicks = 0;
			this.stuckTargetTicks = 0;
			this.lastProgressPos = this.guard.getEntityPos();
			return;
		}

		if (this.guard.squaredDistanceTo(target) < TARGET_PROGRESS_RESET_DISTANCE_SQUARED) {
			this.lastProgressPos = this.guard.getEntityPos();
			this.stuckTargetTicks = 0;
			return;
		}

		if (this.guard.getGuardNavigation().isCrowdBlocked()) {
			this.lastProgressPos = this.guard.getEntityPos();
			this.stuckTargetTicks = 0;
			return;
		}

		if (this.guard.squaredDistanceTo(this.lastProgressPos) < TARGET_PROGRESS_MIN_SQUARED) {
			if (++this.stuckTargetTicks > TARGET_STUCK_GRACE_TICKS) {
				this.suppressTarget(target.getUuid(), world.getTime());
				this.guard.setTarget(null);
				this.noSightTicks = 0;
				this.stuckTargetTicks = 0;
				this.lastProgressPos = this.guard.getEntityPos();
			}
			return;
		}

		this.lastProgressPos = this.guard.getEntityPos();
		this.stuckTargetTicks = 0;
	}

	private void suppressTarget(UUID targetUuid, long currentTick) {
		if (targetUuid == null) {
			return;
		}
		this.suppressedTargets.put(targetUuid, currentTick + TARGET_SUPPRESSION_TICKS);
		this.alerts.removeIf(alert -> targetUuid.equals(alert.targetUuid()));
		if (targetUuid.equals(this.mainTargetUuid)) {
			this.mainTargetUuid = null;
		}
		if (targetUuid.equals(this.urgentTargetUuid)) {
			this.urgentTargetUuid = null;
		}
	}

	private void ingestAlert(LivingEntity target, AlertReason reason, long alertTick, boolean clearSuppression) {
		if (!this.isValidCombatTarget(target, reason.requiresSight())) {
			return;
		}
		if (reason.ownerDirected() && alertTick < this.lastAcceptedOwnerAlertTick) {
			return;
		}

		if (clearSuppression) {
			this.suppressedTargets.remove(target.getUuid());
		}

		long expiresAt = alertTick + reason.ttlTicks();
		for (int i = 0; i < this.alerts.size(); i++) {
			GuardAlert existing = this.alerts.get(i);
			if (existing.targetUuid().equals(target.getUuid()) && existing.reason() == reason) {
				this.alerts.set(i, new GuardAlert(target.getUuid(), reason, alertTick, Math.max(existing.expiresAt(), expiresAt)));
				this.combatCooldown = Math.max(this.combatCooldown, reason.cooldownTicks());
				if (reason.ownerDirected()) {
					this.lastAcceptedOwnerAlertTick = Math.max(this.lastAcceptedOwnerAlertTick, alertTick);
				}
				return;
			}
		}

		this.alerts.add(new GuardAlert(target.getUuid(), reason, alertTick, expiresAt));
		this.combatCooldown = Math.max(this.combatCooldown, reason.cooldownTicks());
		if (reason.ownerDirected()) {
			this.lastAcceptedOwnerAlertTick = Math.max(this.lastAcceptedOwnerAlertTick, alertTick);
		}
	}

	private void addCandidate(Map<UUID, TargetAccumulator> candidates, LivingEntity entity, AlertReason reason, long tick, boolean sticky) {
		if (!this.isValidCombatTarget(entity, reason.requiresSight())) {
			return;
		}
		TargetAccumulator accumulator = candidates.computeIfAbsent(entity.getUuid(), uuid -> new TargetAccumulator(entity));
		accumulator.include(reason, this.guard.squaredDistanceTo(entity), tick, sticky, this.scoreThreat(entity));
	}

	private LivingEntity findNearbyHostile(ServerWorld world) {
		Box searchBox = this.guard.getBoundingBox().expand(HOSTILE_SCAN_RANGE);
		List<HostileEntity> hostiles = world.getEntitiesByClass(
				HostileEntity.class,
				searchBox,
				entity -> this.isValidCombatTarget(entity, true));
		if (hostiles.isEmpty()) {
			return null;
		}
		hostiles.sort(Comparator
				.comparingInt((HostileEntity entity) -> this.scoreThreat(entity))
				.reversed()
				.thenComparingDouble((HostileEntity entity) -> this.guard.squaredDistanceTo(entity))
				.thenComparing(entity -> entity.getUuid().toString()));
		return hostiles.getFirst();
	}

	private LivingEntity findNearestRaidTarget(ServerWorld world) {
		if (this.getActiveRaid(world) == null) {
			return null;
		}
		List<RaiderEntity> raiders = world.getEntitiesByClass(
				RaiderEntity.class,
				this.guard.getBoundingBox().expand(RAID_SCAN_RANGE),
				entity -> this.isValidCombatTarget(entity, true));
		if (raiders.isEmpty()) {
			return null;
		}
		raiders.sort(Comparator
				.comparingDouble((RaiderEntity entity) -> this.guard.squaredDistanceTo(entity))
				.thenComparing(entity -> entity.getUuid().toString()));
		return raiders.getFirst();
	}

	private LivingEntity resolveOwnerHostilityTarget(ServerWorld world) {
		UUID ownerUuid = this.guard.getOwnerUuid();
		if (ownerUuid == null) {
			return null;
		}
		if (!GuardReputationManager.shouldGuardsTurnHostile(world, ownerUuid, this.guard.getBlockPos())) {
			return null;
		}
		ServerPlayerEntity owner = this.guard.resolveOwner(world);
		if (owner == null || owner.getAbilities().creativeMode) {
			return null;
		}
		if (this.guard.squaredDistanceTo(owner) > OWNER_HOSTILITY_CHECK_RANGE_SQUARED) {
			return null;
		}
		return owner;
	}

	private Raid getActiveRaid(ServerWorld world) {
		Raid raid = world.getRaidAt(this.guard.getBlockPos());
		return raid != null && raid.isActive() ? raid : null;
	}

	private boolean isValidCombatTarget(LivingEntity target, boolean requiresSight) {
		if (!this.isTargetStillTrackable(target)) {
			return false;
		}
		if (requiresSight && !this.guard.canSee(target)) {
			return false;
		}
		Long suppressedUntil = this.suppressedTargets.get(target.getUuid());
		return suppressedUntil == null || suppressedUntil <= this.currentWorldTime();
	}

	private boolean isTargetStillTrackable(LivingEntity target) {
		return target != null
				&& target.isAlive()
				&& !target.isRemoved()
				&& !this.guard.isAlly(target)
				&& this.guard.canTargetWithinZone(target.getBlockPos());
	}

	private LivingEntity resolveEntity(ServerWorld world, UUID uuid) {
		if (uuid == null) {
			return null;
		}
		Entity entity = world.getEntity(uuid);
		return entity instanceof LivingEntity living ? living : null;
	}

	private boolean canAcceptOwnerAlert() {
		if (this.guard.isStaying() || this.combatSuspended || this.retreating) {
			return false;
		}
		return !(this.guard.getHealth() <= this.guard.getMaxHealth() * ALERT_REJECTION_HEALTH_RATIO && this.combatCooldown <= 0);
	}

	private int scoreThreat(LivingEntity target) {
		int score = this.guard.scoreTarget(target);
		if (target instanceof RaiderEntity) {
			score += 20;
		}
		if (target instanceof RangedAttackMob
				|| target.getMainHandStack().isOf(Items.BOW)
				|| target.getMainHandStack().isOf(Items.CROSSBOW)
				|| target.getMainHandStack().isOf(Items.TRIDENT)) {
			score += 5;
		}
		return score;
	}

	private boolean hasReachedPeelCap(ServerWorld world) {
		int groupSize = 1;
		int peeling = 0;
		double range = Math.max(MIN_OWNER_ALERT_RANGE, this.guard.getAttributeValue(EntityAttributes.FOLLOW_RANGE));
		for (GuardEntity other : world.getEntitiesByClass(
				GuardEntity.class,
				this.guard.getBoundingBox().expand(range),
				entity -> entity != this.guard && entity.isAlive() && this.guard.isSameGroup(entity))) {
			groupSize++;
			if (other.getUrgentTargetUuid() != null) {
				peeling++;
			}
		}
		int peelCap = Math.max(1, Math.min(10, groupSize * 30 / 100));
		return peeling >= peelCap;
	}

	private void broadcastToAlliedGuards(ServerWorld world, LivingEntity target, AlertReason reason, long alertTick) {
		if (!this.isTargetStillTrackable(target)) {
			return;
		}

		double range = Math.max(MIN_OWNER_ALERT_RANGE, this.guard.getAttributeValue(EntityAttributes.FOLLOW_RANGE));
		double rangeSq = range * range;
		for (GuardEntity other : world.getEntitiesByClass(
				GuardEntity.class,
				this.guard.getBoundingBox().expand(range),
				entity -> entity != this.guard
						&& entity.isAlive()
						&& entity.squaredDistanceTo(this.guard) <= rangeSq
						&& this.guard.isSameGroup(entity))) {
			other.receiveAlliedAlert(target, alertTick);
		}

		if (target instanceof HostileEntity || target instanceof RaiderEntity) {
			for (IronGolemEntity golem : world.getEntitiesByClass(
					IronGolemEntity.class,
					this.guard.getBoundingBox().expand(24.0D),
					LivingEntity::isAlive)) {
				golem.setTarget(target);
			}
		}
	}

	private long currentWorldTime() {
		return this.guard.getEntityWorld() instanceof ServerWorld world ? world.getTime() : Long.MIN_VALUE;
	}

	private record GuardAlert(UUID targetUuid, AlertReason reason, long alertTick, long expiresAt) {
	}

	private enum AlertReason {
		DIRECTIVE_PRIMARY(85, 120, false, false, false, 100),
		DIRECTIVE_PRIORITY(92, 140, false, false, true, 120),
		OWNER_DAMAGED(90, 140, false, false, true, 120),
		OWNER_ATTACK(88, 120, false, false, true, 100),
		DIRECT_DAMAGE(95, 160, false, false, false, 160),
		URGENT_PEEL(100, 120, true, false, false, 120),
		ALLY_BROADCAST(80, 100, false, false, false, 100),
		OWNER_HOSTILE(82, 100, false, true, false, 100),
		RAID_TARGET(76, 40, false, true, false, 80),
		HOSTILE_SCAN(70, 20, false, true, false, 80),
		CURRENT_TARGET(84, 20, false, false, false, 80);

		private final int priority;
		private final int ttlTicks;
		private final boolean urgent;
		private final boolean requiresSight;
		private final boolean ownerDirected;
		private final int cooldownTicks;

		AlertReason(int priority, int ttlTicks, boolean urgent, boolean requiresSight, boolean ownerDirected, int cooldownTicks) {
			this.priority = priority;
			this.ttlTicks = ttlTicks;
			this.urgent = urgent;
			this.requiresSight = requiresSight;
			this.ownerDirected = ownerDirected;
			this.cooldownTicks = cooldownTicks;
		}

		public int priority() {
			return this.priority;
		}

		public int ttlTicks() {
			return this.ttlTicks;
		}

		public boolean urgent() {
			return this.urgent;
		}

		public boolean requiresSight() {
			return this.requiresSight;
		}

		public boolean ownerDirected() {
			return this.ownerDirected;
		}

		public int cooldownTicks() {
			return this.cooldownTicks;
		}
	}

	private static final class TargetAccumulator {
		private final LivingEntity entity;
		private boolean urgent;
		private boolean sticky;
		private int sourcePriority;
		private int threatPriority;
		private double distanceSq = Double.MAX_VALUE;
		private long latestTick = Long.MIN_VALUE;

		private TargetAccumulator(LivingEntity entity) {
			this.entity = entity;
		}

		private void include(AlertReason reason, double distanceSq, long tick, boolean sticky, int threatPriority) {
			this.urgent |= reason.urgent();
			this.sticky |= sticky;
			this.sourcePriority = Math.max(this.sourcePriority, reason.priority());
			this.threatPriority = Math.max(this.threatPriority, threatPriority);
			this.distanceSq = Math.min(this.distanceSq, distanceSq);
			this.latestTick = Math.max(this.latestTick, tick);
		}

		private TargetCandidate freeze() {
			return new TargetCandidate(
					this.entity,
					this.urgent,
					this.sticky,
					this.sourcePriority,
					this.threatPriority,
					this.distanceSq,
					this.latestTick);
		}
	}

	private record TargetCandidate(
			LivingEntity entity,
			boolean urgent,
			boolean sticky,
			int sourcePriority,
			int threatPriority,
			double distanceSq,
			long latestTick) {
	}

	private record Decision(
			GuardAiIntent intent,
			GuardBehaviorExecutor behaviorExecutor,
			LivingEntity combatTarget,
			UUID mainTargetUuid,
			UUID urgentTargetUuid) {
	}
}
