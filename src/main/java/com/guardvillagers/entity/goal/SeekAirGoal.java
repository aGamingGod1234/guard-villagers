package com.guardvillagers.entity.goal;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardAiIntent;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * When a guard is submerged and running low on air, navigate upward to breathe.
 * Suspends combat targeting while surfacing and resumes after air is
 * replenished.
 */
public final class SeekAirGoal extends Goal {
    private static final int AIR_CRITICAL_THRESHOLD = 60;
    private static final int MAX_VERTICAL_SCAN = 24;

    private final GuardEntity guard;
    private final double speed;
    private float oldWaterPenalty;
    private int repathTicks;
    private BlockPos targetAirPos;

    public SeekAirGoal(GuardEntity guard, double speed) {
        this.guard = guard;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
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
        this.oldWaterPenalty = this.guard.getPathfindingMalus(PathType.WATER);
        this.guard.setPathfindingMalus(PathType.WATER, 0.0F);
        this.repathTicks = 0;
        this.targetAirPos = null;
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        this.guard.setPathfindingMalus(PathType.WATER, this.oldWaterPenalty);
        this.targetAirPos = null;
    }

    @Override
    public void tick() {
        if (--this.repathTicks > 0) {
            return;
        }
        this.repathTicks = 10;

        if (this.targetAirPos == null || !isBreathable(this.guard.level(), this.targetAirPos)) {
            this.targetAirPos = findAirAbove();
        }

        if (this.targetAirPos != null) {
            this.guard.getNavigation().moveTo(
                    this.targetAirPos.getX() + 0.5D,
                    this.targetAirPos.getY(),
                    this.targetAirPos.getZ() + 0.5D,
                    this.speed);
        } else {
            // No air found above — swim upward as best we can
            this.guard.setDeltaMovement(
                    this.guard.getDeltaMovement().x,
                    Math.max(this.guard.getDeltaMovement().y, 0.04D),
                    this.guard.getDeltaMovement().z);
        }
    }

    private BlockPos findAirAbove() {
        BlockPos guardPos = this.guard.blockPosition();
        Level world = this.guard.level();
        for (int dy = 1; dy <= MAX_VERTICAL_SCAN; dy++) {
            BlockPos checkPos = guardPos.above(dy);
            if (isBreathable(world, checkPos)) {
                // Return the block below the air so the guard stands there
                return checkPos.below();
            }
        }
        return null;
    }

    private static boolean isBreathable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isRedstoneConductor(world, pos) && !state.getFluidState().isSource();
    }
}
