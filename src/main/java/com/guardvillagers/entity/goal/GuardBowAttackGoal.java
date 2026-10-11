package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import com.guardvillagers.navigation.GuardGrounding;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * Bow combat, modelled on vanilla RangedBowAttackGoal with three guard-specific additions:
 * <ul>
 * <li>Keeps a range band: approaches when out of range or without sight, backs off along a
 * path-safe spot when a target gets inside {@link #KITE_DISTANCE}, otherwise holds and strafes.
 * The old goal ran to a slot 2.5 blocks from the target for the first second of every fight.</li>
 * <li>Holds a drawn arrow while a villager, owner or allied guard is in the line of fire and
 * strafes sideways to clear it, instead of loosing an arrow that the ally filter then deletes.</li>
 * <li>Raises the shield between shots against ranged or close threats, and never while drawing.</li>
 * </ul>
 */
public final class GuardBowAttackGoal extends Goal {
	private static final int MIN_PULL_TICKS = 20;
	private static final int SHOT_INTERVAL_TICKS = 20;
	private static final int MAX_BLOCKED_HOLD_TICKS = 60;
	private static final double KITE_DISTANCE = 5.0D;
	private static final double KITE_STEP = 5.0D;
	private static final double SHIELD_CLOSE_DISTANCE_SQUARED = 6.0D * 6.0D;
	private static final int REPATH_INTERVAL_TICKS = 10;

	private final GuardEntity guard;
	private final double speed;
	private final float attackRadiusSq;
	private int seeTime;
	private int attackTime = -1;
	private int strafingTime = -1;
	private boolean strafingClockwise;
	private boolean strafingBackwards;
	private int blockedHoldTicks;
	private int repathTicks;

	public GuardBowAttackGoal(GuardEntity guard, double speed, float range) {
		this.guard = guard;
		this.speed = speed;
		this.attackRadiusSq = range * range;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.hasValidTarget();
	}

	@Override
	public boolean canContinueToUse() {
		return this.hasValidTarget();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		this.guard.setAggressive(true);
		this.seeTime = 0;
		this.attackTime = -1;
		this.strafingTime = -1;
		this.blockedHoldTicks = 0;
		this.repathTicks = 0;
	}

	@Override
	public void stop() {
		this.guard.setAggressive(false);
		this.seeTime = 0;
		this.attackTime = -1;
		if (this.guard.isUsingItem() && this.guard.getUsedItemHand() == InteractionHand.MAIN_HAND) {
			this.guard.stopUsingItem();
		}
		this.guard.requestShield(false);
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		LivingEntity target = this.guard.getTarget();
		if (target == null || !(this.guard.level() instanceof ServerLevel world)) {
			return;
		}

		double distanceSq = this.guard.distanceToSqr(target.getX(), target.getY(), target.getZ());
		boolean hasSight = this.guard.getSensing().hasLineOfSight(target);
		if (hasSight != this.seeTime > 0) {
			this.seeTime = 0;
		}
		this.seeTime += hasSight ? 1 : -1;

		this.updateMovement(target, distanceSq, hasSight);

		boolean drawing = this.guard.isUsingItem() && this.guard.getUsedItemHand() == InteractionHand.MAIN_HAND;
		if (drawing) {
			if (!hasSight && this.seeTime < -60) {
				this.guard.stopUsingItem();
			} else if (hasSight && this.guard.getTicksUsingItem() >= MIN_PULL_TICKS) {
				if (this.isLineOfFireClear(world, target)) {
					int pull = this.guard.getTicksUsingItem();
					this.guard.stopUsingItem();
					this.guard.performRangedAttack(target, BowItem.getPowerForTime(pull));
					this.attackTime = SHOT_INTERVAL_TICKS;
					this.blockedHoldTicks = 0;
				} else if (++this.blockedHoldTicks > MAX_BLOCKED_HOLD_TICKS) {
					this.guard.stopUsingItem();
					this.attackTime = SHOT_INTERVAL_TICKS / 2;
					this.blockedHoldTicks = 0;
				} else if (this.blockedHoldTicks % 20 == 1) {
					// Step sideways to get a clear lane.
					this.strafingClockwise = !this.strafingClockwise;
					this.strafingTime = 0;
				}
			}
			this.guard.requestShield(false);
			return;
		}

		if (--this.attackTime <= 0 && this.seeTime >= -60) {
			this.guard.requestShield(false);
			if (this.guard.isUsingItem()) {
				this.guard.stopUsingItem();
			}
			this.guard.startUsingItem(InteractionHand.MAIN_HAND);
			return;
		}

		boolean threatened = GuardMeleeAttackGoal.isRangedThreat(target) || distanceSq <= SHIELD_CLOSE_DISTANCE_SQUARED;
		this.guard.requestShield(threatened && hasSight);
	}

	private void updateMovement(LivingEntity target, double distanceSq, boolean hasSight) {
		if (this.guard.isHoldingPosition()) {
			this.guard.getNavigation().stop();
			this.guard.getLookControl().setLookAt(target, 30.0F, 30.0F);
			return;
		}
		if (distanceSq > this.attackRadiusSq || this.seeTime < 20) {
			this.strafingTime = -1;
			if (--this.repathTicks <= 0) {
				this.repathTicks = REPATH_INTERVAL_TICKS;
				this.guard.getNavigation().moveTo(target, this.speed);
			}
			this.guard.getLookControl().setLookAt(target, 30.0F, 30.0F);
			return;
		}

		if (distanceSq < KITE_DISTANCE * KITE_DISTANCE && this.tryKite(target)) {
			this.guard.getLookControl().setLookAt(target, 30.0F, 30.0F);
			return;
		}

		this.guard.getNavigation().stop();
		this.strafingTime++;
		if (this.strafingTime >= 20) {
			if (this.guard.getRandom().nextFloat() < 0.3F) {
				this.strafingClockwise = !this.strafingClockwise;
			}
			if (this.guard.getRandom().nextFloat() < 0.3F) {
				this.strafingBackwards = !this.strafingBackwards;
			}
			this.strafingTime = 0;
		}
		if (distanceSq > this.attackRadiusSq * 0.75F) {
			this.strafingBackwards = false;
		} else if (distanceSq < this.attackRadiusSq * 0.25F) {
			this.strafingBackwards = true;
		}
		this.guard.lookAt(target, 30.0F, 30.0F);
		this.strafeWithinZone(this.strafingBackwards ? -0.5F : 0.5F, this.strafingClockwise ? 0.5F : -0.5F);
	}

	private void strafeWithinZone(float forward, float sideways) {
		float yaw = this.guard.getYRot() * Mth.DEG_TO_RAD;
		Vec3 ahead = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
		Vec3 right = new Vec3(Mth.cos(yaw), 0.0D, Mth.sin(yaw));
		Vec3 step = ahead.scale(forward).add(right.scale(sideways)).normalize();
		// Leave room to brake existing motion. Vanilla may replace a non-walkable strafe with
		// forward movement, so that direction must also respect the boundary.
		Vec3 origin = this.guard.position().add(this.guard.getDeltaMovement().multiply(3.0D, 0.0D, 3.0D));
		if (this.guard.canTargetWithinZone(BlockPos.containing(origin.add(step)))
				&& this.guard.canTargetWithinZone(BlockPos.containing(origin.add(ahead)))) {
			this.guard.getMoveControl().strafe(forward, sideways);
		} else {
			this.guard.getMoveControl().setWait();
			this.guard.setZza(0.0F);
			this.guard.setXxa(0.0F);
		}
	}

	/** Back away from a close target to a walkable spot inside the guard's zone. */
	private boolean tryKite(LivingEntity target) {
		if (this.repathTicks-- > 0 && this.guard.getNavigation().isInProgress()) {
			return true;
		}
		this.repathTicks = REPATH_INTERVAL_TICKS;
		Vec3 away = this.guard.position().subtract(target.position());
		away = new Vec3(away.x, 0.0D, away.z);
		if (away.lengthSqr() < 1.0E-4D) {
			return false;
		}
		Vec3 destination = this.guard.position().add(away.normalize().scale(KITE_STEP));
		BlockPos spot = GuardGrounding.findStandableNear(this.guard, Mth.floor(destination.x), Mth.floor(destination.z),
				this.guard.getBlockY(), 2, 3, 1);
		if (spot == null || !this.guard.canTargetWithinZone(spot)) {
			return false;
		}
		this.guard.getGuardNavigation().startMovingToDynamic(Vec3.atBottomCenterOf(spot), this.speed * 1.15D);
		return !this.guard.getGuardNavigation().isLastTargetUnreachable();
	}

	/**
	 * True when no allied entity (villager, owner, golem, squadmate) and no bystander player sits
	 * on the shot line.
	 */
	private boolean isLineOfFireClear(ServerLevel world, LivingEntity target) {
		Vec3 from = this.guard.getEyePosition();
		Vec3 to = new Vec3(target.getX(), target.getY(0.5D), target.getZ());
		AABB sweep = new AABB(from, to).inflate(0.5D);
		return !world.hasEntities(EntityTypeTest.forClass(LivingEntity.class), sweep,
				entity -> entity != this.guard && entity != target && entity.isAlive() && entity.canBeHitByProjectile()
						&& (this.guard.isAlly(entity) || entity instanceof Player)
						&& entity.getBoundingBox().inflate(0.3D).clip(from, to).isPresent());
	}

	private boolean hasValidTarget() {
		LivingEntity target = this.guard.getTarget();
		return this.guard.isAiIntent(GuardAiIntent.ENGAGE_TARGET)
				&& target != null
				&& target.isAlive()
				&& this.guard.getMainHandItem().is(Items.BOW);
	}
}
