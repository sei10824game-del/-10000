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

    private enum Job {HARVEST, PLANT, TILL, SEEDS, LIGHT, SOIL}

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
    /** Bank blocks beside water turned into soil / torches put up so crops can grow (diagnostics, tests). */
    public int soilMade;
    public int torchesPlaced;
    /** The plot by the water is dark and there is no torch in the bag (the controller makes one / asks for one). */
    public boolean needTorch;
    /** Water with no soil beside it, and no dirt in the bag to make some. */
    public boolean needDirt;
    private String last = "";

    /** State for diagnostics. */
    /** How many spots pass each test on the way to "tillable and visible" (diagnostics). */
    public String diag() {
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        int soil = 0, open = 0, wet = 0, lit = 0, seen = 0;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-RADIUS, -2, -RADIUS), feet.offset(RADIUS, 1, RADIUS))) {
            BlockState st = level.getBlockState(p);
            if (!(st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT))) {
                continue;
            }
            soil++;
            if (!level.getBlockState(p.above()).isAir()) {
                continue;
            }
            open++;
            if (!nearWater(level, p)) {
                continue;
            }
            wet++;
            if (!bright(level, p.above())) {
                continue;
            }
            lit++;
            if (visible(level, p, false)) {
                seen++;
            }
        }
        return "soil=" + soil + " open=" + open + " wet=" + wet + " lit=" + lit + " seen=" + seen;
    }

    public String debug() {
        return "job=" + job + " target=" + target + " tries=" + tries + " ticks=" + ticks + " tilled=" + tilled + " planted=" + planted + " failed=" + failed.size()
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

    private static boolean isGrass(BlockState st) {
        return st.is(Blocks.GRASS) || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN);
    }

    private int seedCount() {
        int n = 0;
        for (net.minecraft.world.item.ItemStack s : self.getInventory().items) {
            if (Storage.isSeed(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    public int grassCut;

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

    private static boolean soilish(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.ROOTED_DIRT);
    }

    private int dirtSlot() {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.is(net.minecraft.world.item.Items.DIRT) || s.is(net.minecraft.world.item.Items.GRASS_BLOCK) || s.is(net.minecraft.world.item.Items.COARSE_DIRT)
                    || s.is(net.minecraft.world.item.Items.ROOTED_DIRT)) {
                return i;
            }
        }
        return -1;
    }

    private int torchSlot() {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(net.minecraft.world.item.Items.TORCH)) {
                return i;
            }
        }
        return -1;
    }

    private int torchCount() {
        int n = 0;
        for (net.minecraft.world.item.ItemStack st : self.getInventory().items) {
            if (st.is(net.minecraft.world.item.Items.TORCH)) {
                n += st.getCount();
            }
        }
        return n;
    }

    /** R-26: torches beyond the 16 kept for digging. */
    private boolean sparingTorches() {
        return torchCount() > 16;
    }

    /** A crop (or farmland) too dark for the plants to grow. */
    private static boolean cropDark(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getBlock() instanceof FarmBlock && level.getBlockState(p.above()).isAir() && level.getRawBrightness(p.above(), 0) < 9
                || level.getBlockState(p).getBlock() instanceof FarmBlock && level.getBlockState(p.above()).getBlock() instanceof net.minecraft.world.level.block.CropBlock
                && level.getRawBrightness(p.above(), 0) < 9;
    }

    /** R-25: the plot being worked: tilled and sown cell by cell, nothing else in between, until it is done. */
    private final java.util.ArrayDeque<BlockPos> lot = new java.util.ArrayDeque<>();

    private void planLot(ServerLevel level, BlockPos first) {
        lot.clear();
        java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        todo.add(first);
        seen.add(first);
        int n = Math.max(1, seedCount());
        while (!todo.isEmpty() && lot.size() < n) {
            BlockPos p = todo.poll();
            if (!tillable(level, p)) {
                continue;
            }
            lot.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos q = p.offset(dx, 0, dz);
                    if ((dx != 0 || dz != 0) && Math.abs(q.getX() - first.getX()) <= 6 && Math.abs(q.getZ() - first.getZ()) <= 6 && seen.add(q)) {
                        todo.add(q);
                    }
                }
            }
        }
    }

    private static boolean waterBeside(ServerLevel level, BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(pos.relative(d)).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** A plain bank block right beside the water (stone, sand, gravel...) that could be swapped for soil. */
    private boolean bank(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir() || soilish(st) || st.getBlock() instanceof FarmBlock || !level.getFluidState(pos).isEmpty() || st.hasBlockEntity()
                || !level.getBlockState(pos.above()).isAir() || !st.isCollisionShapeFullBlock(level, pos)) {
            return false;
        }
        float hard = st.getDestroySpeed(level, pos);
        return hard >= 0 && hard < 3 && (st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) || st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL))
                && waterBeside(level, pos) && !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()
                && self.mayInteract(level, pos);
    }

    private boolean tillable(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.ROOTED_DIRT))) {
            return false;
        }
        return level.getBlockState(pos.above()).isAir() && nearWater(level, pos) && bright(level, pos.above());
    }

    /** Crops only take where there is light (sky or a lamp). */
    private static boolean bright(ServerLevel level, BlockPos pos) {
        return level.getRawBrightness(pos, 0) >= 8 || level.canSeeSky(pos);
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
        // something to break must be the first thing the line hits (OUTLINE); soil to work on top of may sit behind
        // grass and crops (VISUAL ignores shapes without collision)
        BlockHitResult hit = level.clip(new ClipContext(eye, top, cropOnTop ? ClipContext.Block.OUTLINE : ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, self));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private static BlockState st(ServerLevel level, BlockPos p) {
        return level.getBlockState(p);
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
        while (!lot.isEmpty() && seeds) {
            BlockPos p = lot.peek();
            Long lf = failed.get(p);
            if (lf != null && self.level().getGameTime() - lf < 1200) {
                lot.poll();
            } else if (st(level, p).getBlock() instanceof FarmBlock && level.getBlockState(p.above()).isAir()) {
                out[0] = Job.PLANT;
                return p;
            } else if (hoe && tillable(level, p)) {
                out[0] = Job.TILL;
                return p;
            } else {
                lot.poll(); // done (sown) or no longer fit
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-RADIUS, -2, -RADIUS), feet.offset(RADIUS, 1, RADIUS))) {
            BlockState st = level.getBlockState(p);
            Job j = null;
            if (breaking && ripe(st)) {
                j = Job.HARVEST;
            } else if (seeds && st.getBlock() instanceof FarmBlock && level.getBlockState(p.above()).isAir() && bright(level, p.above())) {
                j = Job.PLANT;
            } else if (seeds && hoe && tillable(level, p)) {
                j = Job.TILL;
            } else if (breaking && seedCount() < 4 && isGrass(st)) {
                j = Job.SEEDS; // cut grass: wheat seeds drop from it
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
            if (score < bestScore && visible(level, p, j == Job.HARVEST || j == Job.SEEDS)) {
                bestScore = score;
                best = p.immutable();
                out[0] = j;
            }
        }
        if ((best == null || out[0] == Job.SEEDS) && seeds && hoe && breaking) {
            // water but no soil to work beside it (or too dark for crops): make soil from the bank, light the plot
            boolean dirt = dirtSlot() >= 0;
            boolean placing = Config.get(Config.ALLOW_BLOCK_PLACING, true);
            BlockPos plot = null;
            Job plotJob = null;
            double plotD = Double.MAX_VALUE;
            boolean wantDirt = false;
            for (BlockPos p : BlockPos.betweenClosed(feet.offset(-RADIUS, -2, -RADIUS), feet.offset(RADIUS, 1, RADIUS))) {
                BlockState st = level.getBlockState(p);
                boolean darkSoil = soilish(st) && level.getBlockState(p.above()).isAir() && nearWater(level, p) && !bright(level, p.above());
                boolean bankSpot = !darkSoil && bank(level, p);
                if (!darkSoil && !bankSpot) {
                    continue;
                }
                Long f = failed.get(p);
                if (f != null && self.level().getGameTime() - f < 1200) {
                    continue;
                }
                boolean lit = bright(level, p.above());
                if (bankSpot && lit && !dirt) {
                    wantDirt = true;
                    continue;
                }
                if (!placing) {
                    continue;
                }
                double d = p.distSqr(feet);
                if (d < plotD && visible(level, p, bankSpot)) {
                    plotD = d;
                    plot = p.immutable();
                    plotJob = lit ? Job.SOIL : Job.LIGHT;
                }
            }
            needDirt = wantDirt && plot == null;
            if (plot != null) {
                out[0] = plotJob;
                return plot;
            }
        }
        if (best == null && sparingTorches() && Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            // R-26: spare torches light the crops (a torch every 4-5 blocks follows from lighting where it is dark)
            double d0 = Double.MAX_VALUE;
            for (BlockPos p : BlockPos.betweenClosed(feet.offset(-RADIUS, -2, -RADIUS), feet.offset(RADIUS, 1, RADIUS))) {
                Long f = failed.get(p);
                if (cropDark(level, p) && (f == null || self.level().getGameTime() - f >= 1200) && p.distSqr(feet) < d0 && visible(level, p, false)) {
                    d0 = p.distSqr(feet);
                    best = p.immutable();
                    out[0] = Job.LIGHT;
                }
            }
        }
        if (best != null && out[0] == Job.TILL && lot.isEmpty()) {
            planLot(level, best); // R-25: as many cells as there are seeds, in one go
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
            case SEEDS -> isGrass(st);
            case LIGHT -> !bright(level, target.above()) && (soilish(st) || bank(level, target)) || sparingTorches() && cropDark(level, target);
            case SOIL -> dirtSlot() >= 0 && (bank(level, target) || st.canBeReplaced() && !level.getBlockState(target.below()).getCollisionShape(level, target.below()).isEmpty());
        };
        if (!stillValid) {
            target = null;
            return Status.WORKING;
        }
        Vec3 top = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
        if (self.getEyePosition().distanceTo(top) > Motor.BLOCK_REACH - 0.7) {
            if (job == Job.SEEDS) {
                motor.sweep(); // R-17: cut the grass on the way there
            }
            motor.navigate(top, 1.5, false);
            if (motor.stuckCount() > 6) {
                failed.put(target, level.getGameTime()); // cannot get there: try other spots first
                last = "stuck going to " + target.toShortString();
                target = null;
                motor.resetStuck();
                return Status.FAILED;
            }
            return Status.WORKING;
        }
        motor.stop();
        if (++tries > (job == Job.SOIL ? 400 : 60)) {
            failed.put(target, level.getGameTime());
            target = null;
            return Status.WORKING;
        }
        switch (job) {
            case LIGHT -> {
                int torch = torchSlot();
                if (torch < 0) {
                    needTorch = true; // the controller makes one (or asks a friend)
                    last = "no torch for " + target.toShortString();
                    target = null;
                    return Status.FAILED;
                }
                needTorch = false;
                Equipment.select(self, torch);
                BlockPos spot = null;
                double bestD = Double.MAX_VALUE;
                for (BlockPos q : BlockPos.betweenClosed(target.offset(-2, 1, -2), target.offset(2, 1, 2))) {
                    BlockPos under = q.below();
                    if (q.equals(target.above()) || !level.getBlockState(q).isAir() || !level.getFluidState(q).isEmpty()
                            || self.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(q)) || bank(level, under)
                            || !level.getBlockState(under).isFaceSturdy(level, under, net.minecraft.core.Direction.UP) || soilish(level.getBlockState(under))
                            || under.equals(target) || level.getBlockState(under).getBlock() instanceof FarmBlock) {
                        continue;
                    }
                    double d = Vec3.atCenterOf(q).distanceTo(self.getEyePosition());
                    if (d < Motor.BLOCK_REACH - 0.3 && d < bestD) {
                        bestD = d;
                        spot = q.immutable();
                    }
                }
                if (spot != null && motor.placeBlockAt(spot)) {
                    torchesPlaced++;
                    last = "torch at " + spot.toShortString();
                    target = null; // light now: soil / till next
                } else if (spot == null) {
                    failed.put(target, level.getGameTime());
                    target = null;
                }
            }
            case SOIL -> {
                BlockState cur = level.getBlockState(target);
                if (bank(level, target)) {
                    Equipment.select(self, Equipment.bestToolSlot(self, cur));
                    motor.mine(target); // the bank block out...
                } else {
                    Equipment.select(self, dirtSlot());
                    if (motor.placeBlockAt(target)) {
                        soilMade++; // ...and soil in its place
                        last = "soil at " + target.toShortString();
                        job = Job.TILL;
                        tries = 0;
                    }
                }
            }
            case SEEDS -> {
                Equipment.select(self, -1);
                if (motor.mine(target)) {
                    grassCut++;
                    collect = 15;
                }
            }
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
