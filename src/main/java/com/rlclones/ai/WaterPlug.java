package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Underground (a staircase, a tunnel) and water running down it: a block goes into the flowing water nearest to where it comes from, which cuts the
 * flow below the block; repeated while any runs within reach. ponytail: flowing water only (never a still pond), within 4 blocks, under a roof,
 * at most 12 blocks per 600 ticks; no tracing back to the source and no ceiling for a waterfall that keeps coming - the blocks are the price.
 */
public final class WaterPlug {
    private final ClonePlayer self;
    private final Motor motor;
    private long cooldownUntil;
    private int budget = 12;
    private long budgetAt;
    /** Blocks put into running water (tests, soak). */
    public int plugged;

    public WaterPlug(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    /** True while at it (the caller leaves the clone to it). */
    public boolean tick(long now, boolean threatened) {
        if (threatened || now < cooldownUntil || (now + self.getId()) % 10 != 0 || !self.onGround() || self.isInWater()
                || !com.rlclones.Config.get(com.rlclones.Config.ALLOW_BLOCK_PLACING, true)) {
            return false;
        }
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        if (level.canSeeSky(feet.above())) {
            return false;
        }
        if (now - budgetAt > 600) {
            budgetAt = now;
            budget = 12;
        }
        if (budget <= 0) {
            return false;
        }
        BlockPos best = null;
        int bestAmount = 0;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 3, 4))) {
            FluidState fs = level.getFluidState(p);
            if (!fs.is(FluidTags.WATER) || fs.isSource()) {
                continue;
            }
            double d = p.distSqr(feet);
            if (fs.getAmount() > bestAmount || fs.getAmount() == bestAmount && d < bestD) {
                bestAmount = fs.getAmount();
                bestD = d;
                best = p.immutable();
            }
        }
        if (best == null || self.getEyePosition().distanceTo(Vec3.atCenterOf(best)) > Motor.BLOCK_REACH - 0.5) {
            return false;
        }
        int slot = Equipment.pillarBlockSlot(self);
        if (slot < 0) {
            cooldownUntil = now + 200;
            return false;
        }
        Equipment.select(self, slot);
        motor.stop();
        if (motor.placeBlockAt(best)) {
            plugged++;
            budget--;
            cooldownUntil = now + 10;
        } else {
            cooldownUntil = now + 40;
        }
        return true;
    }
}
