package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Lava is worth having: a bucket of it smelts 100 items and is a weapon (the fight and the furnace already use a lava bucket). A clone with an empty bucket
 * and no lava bucket yet, healthy and not threatened, scoops a lava source that is within a few blocks, from a stand a good way off.
 * ponytail: lava within 10 blocks (horizontal) and 4 down / 2 up; one bucket; the stand is wherever the walk ends 3 blocks from the source - no check of what is
 * behind (a lava flow beside the stand would be the alarm reflex's business); add a ring of cobblestone round the source if clones are lost to it.
 */
public final class LavaScoop {
    private final ClonePlayer self;
    private final Motor motor;
    private BlockPos spot;
    private int ticks;
    private long cooldownUntil;
    /** Buckets of lava taken (tests, soak). */
    public int scooped;
    /** Ticks it was wanted (bucket, no lava bucket, healthy) / pools found (soak diagnosis). */
    public int wantedTicks;
    public int poolsFound;
    public String debug = "";

    public LavaScoop(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    private boolean wanted() {
        return self.getInventory().countItem(Items.BUCKET) > 0 && self.getInventory().countItem(Items.LAVA_BUCKET) == 0
                && self.getHealth() >= 16 && !self.isOnFire() && !self.isInWater() && self.onGround();
    }

    @Nullable
    private BlockPos find() {
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-10, -4, -10), feet.offset(10, 2, 10))) {
            var fs = level.getFluidState(p);
            if (fs.isSource() && fs.getType().isSame(Fluids.LAVA) && level.getBlockState(p.above()).isAir()) {
                double d = p.distSqr(feet);
                if (d < bestD) {
                    bestD = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    /** True while busy with a pool. */
    public boolean tick(long now, boolean threatened) {
        if (spot == null) {
            if (now < cooldownUntil || threatened || (now + self.getId()) % 100 != 0 || !wanted()) {
                return false;
            }
            wantedTicks++;
            spot = find();
            if (spot != null) {
                poolsFound++;
            }
            if (spot == null) {
                cooldownUntil = now + 1200;
                return false;
            }
            ticks = 0;
        }
        if (threatened || ++ticks > 400 || !wanted() || !self.serverLevel().getFluidState(spot).isSource()) {
            spot = null;
            cooldownUntil = now + 2400;
            return false;
        }
        Vec3 c = Vec3.atCenterOf(spot);
        double d = self.getEyePosition().distanceTo(c);
        if (d > Motor.BLOCK_REACH - 0.7) {
            motor.navigate(c, 3.0, false); // (stops three blocks short: never at the edge)
            debug = "to the lava " + spot.toShortString();
            if (motor.stuckCount() > 6) {
                spot = null;
                cooldownUntil = now + 2400;
                return false;
            }
            return true;
        }
        motor.stop();
        motor.lookAt(c.add(0, 0.3, 0));
        ServerLevel level = self.serverLevel();
        Vec3 eye = self.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(self.getViewVector(1f).scale(Motor.BLOCK_REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, self));
        if (hit.getType() == HitResult.Type.BLOCK && level.getFluidState(hit.getBlockPos()).is(FluidTags.LAVA)) {
            int slot = self.getInventory().findSlotMatchingItem(new ItemStack(Items.BUCKET));
            if (slot >= 0) {
                Equipment.select(self, slot);
                motor.useHeldItem(InteractionHand.MAIN_HAND);
            }
            if (self.getInventory().countItem(Items.LAVA_BUCKET) > 0) {
                scooped++;
                debug = "took a bucket of lava";
                spot = null;
                cooldownUntil = now + 4800;
                return false;
            }
        }
        return true;
    }
}
