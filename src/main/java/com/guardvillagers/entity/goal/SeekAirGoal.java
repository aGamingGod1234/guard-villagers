package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

/**
 * A submerged guard low on air swims to the nearest reachable air pocket.
 *
 * <p>Fixes: the goal used to declare the JUMP flag, which {@code FloatGoal} (priority 0) holds the
 * whole time a mob is in water, so this goal could never start. The air search also looked
 * straight up through solid ceilings and accepted flowing water as "breathable"; it now scans
 * nearby columns and validates the complete route, body clearance and breathing height.
 */
public final class SeekAirGoal extends Goal {
	private static final int MAX_VERTICAL_SCAN = 16;
	private static final int HORIZONTAL_SCAN_RADIUS = 3;
	private static final int REPATH_TICKS = 10;

	private final GuardEntity guard;
	private final double speed;
	private int repathTicks;
	private BlockPos targetAirPos;

	public SeekAirGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.guard.isAiIntent(GuardAiIntent.SEEK_AIR);
	}

	@Override
	public boolean canContinueToUse() {
		return this.guard.isAiIntent(GuardAiIntent.SEEK_AIR);
	}

	@Override
	public void start() {
		this.guard.setPathfindingMalus(PathType.WATER, 0.0F);
		this.repathTicks = 0;
		this.targetAirPos = null;
	}

	@Override
	public void stop() {
		this.targetAirPos = null;
		this.guard.getNavigation().stop();
		this.guard.setPathfindingMalus(PathType.WATER, GuardEntity.DEFAULT_WATER_MALUS);
	}

	@Override
	public void tick() {
		// Always kick upward; ground navigation alone swims up slowly.
		this.guard.setDeltaMovement(this.guard.getDeltaMovement().x,
				Math.max(this.guard.getDeltaMovement().y, 0.04D),
				this.guard.getDeltaMovement().z);

		if (this.targetAirPos != null && this.guard.getNavigation().isDone()
				&& this.guard.getBlockX() == this.targetAirPos.getX() && this.guard.getBlockZ() == this.targetAirPos.getZ()
				&& Math.abs(this.guard.getY() - this.targetAirPos.getY()) < 1.0D && this.canBreatheAt(this.targetAirPos)) {
			// A path ends within 0.45 blocks of the centre. At a roof opening that can leave
			// the guard's shoulders under the ceiling, so centre before relying on buoyancy.
			this.guard.getMoveControl().setWantedPosition(this.targetAirPos.getX() + 0.5D,
					this.targetAirPos.getY(), this.targetAirPos.getZ() + 0.5D, this.speed);
			return;
		}

		if (--this.repathTicks > 0) {
			return;
		}
		this.repathTicks = REPATH_TICKS;
		// Revalidate even a cached route: a completed partial route must not pin the goal to a cavity.
		Path path = this.findAirPath();
		if (path != null) {
			this.targetAirPos = path.getEndNode().asBlockPos();
			this.guard.getNavigation().moveTo(path, this.speed);
		} else {
			this.targetAirPos = null;
			this.guard.getNavigation().stop();
		}
	}

	private Path findAirPath() {
		Level level = this.guard.level();
		BlockPos origin = this.guard.blockPosition();
		var candidates = new ArrayList<BlockPos>();
		for (int dx = -HORIZONTAL_SCAN_RADIUS; dx <= HORIZONTAL_SCAN_RADIUS; dx++) {
			for (int dz = -HORIZONTAL_SCAN_RADIUS; dz <= HORIZONTAL_SCAN_RADIUS; dz++) {
				for (int dy = 0; dy <= MAX_VERTICAL_SCAN; dy++) {
					BlockPos feet = origin.offset(dx, dy, dz);
					if (!level.isLoaded(feet) || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) {
						break;
					}
					if (this.canBreatheAt(feet)) {
						candidates.add(feet);
						break;
					}
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
		for (BlockPos feet : candidates) {
			Path path = this.guard.getNavigation().createPath(feet, 0);
			if (path != null && path.canReach() && path.getEndNode() != null
					&& this.canBreatheAt(path.getEndNode().asBlockPos())) {
				return path;
			}
		}
		return null;
	}

	private boolean canBreatheAt(BlockPos feet) {
		Level level = this.guard.level();
		Vec3 standAt = Vec3.atBottomCenterOf(feet);
		BlockPos eyes = BlockPos.containing(standAt.x, standAt.y + this.guard.getEyeHeight(), standAt.z);
		return level.isLoaded(eyes) && level.getFluidState(eyes).isEmpty()
				&& level.noCollision(this.guard, this.guard.getBoundingBox().move(standAt.subtract(this.guard.position())));
	}
}
