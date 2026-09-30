package com.rlclones.ai;

import com.rlclones.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;

import javax.annotation.Nullable;

/**
 * Farming like a player: soil next to water gets tilled with a hoe, seeds are planted on empty farmland and ripe crops
 * are harvested (and replanted). Only blocks the clone can actually see (line of sight to the top face) are used.
 */
public final class Farming {
    public enum Status {WORKING, DONE, FAILED}

    private enum Job {HARVEST, PLANT, TILL}

    private static final int RADIUS = 6;

    private final ServerPlayer self;
    private final Motor motor;

    @Nullable
    private BlockPos target;
    private Job job;
    private int ticks;
    private int tries;
    private int collect;
    private long cachedAt = -1000;
    /** Spots where the job did not work out (too dark for crops, out of reach...), skipped for a while. */
    private final java.util.Map<BlockPos, Long> failed = new java.util.HashMap<>();
    private boolean cachedWork;

    public int tilled;
    public int planted;
    public int harvested;
    private String last = "";

    /** State for diagnostics. */
    public String debug() {
        return "job=" + job + " target=" + target + " tries=" + tries + " ticks=" + ticks + " tilled=" + tilled + " planted=" + planted
                + " hand=" + self.getMainHandItem() + " last=" + last
                + (target == null ? "" : " at=" + self.level().getBlockState(target) + " above=" + self.level().getBlockState(target.above())
                + " light=" + self.level().getRawBrightness(target.above(), 0) + " sky=" + self.level().canSeeSky(target.above())
                + " survive=" + Blocks.WHEAT.defaultBlockState().canSurvive(self.level(), target.above()))
                + " eye=" + self.getEyePosition();
    }

    public Farming(ServerPlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public static int seedSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (Storage.isSeed(inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int hoeSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.getItem() instanceof HoeItem || (!s.isEmpty() && s.canPerformAction(ToolActions.HOE_TILL))) {
                return i;
            }
        }
        return -1;
    }

    private boolean tillable(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.ROOTED_DIRT))) {
            return false;
        }
        return level.getBlockState(pos.above()).isAir() && nearWater(level, pos);
    }

    /** Vanilla farmland stays hydrated within 4 blocks of water on the same level. */
    private static boolean nearWater(ServerLevel level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (level.getFluidState(p).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ripe(BlockState st) {
        return st.getBlock() instanceof CropBlock crop && crop.isMaxAge(st);
    }

    /** The clone must see the top of the block (no wall hacks). */
    private boolean visible(ServerLevel level, BlockPos pos, boolean cropOnTop) {
        Vec3 eye = self.getEyePosition();
        Vec3 top = cropOnTop ? Vec3.atCenterOf(pos) : new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        if (eye.distanceTo(top) > 24) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(eye, top, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    @Nullable
    private BlockPos find(Job[] out) {
        ServerLevel level = self.serverLevel();
        boolean seeds = seedSlot(self) >= 0;
        boolean hoe = hoeSlot(self) >= 0;
        boolean breaking = Config.get(Config.ALLOW_BLOCK_BREAKING, true);
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-RADIUS, -2, -RADIUS), feet.offset(RADIUS, 1, RADIUS))) {
            BlockState st = level.getBlockState(p);
            Job j = null;
            if (breaking && ripe(st)) {
                j = Job.HARVEST;
            } else if (seeds && st.getBlock() instanceof FarmBlock && level.getBlockState(p.above()).isAir()) {
                j = Job.PLANT;
            } else if (seeds && hoe && tillable(level, p)) {
                j = Job.TILL;
            }
            if (j == null) {
                continue;
            }
            Long f = failed.get(p);
            if (f != null && self.level().getGameTime() - f < 1200) {
                continue;
            }
            // harvest first, then plant, then till; nearest within a job
            double score = j.ordinal() * 1000 + p.distSqr(feet);
            if (score < bestScore && visible(level, p, j == Job.HARVEST)) {
                bestScore = score;
                best = p.immutable();
                out[0] = j;
            }
        }
        return best;
    }

    public boolean hasWork() {
        long now = self.level().getGameTime();
        if (now - cachedAt >= 40) {
            cachedAt = now;
            cachedWork = find(new Job[1]) != null;
        }
        return cachedWork;
    }

    public void reset() {
        target = null;
        job = null;
        ticks = 0;
        tries = 0;
        collect = 0;
        motor.resetMining();
    }

    public Status tick() {
        ServerLevel level = self.serverLevel();
        if (++ticks > 2400) {
            reset();
            return Status.DONE;
        }
        if (collect > 0) {
            // walk over the dropped wheat / seeds like a player would
            collect--;
            motor.navigate(Vec3.atBottomCenterOf(target), 0.3, false);
            if (collect == 0) {
                target = null;
            }
            return Status.WORKING;
        }
        if (target == null) {
            Job[] j = new Job[1];
            target = find(j);
            job = j[0];
            tries = 0;
            if (target == null) {
                cachedWork = false;
                reset();
                return Status.DONE;
            }
        }
        BlockState st = level.getBlockState(target);
        boolean stillValid = switch (job) {
            case HARVEST -> ripe(st);
            case PLANT -> st.getBlock() instanceof FarmBlock && level.getBlockState(target.above()).isAir() && seedSlot(self) >= 0;
            case TILL -> tillable(level, target) && hoeSlot(self) >= 0 && seedSlot(self) >= 0;
        };
        if (!stillValid) {
            target = null;
            return Status.WORKING;
        }
        Vec3 top = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
        if (self.getEyePosition().distanceTo(top) > Motor.BLOCK_REACH - 0.7) {
            motor.navigate(top, 1.5, false);
            if (motor.stuckCount() > 6) {
                target = null;
                return Status.FAILED;
            }
            return Status.WORKING;
        }
        motor.stop();
        if (++tries > 60) {
            failed.put(target, level.getGameTime());
            target = null;
            return Status.WORKING;
        }
        switch (job) {
            case HARVEST -> {
                Equipment.select(self, -1);
                if (motor.mine(target)) {
                    harvested++;
                    collect = 25;
                }
            }
            case PLANT -> {
                Equipment.select(self, seedSlot(self));
                motor.lookAt(top);
                boolean used = motor.useOnTopFace(target);
                last = "plant used=" + used;
                if (used && !level.getBlockState(target.above()).isAir()) {
                    planted++;
                    target = null;
                }
            }
            case TILL -> {
                Equipment.select(self, hoeSlot(self));
                motor.lookAt(top);
                boolean used = motor.useOnTopFace(target);
                last = "till used=" + used + " now=" + level.getBlockState(target);
                if (used && level.getBlockState(target).getBlock() instanceof FarmBlock) {
                    tilled++;
                    job = Job.PLANT; // plant right away on the fresh farmland
                    tries = 0;
                }
            }
        }
        return Status.WORKING;
    }
}
