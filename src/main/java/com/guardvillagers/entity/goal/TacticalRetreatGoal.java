package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import com.guardvillagers.entity.ai.GuardMovementSlotResolver;
import com.guardvillagers.navigation.GuardGrounding;
import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.Vec3;

/** Falls back to a cleric, a bed, home, or away from the threat while health recovers. */
public final class TacticalRetreatGoal extends Goal {
	private static final int SAFE_POINT_REFRESH_TICKS = 40;

	private final GuardEntity guard;
	private final double speed;
	private final AnchorHold hold = new AnchorHold();
	private BlockPos safePoint;
	private long nextRefresh;

	public TacticalRetreatGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETREAT);
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isAiIntent(GuardAiIntent.RETREAT);
	}

	@Override
	public void start() {
		this.safePoint = null;
		this.nextRefresh = 0L;
		this.hold.reset();
	}

	@Override
	public void stop() {
		this.safePoint = null;
		this.guard.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!(this.guard.level() instanceof ServerLevel world)) {
			return;
		}
		long now = world.getGameTime();
		if (now >= this.nextRefresh) {
			this.safePoint = this.guard.findSafeRetreatPoint(world);
			this.nextRefresh = now + SAFE_POINT_REFRESH_TICKS;
		}
		this.hold.tick(this.guard, this.safePoint, null, this.speed);
	}

	/** Tests every preferred refuge before falling back, so one unreachable cleric cannot trap a retreat. */
	public static BlockPos findSafePoint(GuardEntity guard, ServerLevel world, LivingEntity threat) {
		var clerics = world.getEntitiesOfClass(Villager.class, guard.getBoundingBox().inflate(32.0D),
				villager -> villager.isAlive() && villager.getVillagerData().profession().is(VillagerProfession.CLERIC));
		clerics.sort(Comparator.comparingDouble(guard::distanceToSqr));
		for (Villager cleric : clerics) {
			BlockPos slot = reachableRefuge(guard, cleric.blockPosition());
			if (slot != null) {
				return slot;
			}
		}
		var beds = world.getPoiManager().findAll(entry -> entry.is(PoiTypes.HOME), pos -> true,
				guard.blockPosition(), 48, PoiManager.Occupancy.ANY)
				.sorted(Comparator.comparingDouble(pos -> pos.distSqr(guard.blockPosition()))).toList();
		for (BlockPos bed : beds) {
			BlockPos slot = reachableRefuge(guard, bed);
			if (slot != null) {
				return slot;
			}
		}
		if (guard.getHome().isPresent()) {
			BlockPos slot = reachableRefuge(guard, guard.getHome().get());
			if (slot != null) {
				return slot;
			}
		}
		if (threat != null) {
			Vec3 away = guard.position().subtract(threat.position()).multiply(1.0D, 0.0D, 1.0D);
			if (away.lengthSqr() < 1.0E-4D) {
				away = new Vec3(1.0D, 0.0D, 0.0D);
			}
			// Try shorter and sideways escapes when the direct route away ends across a wall or drop.
			for (int distance : new int[] {12, 8, 4}) {
				for (float angle : new float[] {0.0F, 0.7853982F, -0.7853982F, 1.5707964F, -1.5707964F}) {
					Vec3 destination = guard.position().add(away.normalize().yRot(angle).scale(distance));
					BlockPos grounded = GuardGrounding.findStandableNear(guard, Mth.floor(destination.x), Mth.floor(destination.z),
							guard.getBlockY(), 4, 4, 2);
					if (grounded != null && reachable(guard, grounded)) {
						return grounded;
					}
				}
			}
		}
		return guard.blockPosition();
	}

	private static BlockPos reachableRefuge(GuardEntity guard, BlockPos anchor) {
		BlockPos slot = guard.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.RETREAT, anchor, false);
		return reachable(guard, slot) ? slot : null;
	}

	private static boolean reachable(GuardEntity guard, BlockPos pos) {
		if (!guard.level().isLoaded(pos) || !GuardGrounding.isStandable(guard, pos)) {
			return false;
		}
		var path = guard.getNavigation().createPath(pos, 0);
		return path != null && path.canReach() && path.getEndNode() != null && path.getEndNode().asBlockPos().equals(pos);
	}
}
