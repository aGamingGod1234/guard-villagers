package com.guardvillagers.command.order;

import com.guardvillagers.entity.GuardEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Resolves {@link TargetRef} server-side at execution time. Never returns the speaker, its own guards or allies. */
public final class TargetResolver {
	public static final double LOOK_RANGE = 48.0D;
	/** "My target" falls back to the last thing the speaker hit, if that was this recent. */
	public static final int LAST_HIT_WINDOW_TICKS = 100;
	public static final double NEAREST_HOSTILE_RANGE = 24.0D;

	private TargetResolver() {
	}

	public static Optional<LivingEntity> resolve(ServerPlayer speaker, TargetRef ref) {
		LivingEntity found = switch (ref) {
			case MY_TARGET -> lookedAt(speaker).orElseGet(() -> recentlyHit(speaker));
			case MY_ATTACKER -> valid(speaker, speaker.getLastHurtByMob()) ? speaker.getLastHurtByMob() : null;
			case NEAREST_HOSTILE -> nearestHostile(speaker);
		};
		return Optional.ofNullable(found);
	}

	/** Entity under the speaker's crosshair within {@link #LOOK_RANGE}, stopping at the first solid block. */
	public static Optional<LivingEntity> lookedAt(ServerPlayer speaker) {
		Vec3 eye = speaker.getEyePosition();
		Vec3 look = speaker.getViewVector(1.0F);
		Vec3 end = eye.add(look.scale(LOOK_RANGE));
		BlockHitResult blockHit = speaker.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, speaker));
		if (blockHit.getType() != HitResult.Type.MISS) {
			end = blockHit.getLocation();
		}
		AABB box = speaker.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0D);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(speaker, eye, end, box,
				entity -> entity instanceof LivingEntity living && valid(speaker, living), eye.distanceToSqr(end));
		if (hit != null && hit.getEntity() instanceof LivingEntity living) {
			return Optional.of(living);
		}
		return Optional.empty();
	}

	private static LivingEntity recentlyHit(ServerPlayer speaker) {
		LivingEntity last = speaker.getLastHurtMob();
		if (last != null && speaker.tickCount - speaker.getLastHurtMobTimestamp() <= LAST_HIT_WINDOW_TICKS && valid(speaker, last)) {
			return last;
		}
		return null;
	}

	private static LivingEntity nearestHostile(ServerPlayer speaker) {
		List<Mob> hostiles = speaker.level().getEntitiesOfClass(Mob.class, speaker.getBoundingBox().inflate(NEAREST_HOSTILE_RANGE),
				mob -> mob instanceof Enemy && valid(speaker, mob));
		Mob best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Mob mob : hostiles) {
			double distance = mob.distanceToSqr(speaker);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = mob;
			}
		}
		return best;
	}

	private static boolean valid(ServerPlayer speaker, Entity entity) {
		if (!(entity instanceof LivingEntity living) || !living.isAlive() || living == speaker || living.isSpectator()
				|| living.level() != speaker.level()) {
			return false;
		}
		if (living instanceof GuardEntity guard && guard.isOwnedBy(speaker.getUUID())) {
			return false;
		}
		return !(living instanceof ServerPlayer other && (other.isCreative() || !speaker.canHarmPlayer(other)));
	}
}
