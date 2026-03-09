package com.guardvillagers.navigation;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class GuardNavigation extends MobNavigation {
    private final GuardEntity guard;

    // Repathing Budget
    private long lastRecalculateTick;
    private BlockPos lastTargetPos;

    // Stall Detection
    private Vec3d lastStallCheckPos = Vec3d.ZERO;
    private int stallTicks;

    public GuardNavigation(GuardEntity guard, World world) {
        super(guard, world);
        this.guard = guard;
    }

    @Override
    public Path findPathTo(BlockPos target, int distance) {
        // Air priority check for SeekAirGoal
        if (guard.getAir() < 80) {
            // Temporarily ignore routing cache for immediate survival
            return super.findPathTo(target, distance);
        }

        long currentTick = guard.getEntityWorld().getTime();

        // Use cached squad route if available
        Path cached = SquadRouteCache.getSquadRoute(guard.getSquadId(), guard.getBlockPos(), target, currentTick);
        if (cached != null) {
            return cached;
        }

        Path newPath = super.findPathTo(target, distance);
        if (newPath != null) {
            SquadRouteCache.cacheSquadRoute(guard.getSquadId(), guard.getBlockPos(), target, newPath, currentTick);
        }
        return newPath;
    }

    @Override
    public Path findPathTo(Entity entity, int distance) {
        return this.findPathTo(entity.getBlockPos(), distance);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.isIdle()) {
            stallTicks = 0;
            return;
        }

        // Stall Detection Logic
        Vec3d currentPos = new Vec3d(guard.getX(), guard.getY(), guard.getZ());
        if (lastStallCheckPos.squaredDistanceTo(currentPos) < 0.25) {
            stallTicks++;
            if (stallTicks >= 40) {
                // Micro-recovery: stalled for 2 seconds while trying to follow a path.
                // Clear path and trigger an immediate recalculate slightly offset if needed
                // next tick.
                this.stop();
                stallTicks = 0;
            }
        } else {
            lastStallCheckPos = currentPos;
            stallTicks = 0;
        }

        // Repath budgeting is inherently handled by tracking the path usage,
        // vanilla recalculates anyway but we can limit external forced recalculates by
        // overriding starts.
    }
}
