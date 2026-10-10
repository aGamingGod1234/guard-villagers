package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Melee combat, modelled on vanilla MeleeAttackGoal: path straight to the target and swing when
 * {@link GuardEntity#isWithinMeleeAttackRange} says the target is in reach.
 *
 * <p>The old version walked to a "combat approach slot" 2.5 blocks from the target, then spread
 * by a 1.75-block ring, which is outside the ~1.4 block sword reach. Against mobs that do not
 * attack guards (husks, skeletons kiting) the guard stood at its slot forever and never swung.
 *
 * <p>Only the closest {@link #MAX_FRONTLINE_ATTACKERS} guards on one target close in; the rest
 * hold a short distance back so a big squad does not crush into one block around one zombie.
 * Shield use: raised while closing on a ranged attacker and between swings.
 */
public final class GuardMeleeAttackGoal extends Goal {
	private static final int ATTACK_INTERVAL_TICKS = 20;
	private static final int MAX_FRONTLINE_ATTACKERS = 4;
	private static final double SUPPORT_HOLD_DISTANCE = 4.0D;
	private static final double SHIELD_RANGED_THREAT_DISTANCE_SQUARED = 16.0D * 16.0D;
	private static final double SHIELD_CLOSE_DISTANCE_SQUARED = 4.0D * 4.0D;
	private static final float LOOK_CHANGE = 30.0F;
	private static final double CREEPER_SAFE_DISTANCE = 6.0D;

	private final GuardEntity guard;
	private final double speed;
	private int repathTicks;
	private int attackCooldown;
	private double pathedTargetX;
	private double pathedTargetY;
	private double pathedTargetZ;

	public GuardMeleeAttackGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
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
		this.repathTicks = 0;
		this.attackCooldown = 0;
		this.pathedTargetX = Double.NaN;
		this.guard.setAggressive(true);
	}

	@Override
	public void stop() {
		this.repathTicks = 0;
		this.guard.setAggressive(false);
		this.guard.requestShield(false);
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		LivingEntity target = this.guard.getTarget();
		if (target == null || !(this.guard.level() instanceof ServerLevel world)) {
			return;
		}

		this.guard.getLookControl().setLookAt(target, LOOK_CHANGE, LOOK_CHANGE);
		double distanceSq = this.guard.distanceToSqr(target);
		if (this.avoidingCreeperBlast(target, distanceSq)) {
			return;
		}
		boolean frontline = this.guard.getEngagementRank() < MAX_FRONTLINE_ATTACKERS;
		this.repathTicks = Math.max(this.repathTicks - 1, 0);
		if (this.repathTicks <= 0 && (Double.isNaN(this.pathedTargetX)
				|| target.distanceToSqr(this.pathedTargetX, this.pathedTargetY, this.pathedTargetZ) >= 1.0D
				|| this.guard.getNavigation().isDone())) {
			this.pathedTargetX = target.getX();
			this.pathedTargetY = target.getY();
			this.pathedTargetZ = target.getZ();
			this.repathTicks = 4 + this.guard.getRandom().nextInt(7);
			if (distanceSq > 32.0D * 32.0D) {
				this.repathTicks += 10;
			} else if (distanceSq > 16.0D * 16.0D) {
				this.repathTicks += 5;
			}
			if (!this.moveTowards(target, distanceSq, frontline)) {
				this.repathTicks += 15;
			}
		}

		if (this.attackCooldown > 0) {
			this.attackCooldown--;
		}
		boolean inReach = this.guard.isWithinMeleeAttackRange(target);
		if (inReach && this.attackCooldown <= 0 && this.guard.getSensing().hasLineOfSight(target)) {
			this.guard.requestShield(false);
			this.guard.stopUsingItem();
			this.attackCooldown = ATTACK_INTERVAL_TICKS;
			this.guard.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
			this.guard.doHurtTarget(world, target);
			return;
		}

		boolean rangedThreat = isRangedThreat(target);
		boolean closingOnArcher = rangedThreat && !inReach && distanceSq <= SHIELD_RANGED_THREAT_DISTANCE_SQUARED;
		// Between swings: shield up after the swing animation, down a few ticks before the next swing.
		boolean betweenSwings = distanceSq <= SHIELD_CLOSE_DISTANCE_SQUARED
				&& this.attackCooldown > 4 && this.attackCooldown < ATTACK_INTERVAL_TICKS - 4;
		this.guard.requestShield(closingOnArcher || betweenSwings);
	}

	/**
	 * Hit-and-run against creepers: once a creeper starts to swell, back out of blast range with the
	 * shield up (shields block explosion damage when facing it), then close in again when it calms
	 * down. Without this, swordsmen stood in the blast and died.
	 */
	private boolean avoidingCreeperBlast(LivingEntity target, double distanceSq) {
		if (!(target instanceof Creeper creeper) || !(creeper.getSwellDir() > 0 || creeper.isIgnited())) {
			return false;
		}
		this.guard.requestShield(true);
		if (distanceSq >= CREEPER_SAFE_DISTANCE * CREEPER_SAFE_DISTANCE || this.guard.isHoldingPosition()) {
			this.guard.getNavigation().stop();
			return true;
		}
		Vec3 away = this.guard.position().subtract(target.position());
		away = new Vec3(away.x, 0.0D, away.z);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(1.0D, 0.0D, 0.0D);
		}
		Vec3 retreat = target.position().add(away.normalize().scale(CREEPER_SAFE_DISTANCE + 1.0D));
		this.guard.getGuardNavigation().startMovingToDynamic(retreat, this.speed * 1.2D);
		this.pathedTargetX = Double.NaN;
		return true;
	}

	private boolean moveTowards(LivingEntity target, double distanceSq, boolean frontline) {
		if (this.guard.isHoldingPosition()) {
			this.guard.getNavigation().stop();
			return true;
		}
		if (frontline) {
			return this.guard.getNavigation().moveTo(target, this.speed);
		}
		// Support line: hold a few blocks out on our side of the target.
		if (distanceSq <= SUPPORT_HOLD_DISTANCE * SUPPORT_HOLD_DISTANCE) {
			this.guard.getNavigation().stop();
			return true;
		}
		Vec3 away = this.guard.position().subtract(target.position());
		away = new Vec3(away.x, 0.0D, away.z);
		if (away.lengthSqr() < 1.0E-4D) {
			return this.guard.getNavigation().moveTo(target, this.speed);
		}
		Vec3 hold = target.position().add(away.normalize().scale(SUPPORT_HOLD_DISTANCE - 1.0D));
		return this.guard.getGuardNavigation().startMovingToDynamic(hold, this.speed);
	}

	static boolean isRangedThreat(LivingEntity target) {
		return target instanceof RangedAttackMob
				|| target.getMainHandItem().is(Items.BOW)
				|| target.getMainHandItem().is(Items.CROSSBOW)
				|| target.getMainHandItem().is(Items.TRIDENT);
	}

	private boolean hasValidTarget() {
		LivingEntity target = this.guard.getTarget();
		return this.guard.isAiIntent(GuardAiIntent.ENGAGE_TARGET)
				&& !this.guard.getMainHandItem().is(Items.BOW)
				&& target != null
				&& target.isAlive();
	}
}
