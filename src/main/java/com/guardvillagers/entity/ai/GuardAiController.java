package com.guardvillagers.entity.ai;

import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Single authority for a guard's high-level decisions: which target to fight, and which intent
 * (air, retreat, engage, rally, follow, return-to-land, behavior, idle) is active. Goals only
 * execute the chosen intent.
 *
 * <p>Cost model (runs every tick for every guard): world entity scans happen every
 * {@link #SCAN_INTERVAL_TICKS} ticks, staggered by entity id, and line-of-sight raycasts for scan
 * candidates happen only at scan time. Per-tick work is validating a handful of cached candidates
 * and scoring them, with reused collections.
 */
public final class GuardAiController {
	private static final float RETREAT_ENTER_HEALTH_RATIO = 0.25F;
	private static final float RETREAT_EXIT_HEALTH_RATIO = 0.40F;
	private static final float ALERT_REJECTION_HEALTH_RATIO = 0.30F;
	private static final int MIN_ALLY_ALERT_RANGE = 8;
	private static final double MAX_ALLY_ALERT_RANGE = 24.0D;
	private static final int HOSTILE_SCAN_RANGE = 20;
	private static final int RAID_SCAN_RANGE = 48;
	private static final int SCAN_INTERVAL_TICKS = 10;
	private static final int STATUS_INTERVAL_TICKS = 20;
	private static final int MAX_SCANNED_HOSTILES = 4;
	private static final int MAX_SCAN_SIGHT_CHECKS = 8;
	private static final int TARGET_LOS_GRACE_TICKS = 40;
	private static final int TARGET_LOS_GRACE_MOVING_TICKS = 100;
	private static final int PROGRESS_WINDOW_TICKS = 20;
	private static final int TARGET_STUCK_WINDOWS = 4;
	private static final double PROGRESS_MIN_DISTANCE = 0.5D;
	private static final double CLOSE_COMBAT_DISTANCE_SQUARED = 9.0D;
	private static final double BOW_HOLD_DISTANCE_SQUARED = 18.0D * 18.0D;
	private static final int TARGET_SUPPRESSION_TICKS = 40;
	private static final int FOLLOW_HOME_EXIT_BUFFER = 8;
	private static final int HOME_ANCHOR_SETTLE_MIN = 4;
	private static final double FOLLOW_COMBAT_LEASH = 24.0D;
	private static final double STAY_COMBAT_LEASH = 16.0D;
	private static final int BROADCAST_INTERVAL_TICKS = 40;
	private static final int ENGAGEMENT_REPORT_INTERVAL_TICKS = 10;
	private static final double GOLEM_ALERT_RANGE = 24.0D;
	private static final int CORNERED_TICKS = 40;
	private static final double HOLD_MELEE_REACH = 3.0D;
	private static final double HOLD_BOW_REACH = 16.0D;
	private static final int CORNERED_FIGHT_TICKS = 100;

	/** Shared across all guards; entries expire so unloaded guards cannot leak. */
	public static final GuardEngagementTracker ENGAGEMENTS = new GuardEngagementTracker(30, 4096);

	private final GuardEntity guard;
	private final List<GuardAlert> alerts = new ArrayList<>();
	private final Map<UUID, Long> suppressedTargets = new HashMap<>();
	private final List<TargetAccumulator> candidates = new ArrayList<>();
	private final List<TargetAccumulator> accumulatorPool = new ArrayList<>();
	private final List<LivingEntity> scannedHostiles = new ArrayList<>(MAX_SCANNED_HOSTILES);

	private GuardAiIntent currentIntent = GuardAiIntent.IDLE;
	private GuardBehaviorExecutor behaviorExecutor = GuardBehaviorExecutor.NONE;
	private UUID mainTargetUuid;
	private UUID urgentTargetUuid;
	private int combatCooldown;
	private int noSightTicks;
	private int progressWindowTicks;
	private int stuckWindows;
	private Vec3 progressSamplePos;
	private double progressSampleTargetDistance = Double.MAX_VALUE;
	private long lastAcceptedOwnerAlertTick = Long.MIN_VALUE;
	private boolean combatSuspended;
	private boolean retreating;
	private boolean homeAnchorActive;
	private BlockPos rallyPoint;
	private int rallyTicks;
	private int corneredTicks;
	private long retreatSuppressedUntil = Long.MIN_VALUE;

	private LivingEntity scannedRaidTarget;
	private Raid cachedRaid;
	private boolean scanInitialized;
	private boolean statusInitialized;
	private ServerPlayer followLeashOwner;

	private UUID engagedTargetUuid;
	private long lastEngagementReportTick = Long.MIN_VALUE;
	private int engagementRank;
	private UUID lastBroadcastTargetUuid;
	private long lastBroadcastTick = Long.MIN_VALUE;

	public GuardAiController(GuardEntity guard) {
		this.guard = guard;
	}

	public void tick(ServerLevel world) {
		long now = world.getGameTime();
		this.tickTimers(world, now);
		this.refreshStatus(world, now);
		this.refreshScans(world, now);
		this.tickCombatTracking(world, now);

		LivingEntity combatTarget = this.selectTargets(world, now);
		GuardAiIntent intent = this.selectIntent(world, combatTarget);
		this.currentIntent = intent;
		this.behaviorExecutor = intent == GuardAiIntent.BEHAVIOR_PATROL_OR_ANCHOR
				? this.selectBehaviorExecutor()
				: GuardBehaviorExecutor.NONE;
		if (this.behaviorExecutor != GuardBehaviorExecutor.HOME_ANCHOR) {
			this.homeAnchorActive = false;
		}
		this.retreating = intent == GuardAiIntent.RETREAT;
		LivingEntity activeTarget = intent == GuardAiIntent.ENGAGE_TARGET ? combatTarget : null;
		this.applyActiveTarget(activeTarget);
		this.updateEngagement(activeTarget, now);

		if (activeTarget != null) {
			this.combatCooldown = Math.max(this.combatCooldown, 80);
			if (!activeTarget.getUUID().equals(this.lastBroadcastTargetUuid)
					|| now - this.lastBroadcastTick >= BROADCAST_INTERVAL_TICKS) {
				this.lastBroadcastTargetUuid = activeTarget.getUUID();
				this.lastBroadcastTick = now;
				this.broadcastToAlliedGuards(world, activeTarget, now);
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

	/** How many other guards engaging the same target are closer to it (0 = closest). */
	public int getEngagementRank() {
		return this.engagementRank;
	}

	/** Forces owner/follow status to be recomputed next tick (after an order changes it). */
	public void invalidateStatus() {
		this.statusInitialized = false;
		this.homeAnchorActive = false;
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

	public LivingEntity getTrackedCombatTarget(ServerLevel world) {
		LivingEntity urgent = this.resolveEntity(world, this.urgentTargetUuid);
		if (this.isTargetStillTrackable(urgent, true)) {
			return urgent;
		}
		LivingEntity main = this.resolveEntity(world, this.mainTargetUuid);
		return this.isTargetStillTrackable(main, true) ? main : null;
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
		this.rallyPoint = rallyPoint.immutable();
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
		this.scannedHostiles.clear();
		this.scannedRaidTarget = null;
		this.mainTargetUuid = null;
		this.urgentTargetUuid = null;
		this.combatCooldown = 0;
		this.resetCombatTracking();
		this.releaseEngagement();
		this.guard.setTarget(null);
		this.guard.getNavigation().stop();
	}

	public void setMainTarget(LivingEntity target) {
		this.ingestAlert(target, AlertReason.DIRECTIVE_PRIMARY, this.currentWorldTime(), false);
	}

	public void setPriorityTarget(LivingEntity target) {
		this.ingestAlert(target, AlertReason.DIRECTIVE_PRIORITY, this.currentWorldTime(), true);
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

	/**
	 * Peel order from an ally. The caller (the guard that was hit) enforces the group peel cap, so
	 * this no longer runs its own entity scan per recipient.
	 */
	public void receiveUrgentPeel(LivingEntity urgentThreat, ServerLevel world) {
		this.ingestAlert(urgentThreat, AlertReason.URGENT_PEEL, world.getGameTime(), false);
	}

	public void receiveDamageAlert(LivingEntity attacker, long alertTick) {
		if (attacker == null) {
			return;
		}
		boolean differentFromMain = this.mainTargetUuid != null && !attacker.getUUID().equals(this.mainTargetUuid);
		boolean closeThreat = this.guard.distanceToSqr(attacker) <= CLOSE_COMBAT_DISTANCE_SQUARED;
		// Only a nearby attacker pulls the guard off its current fight; a distant archer is left to
		// the peel squad and normal scoring.
		AlertReason reason = differentFromMain && closeThreat ? AlertReason.URGENT_PEEL : AlertReason.DIRECT_DAMAGE;
		this.ingestAlert(attacker, reason, alertTick, true);
	}

	private LivingEntity selectTargets(ServerLevel world, long now) {
		this.releaseCandidates();
		LivingEntity currentTarget = this.guard.getTarget();
		this.addCandidate(currentTarget, AlertReason.CURRENT_TARGET, now, true);
		for (int i = 0; i < this.alerts.size(); i++) {
			GuardAlert alert = this.alerts.get(i);
			this.addCandidate(this.resolveEntity(world, alert.targetUuid()), alert.reason(), alert.alertTick(), false);
		}
		this.addCandidate(this.scannedRaidTarget, AlertReason.RAID_TARGET, now, false);
		for (int i = 0; i < this.scannedHostiles.size(); i++) {
			this.addCandidate(this.scannedHostiles.get(i), AlertReason.HOSTILE_SCAN, now, false);
		}

		UUID self = this.guard.getUUID();
		TargetAccumulator best = null;
		TargetAccumulator bestUrgent = null;
		TargetAccumulator bestPrimary = null;
		for (int i = 0; i < this.candidates.size(); i++) {
			TargetAccumulator candidate = this.candidates.get(i);
			int engaged = ENGAGEMENTS.count(candidate.entity.getUUID(), self, now);
			candidate.score = TargetScoring.score(candidate.urgent, candidate.ownerDirected, candidate.sourcePriority,
					candidate.threatPriority,
					candidate.sticky, candidate.distanceSq, engaged);
			if (best == null || candidate.isBetterThan(best)) {
				best = candidate;
			}
			if (candidate.urgent) {
				if (bestUrgent == null || candidate.isBetterThan(bestUrgent)) {
					bestUrgent = candidate;
				}
			} else if (bestPrimary == null || candidate.isBetterThan(bestPrimary)) {
				bestPrimary = candidate;
			}
		}
		if (bestPrimary == null) {
			bestPrimary = best;
		}
		this.mainTargetUuid = bestPrimary == null ? null : bestPrimary.entity.getUUID();
		this.urgentTargetUuid = bestUrgent == null ? null : bestUrgent.entity.getUUID();
		return best == null || this.combatSuspended ? null : best.entity;
	}

	private GuardAiIntent selectIntent(ServerLevel world, LivingEntity combatTarget) {
		if (this.shouldSeekAir()) {
			return GuardAiIntent.SEEK_AIR;
		}
		if (this.guard.isHoldingPosition()) {
			// Hold: fight what is in reach, otherwise stand (IDLE re-centres on the hold block).
			return combatTarget != null ? GuardAiIntent.ENGAGE_TARGET : GuardAiIntent.IDLE;
		}
		if (this.shouldRetreat(combatTarget, world.getGameTime())) {
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
		// Below follow/engage on purpose: a guard crossing a river behind its owner, or chasing a
		// target through water, must not be dragged back to the bank it came from.
		if (this.shouldReturnToLand()) {
			return GuardAiIntent.RETURN_TO_LAND;
		}
		if (this.peekBehaviorExecutor() != GuardBehaviorExecutor.NONE) {
			return GuardAiIntent.BEHAVIOR_PATROL_OR_ANCHOR;
		}
		return GuardAiIntent.IDLE;
	}

	private GuardBehaviorExecutor peekBehaviorExecutor() {
		boolean wasAnchoring = this.homeAnchorActive;
		GuardBehaviorExecutor executor = this.selectBehaviorExecutor();
		this.homeAnchorActive = wasAnchoring;
		return executor;
	}

	private GuardBehaviorExecutor selectBehaviorExecutor() {
		if (this.guard.isStaying()) {
			return GuardBehaviorExecutor.NONE;
		}

		Optional<BlockPos> home = this.guard.getHome();
		if (home.isPresent() && this.guard.getPatrolRadius() > 0 && !this.guard.hasFollowOverride()) {
			double distanceSq = home.get().distSqr(this.guard.blockPosition());
			int radius = this.guard.getPatrolRadius();
			// Hysteresis: start anchoring past radius + buffer, keep going until well inside, so a
			// patrol point near the border cannot bounce the guard in and out every tick.
			int threshold = this.homeAnchorActive
					? Math.max(HOME_ANCHOR_SETTLE_MIN, radius / 2)
					: radius + FOLLOW_HOME_EXIT_BUFFER;
			if (distanceSq > (double) threshold * threshold) {
				this.homeAnchorActive = true;
				return GuardBehaviorExecutor.HOME_ANCHOR;
			}
		}
		this.homeAnchorActive = false;

		GuardBehavior behavior = this.guard.getBehavior();
		if (this.cachedRaid != null && this.cachedRaid.isActive()) {
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
		return this.guard.isUnderWater() && this.guard.getAirSupply() < 60;
	}

	private boolean shouldReturnToLand() {
		return this.guard.isInWater() && (this.guard.isUnderWater() || !this.guard.onGround());
	}

	/**
	 * Retreat at low health, except when cornered: if a threat stays within reach for
	 * {@link #CORNERED_TICKS} while retreating, the guard turns and fights for a while instead of
	 * dying with its back turned.
	 */
	private boolean shouldRetreat(LivingEntity combatTarget, long now) {
		if (now < this.retreatSuppressedUntil) {
			return false;
		}
		float currentHealth = this.guard.getHealth();
		float maxHealth = this.guard.getMaxHealth();
		boolean inDanger = combatTarget != null || this.combatCooldown > 0;
		if (this.currentIntent != GuardAiIntent.RETREAT) {
			this.corneredTicks = 0;
			return currentHealth <= maxHealth * RETREAT_ENTER_HEALTH_RATIO && inDanger;
		}
		if (combatTarget != null && this.guard.distanceToSqr(combatTarget) <= CLOSE_COMBAT_DISTANCE_SQUARED) {
			if (++this.corneredTicks > CORNERED_TICKS) {
				this.corneredTicks = 0;
				this.retreatSuppressedUntil = now + CORNERED_FIGHT_TICKS;
				return false;
			}
		} else {
			this.corneredTicks = 0;
		}
		return currentHealth < maxHealth * RETREAT_EXIT_HEALTH_RATIO && inDanger;
	}

	private boolean shouldFollowOwner(ServerLevel world) {
		// Owned guards always follow their owner; reputation only gates hiring and village guards.
		if (!this.guard.hasOwner() || this.guard.isStaying() || this.guard.isHoldingPosition()) {
			return false;
		}
		ServerPlayer owner = this.guard.resolveOnlineOwner(world);
		if (owner == null || owner.isSpectator()) {
			return false;
		}
		return this.guard.hasFollowOverride() || this.guard.getHome().isEmpty();
	}

	private void applyActiveTarget(LivingEntity target) {
		if (target == null) {
			if (this.guard.getTarget() != null) {
				this.guard.setTarget(null);
			}
			return;
		}
		this.guard.setTarget(target);
	}

	private void updateEngagement(LivingEntity target, long now) {
		if (target == null) {
			this.releaseEngagement();
			return;
		}
		UUID targetUuid = target.getUUID();
		if (!targetUuid.equals(this.engagedTargetUuid)) {
			this.releaseEngagement();
			this.engagedTargetUuid = targetUuid;
			this.lastEngagementReportTick = Long.MIN_VALUE;
		}
		if (this.lastEngagementReportTick == Long.MIN_VALUE
				|| now - this.lastEngagementReportTick >= ENGAGEMENT_REPORT_INTERVAL_TICKS) {
			this.lastEngagementReportTick = now;
			this.engagementRank = ENGAGEMENTS.report(targetUuid, this.guard.getUUID(), this.guard.distanceToSqr(target), now);
		}
	}

	private void releaseEngagement() {
		if (this.engagedTargetUuid != null) {
			ENGAGEMENTS.release(this.engagedTargetUuid, this.guard.getUUID());
			this.engagedTargetUuid = null;
		}
		this.engagementRank = 0;
	}

	private void tickTimers(ServerLevel world, long now) {
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

		for (int i = this.alerts.size() - 1; i >= 0; i--) {
			GuardAlert alert = this.alerts.get(i);
			if (alert.expiresAt() <= now
					|| !this.isTargetStillTrackable(this.resolveEntity(world, alert.targetUuid()), alert.reason().ownerDirected())) {
				this.alerts.remove(i);
			}
		}

		if (!this.suppressedTargets.isEmpty()) {
			Iterator<Map.Entry<UUID, Long>> iterator = this.suppressedTargets.entrySet().iterator();
			while (iterator.hasNext()) {
				if (iterator.next().getValue() <= now) {
					iterator.remove();
				}
			}
		}
	}

	/** Owner trust / hostility / follow-leash anchor change slowly; refresh them once a second. */
	private void refreshStatus(ServerLevel world, long now) {
		if (this.statusInitialized && Math.floorMod(now + this.guard.getId(), STATUS_INTERVAL_TICKS) != 0) {
			return;
		}
		this.statusInitialized = true;
		UUID ownerUuid = this.guard.getOwnerUuid();
		if (ownerUuid == null) {
			this.followLeashOwner = null;
			return;
		}
		boolean followMode = !this.guard.isStaying() && !this.guard.isHoldingPosition() && (this.guard.hasFollowOverride() || this.guard.getHome().isEmpty());
		this.followLeashOwner = followMode ? this.guard.resolveOwner(world) : null;
	}

	/**
	 * Entity scans, staggered by entity id. Line of sight is checked here (nearest first, a bounded
	 * number of raycasts) instead of for every hostile on every tick.
	 */
	private void refreshScans(ServerLevel world, long now) {
		if (this.scanInitialized && Math.floorMod(now + this.guard.getId(), SCAN_INTERVAL_TICKS) != 0) {
			return;
		}
		this.scanInitialized = true;
		this.scannedHostiles.clear();
		this.scannedRaidTarget = null;

		Raid raid = world.getRaidAt(this.guard.blockPosition());
		this.cachedRaid = raid != null && raid.isActive() ? raid : null;
		if (this.cachedRaid != null) {
			List<Raider> raiders = world.getEntitiesOfClass(
					Raider.class,
					this.guard.getBoundingBox().inflate(RAID_SCAN_RANGE),
					entity -> this.isTargetStillTrackable(entity, false) && !this.isSuppressed(entity.getUUID(), now));
			raiders.sort((a, b) -> Double.compare(this.guard.distanceToSqr(a), this.guard.distanceToSqr(b)));
			int checks = 0;
			for (Raider raider : raiders) {
				if (checks++ >= MAX_SCAN_SIGHT_CHECKS) {
					break;
				}
				if (this.guard.getSensing().hasLineOfSight(raider)) {
					this.scannedRaidTarget = raider;
					break;
				}
			}
		}

		List<Monster> hostiles = world.getEntitiesOfClass(
				Monster.class,
				this.guard.getBoundingBox().inflate(HOSTILE_SCAN_RANGE),
				entity -> this.isTargetStillTrackable(entity, false) && !this.isSuppressed(entity.getUUID(), now));
		if (hostiles.isEmpty()) {
			return;
		}
		hostiles.sort((a, b) -> Double.compare(this.guard.distanceToSqr(a), this.guard.distanceToSqr(b)));
		int checks = 0;
		for (Monster hostile : hostiles) {
			if (this.scannedHostiles.size() >= MAX_SCANNED_HOSTILES || checks++ >= MAX_SCAN_SIGHT_CHECKS) {
				break;
			}
			if (this.guard.getSensing().hasLineOfSight(hostile)) {
				this.scannedHostiles.add(hostile);
			}
		}
	}

	/**
	 * Drops a target the guard cannot see or make progress toward. A bowman holding position with
	 * sight, or a guard in close combat, is not "stuck"; LOS loss is tolerated longer while the
	 * guard is actually moving along a path (e.g. around a house).
	 */
	private void tickCombatTracking(ServerLevel world, long now) {
		LivingEntity target = this.guard.getTarget();
		if (!this.isTargetStillTrackable(target, true)) {
			this.resetCombatTracking();
			return;
		}

		boolean crowdBlocked = this.guard.getGuardNavigation().isCrowdBlocked();
		boolean hasSight = this.guard.getSensing().hasLineOfSight(target);
		double distanceSq = this.guard.distanceToSqr(target);
		boolean moving = this.guard.getNavigation().isInProgress();

		if (hasSight || crowdBlocked) {
			this.noSightTicks = 0;
		} else if (++this.noSightTicks > (moving ? TARGET_LOS_GRACE_MOVING_TICKS : TARGET_LOS_GRACE_TICKS)) {
			this.dropTarget(target, now);
			return;
		}

		boolean holdingPosition = distanceSq <= CLOSE_COMBAT_DISTANCE_SQUARED
				|| (hasSight && this.guard.getMainHandItem().is(Items.BOW) && distanceSq <= BOW_HOLD_DISTANCE_SQUARED)
				|| crowdBlocked;
		if (holdingPosition || this.progressSamplePos == null) {
			this.startProgressWindow(distanceSq);
			return;
		}
		if (++this.progressWindowTicks < PROGRESS_WINDOW_TICKS) {
			return;
		}
		double moved = Math.sqrt(this.guard.distanceToSqr(this.progressSamplePos));
		double closed = Math.sqrt(this.progressSampleTargetDistance) - Math.sqrt(distanceSq);
		if (moved >= PROGRESS_MIN_DISTANCE || closed >= PROGRESS_MIN_DISTANCE) {
			this.stuckWindows = 0;
		} else if (++this.stuckWindows >= TARGET_STUCK_WINDOWS) {
			this.dropTarget(target, now);
			return;
		}
		this.progressWindowTicks = 0;
		this.progressSamplePos = this.guard.position();
		this.progressSampleTargetDistance = distanceSq;
	}

	private void startProgressWindow(double distanceSq) {
		this.progressWindowTicks = 0;
		this.stuckWindows = 0;
		this.progressSamplePos = this.guard.position();
		this.progressSampleTargetDistance = distanceSq;
	}

	private void resetCombatTracking() {
		this.noSightTicks = 0;
		this.progressWindowTicks = 0;
		this.stuckWindows = 0;
		this.progressSamplePos = null;
		this.progressSampleTargetDistance = Double.MAX_VALUE;
	}

	private void dropTarget(LivingEntity target, long now) {
		this.suppressTarget(target.getUUID(), now);
		this.guard.setTarget(null);
		this.resetCombatTracking();
	}

	private void suppressTarget(UUID targetUuid, long currentTick) {
		if (targetUuid == null) {
			return;
		}
		this.suppressedTargets.put(targetUuid, currentTick + TARGET_SUPPRESSION_TICKS);
		this.alerts.removeIf(alert -> targetUuid.equals(alert.targetUuid()));
		this.scannedHostiles.removeIf(entity -> targetUuid.equals(entity.getUUID()));
		if (this.scannedRaidTarget != null && targetUuid.equals(this.scannedRaidTarget.getUUID())) {
			this.scannedRaidTarget = null;
		}
		if (targetUuid.equals(this.mainTargetUuid)) {
			this.mainTargetUuid = null;
		}
		if (targetUuid.equals(this.urgentTargetUuid)) {
			this.urgentTargetUuid = null;
		}
	}

	private void ingestAlert(LivingEntity target, AlertReason reason, long alertTick, boolean clearSuppression) {
		if (!this.isTargetStillTrackable(target, reason.ownerDirected())) {
			return;
		}
		if (reason.ownerDirected() && alertTick < this.lastAcceptedOwnerAlertTick) {
			return;
		}
		UUID targetUuid = target.getUUID();
		if (clearSuppression) {
			this.suppressedTargets.remove(targetUuid);
		} else if (this.isSuppressed(targetUuid, alertTick)) {
			return;
		}

		long expiresAt = alertTick + reason.ttlTicks();
		this.combatCooldown = Math.max(this.combatCooldown, reason.cooldownTicks());
		if (reason.ownerDirected()) {
			this.lastAcceptedOwnerAlertTick = Math.max(this.lastAcceptedOwnerAlertTick, alertTick);
		}
		for (int i = 0; i < this.alerts.size(); i++) {
			GuardAlert existing = this.alerts.get(i);
			if (existing.targetUuid().equals(targetUuid) && existing.reason() == reason) {
				this.alerts.set(i, new GuardAlert(targetUuid, reason, alertTick, Math.max(existing.expiresAt(), expiresAt)));
				return;
			}
		}
		this.alerts.add(new GuardAlert(targetUuid, reason, alertTick, expiresAt));
	}

	private void addCandidate(LivingEntity entity, AlertReason reason, long tick, boolean sticky) {
		if (entity == null) {
			return;
		}
		if (!this.isTargetStillTrackable(entity, reason.ownerDirected()) || this.isSuppressed(entity.getUUID(), tick)) {
			return;
		}
		TargetAccumulator accumulator = null;
		for (int i = 0; i < this.candidates.size(); i++) {
			if (this.candidates.get(i).entity == entity) {
				accumulator = this.candidates.get(i);
				break;
			}
		}
		if (accumulator == null) {
			accumulator = this.acquireAccumulator(entity, this.guard.distanceToSqr(entity), this.scoreThreat(entity));
			this.candidates.add(accumulator);
		}
		accumulator.include(reason, tick, sticky);
	}

	private TargetAccumulator acquireAccumulator(LivingEntity entity, double distanceSq, int threat) {
		TargetAccumulator accumulator = this.accumulatorPool.isEmpty()
				? new TargetAccumulator()
				: this.accumulatorPool.removeLast();
		accumulator.reset(entity, distanceSq, threat);
		return accumulator;
	}

	private void releaseCandidates() {
		for (int i = 0; i < this.candidates.size(); i++) {
			this.candidates.get(i).entity = null;
			this.accumulatorPool.add(this.candidates.get(i));
		}
		this.candidates.clear();
	}


	private boolean isSuppressed(UUID uuid, long now) {
		Long suppressedUntil = this.suppressedTargets.get(uuid);
		return suppressedUntil != null && suppressedUntil > now;
	}


	/**
	 * Alive, not an ally, inside the home zone, and inside the guard's leash: within
	 * {@link #FOLLOW_COMBAT_LEASH} of the owner while following (unless the owner directed the
	 * attack), or within {@link #STAY_COMBAT_LEASH} of the stay point while staying.
	 */
	private boolean isTargetStillTrackable(LivingEntity target, boolean ownerDirected) {
		if (target == null || !target.isAlive() || target.isRemoved() || target.level() != this.guard.level()
				|| this.guard.isAlly(target) || !this.guard.canTargetWithinZone(target.blockPosition())) {
			return false;
		}
		if (this.guard.isHoldingPosition()) {
			double reach = this.guard.getMainHandItem().is(Items.BOW) ? HOLD_BOW_REACH : HOLD_MELEE_REACH;
			if (this.guard.distanceToSqr(target) > reach * reach) {
				return false;
			}
		}
		if (this.guard.isStaying() && this.rallyTicks <= 0) {
			BlockPos stayOrigin = this.guard.getStayOrigin();
			if (stayOrigin != null && stayOrigin.distToCenterSqr(target.position()) > STAY_COMBAT_LEASH * STAY_COMBAT_LEASH) {
				return false;
			}
		}
		ServerPlayer leashOwner = this.followLeashOwner;
		if (!ownerDirected && leashOwner != null && !leashOwner.isRemoved() && leashOwner.level() == target.level()
				&& leashOwner.distanceToSqr(target) > FOLLOW_COMBAT_LEASH * FOLLOW_COMBAT_LEASH) {
			return false;
		}
		return true;
	}

	private LivingEntity resolveEntity(ServerLevel world, UUID uuid) {
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
		if (target instanceof Raider) {
			score += 20;
		}
		if (target instanceof RangedAttackMob
				|| target.getMainHandItem().is(Items.BOW)
				|| target.getMainHandItem().is(Items.CROSSBOW)
				|| target.getMainHandItem().is(Items.TRIDENT)) {
			score += 5;
		}
		return score;
	}

	private void broadcastToAlliedGuards(ServerLevel world, LivingEntity target, long alertTick) {
		double range = Mth.clamp(this.guard.getAttributeValue(Attributes.FOLLOW_RANGE), MIN_ALLY_ALERT_RANGE,
				MAX_ALLY_ALERT_RANGE);
		double rangeSq = range * range;
		for (GuardEntity other : world.getEntitiesOfClass(
				GuardEntity.class,
				this.guard.getBoundingBox().inflate(range),
				entity -> entity != this.guard
						&& entity.isAlive()
						&& entity.getTarget() != target
						&& entity.distanceToSqr(this.guard) <= rangeSq
						&& this.guard.isSameGroup(entity))) {
			other.receiveAlliedAlert(target, alertTick);
		}

		if (target instanceof Monster || target instanceof Raider) {
			for (IronGolem golem : world.getEntitiesOfClass(
					IronGolem.class,
					this.guard.getBoundingBox().inflate(GOLEM_ALERT_RANGE),
					golem -> golem.isAlive() && golem.getTarget() == null)) {
				golem.setTarget(target);
			}
		}
	}

	private long currentWorldTime() {
		return this.guard.level() instanceof ServerLevel world ? world.getGameTime() : Long.MIN_VALUE;
	}

	private record GuardAlert(UUID targetUuid, AlertReason reason, long alertTick, long expiresAt) {
	}

	private enum AlertReason {
		DIRECTIVE_PRIMARY(85, 120, false, false, 100),
		DIRECTIVE_PRIORITY(92, 140, false, true, 120),
		OWNER_DAMAGED(90, 140, false, true, 120),
		OWNER_ATTACK(88, 120, false, true, 100),
		DIRECT_DAMAGE(95, 160, false, false, 160),
		URGENT_PEEL(100, 120, true, false, 120),
		ALLY_BROADCAST(80, 100, false, false, 100),
		RAID_TARGET(76, 40, false, false, 80),
		HOSTILE_SCAN(70, 20, false, false, 80),
		// Stickiness comes from TargetScoring.STICKY_BONUS; the source itself ranks below allied
		// alerts so a better-sourced target can still take over.
		CURRENT_TARGET(78, 20, false, false, 80);

		private final int priority;
		private final int ttlTicks;
		private final boolean urgent;
		private final boolean ownerDirected;
		private final int cooldownTicks;

		AlertReason(int priority, int ttlTicks, boolean urgent, boolean ownerDirected, int cooldownTicks) {
			this.priority = priority;
			this.ttlTicks = ttlTicks;
			this.urgent = urgent;
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

		public boolean ownerDirected() {
			return this.ownerDirected;
		}

		public int cooldownTicks() {
			return this.cooldownTicks;
		}
	}

	private static final class TargetAccumulator {
		private LivingEntity entity;
		private boolean urgent;
		private boolean ownerDirected;
		private boolean sticky;
		private int sourcePriority;
		private int threatPriority;
		private double distanceSq;
		private long latestTick;
		private int score;

		private void reset(LivingEntity entity, double distanceSq, int threatPriority) {
			this.entity = entity;
			this.urgent = false;
			this.ownerDirected = false;
			this.sticky = false;
			this.sourcePriority = 0;
			this.threatPriority = threatPriority;
			this.distanceSq = distanceSq;
			this.latestTick = Long.MIN_VALUE;
			this.score = 0;
		}

		private void include(AlertReason reason, long tick, boolean sticky) {
			this.urgent |= reason.urgent();
			this.ownerDirected |= reason.ownerDirected();
			this.sticky |= sticky;
			this.sourcePriority = Math.max(this.sourcePriority, reason.priority());
			this.latestTick = Math.max(this.latestTick, tick);
		}

		/** Higher score wins; ties go to the nearer, then the more recent, then the lower UUID. */
		private boolean isBetterThan(TargetAccumulator other) {
			if (this.score != other.score) {
				return this.score > other.score;
			}
			if (this.distanceSq != other.distanceSq) {
				return this.distanceSq < other.distanceSq;
			}
			if (this.latestTick != other.latestTick) {
				return this.latestTick > other.latestTick;
			}
			return this.entity.getUUID().compareTo(other.entity.getUUID()) < 0;
		}
	}
}
