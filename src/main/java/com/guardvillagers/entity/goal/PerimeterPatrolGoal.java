package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.village.VillageDescriptor;
import com.guardvillagers.village.VillageManagerHandler;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public final class PerimeterPatrolGoal extends Goal {
	private final GuardEntity guard;
	private final double speed;
	private VillageDescriptor village;
	private final List<BlockPos> perimeterPoints = new ArrayList<>();
	private int pointIndex;
	private int recalculateTicks;

	public PerimeterPatrolGoal(GuardEntity guard, double speed) {
		this.guard = guard;
		this.speed = speed;
		this.setControls(EnumSet.of(Control.MOVE));
	}

	@Override
	public boolean canStart() {
		return this.guard.getBehavior() == GuardBehavior.PERIMETER
			&& this.guard.canExecuteBehaviorGoals()
			&& this.refreshVillageData();
	}

	@Override
	public boolean shouldContinue() {
		return this.guard.getBehavior() == GuardBehavior.PERIMETER
			&& this.guard.canExecuteBehaviorGoals()
			&& !this.perimeterPoints.isEmpty();
	}

	@Override
	public void start() {
		this.pointIndex = 0;
		this.recalculateTicks = 0;
	}

	@Override
	public void tick() {
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}

		if (this.recalculateTicks-- <= 0) {
			this.refreshVillageData();
			this.recalculateTicks = 120;
		}

		if (this.perimeterPoints.isEmpty()) {
			return;
		}

		BlockPos target = this.perimeterPoints.get(this.pointIndex % this.perimeterPoints.size());
		double distanceSq = this.guard.squaredDistanceTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
		if (distanceSq < 4.0D) {
			this.pointIndex = (this.pointIndex + 1) % this.perimeterPoints.size();
			target = this.perimeterPoints.get(this.pointIndex);
		}

		BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, target);
		this.guard.getNavigation().startMovingTo(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D, this.speed);
	}

	private boolean refreshVillageData() {
		if (!(this.guard.getEntityWorld() instanceof ServerWorld world)) {
			return false;
		}

		Optional<VillageDescriptor> descriptor = VillageManagerHandler.findVillageDescriptor(world, this.guard.getBlockPos());
		if (descriptor.isEmpty()) {
			return false;
		}
		this.village = descriptor.get();
		this.rebuildPerimeterPoints();
		return !this.perimeterPoints.isEmpty();
	}

	private void rebuildPerimeterPoints() {
		this.perimeterPoints.clear();
		if (this.village == null) {
			return;
		}

		int minX = this.village.bounds().getMinX();
		int maxX = this.village.bounds().getMaxX();
		int minZ = this.village.bounds().getMinZ();
		int maxZ = this.village.bounds().getMaxZ();
		int y = this.village.center().getY();

		this.perimeterPoints.add(new BlockPos(minX, y, minZ));
		this.perimeterPoints.add(new BlockPos((minX + maxX) / 2, y, minZ));
		this.perimeterPoints.add(new BlockPos(maxX, y, minZ));
		this.perimeterPoints.add(new BlockPos(maxX, y, (minZ + maxZ) / 2));
		this.perimeterPoints.add(new BlockPos(maxX, y, maxZ));
		this.perimeterPoints.add(new BlockPos((minX + maxX) / 2, y, maxZ));
		this.perimeterPoints.add(new BlockPos(minX, y, maxZ));
		this.perimeterPoints.add(new BlockPos(minX, y, (minZ + maxZ) / 2));
	}
}
