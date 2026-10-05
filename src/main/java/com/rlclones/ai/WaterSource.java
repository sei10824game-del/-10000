package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Water for the fields at home. Once a clone owns a bucket it fills it at the nearest water that never runs dry (a
 * sea, a lake: a source block with source blocks beside it), carries it home and makes an endless spring right next
 * to the base like a player would: a 2x2 hole one deep, a bucket poured into two opposite corners.
 */
public final class WaterSource {
    public enum Status {WORKING, DONE, FAILED}

    private final ClonePlayer self;
    private final Motor motor;
    @Nullable
    /** Fewest blocks (either axis) between the base centre and a new spring. */
    private static final int SITE_GAP = 6;
    private BlockPos spring;
    private long springAt = -1000;
    private long checkedAt = -1000;
    private boolean cachedWork;
    @Nullable
    private BlockPos site;
    private final List<BlockPos> cells = new ArrayList<>();
    private int stage;
    private int ticks;
    private int pours;
    private int stuck;

    public int springsMade;
    public int bucketsFilled;
    public String debug = "";
    /** What happened, briefly (diagnostics, tests). */
    public final StringBuilder trace = new StringBuilder();
    private int tracedStage = -1;
    @Nullable
    private BlockPos lastStand;
    private int waitFrom;

    /** Which of the four cells are springs (diagnostics). */
    private String springs() {
        StringBuilder sb = new StringBuilder("[");
        for (BlockPos c : cells) {
            sb.append(source(c) ? 'S' : level().getFluidState(c).is(FluidTags.WATER) ? 'w' : level().getBlockState(c).isAir() ? '.' : '#');
        }
        return sb.append(']').toString();
    }

    /**
     * Stand on the rim right beside {@code cell} (sharing a side with it): from there the bucket aims down into it
     * clear of the rim. True once there.
     */
    private boolean pourFrom(BlockPos cell) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos rim = cell.relative(d);
            BlockPos stand = rim.above();
            if (cells.contains(rim) || !solid(rim) || !level().getBlockState(stand).isAir() || !level().getBlockState(stand.above()).isAir()) {
                continue;
            }
            double dd = stand.distSqr(self.blockPosition());
            if (dd < bestD) {
                bestD = dd;
                best = stand;
            }
        }
        if (best == null) {
            return reach(cell, 3.0);
        }
        Vec3 at = Vec3.atBottomCenterOf(best);
        if (Motor.horizontalDistance(self.position(), at) < 0.3 && Math.abs(self.getY() - at.y) < 0.5 && self.onGround()) {
            motor.stop();
            return true;
        }
        lastStand = best;
        motor.navigate(at, 0.2, false);
        if (motor.stuckCount() > 6) {
            stuck++;
            motor.resetStuck();
        }
        return false;
    }

    private void trace(String s) {
        if (trace.length() < 500) {
            trace.append(s).append(' ');
        }
    }

    public WaterSource(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    private ServerLevel level() {
        return self.serverLevel();
    }

    private boolean source(BlockPos p) {
        return level().getFluidState(p).isSource() && level().getFluidState(p).is(FluidTags.WATER);
    }

    /** A water source block with at least two source blocks beside it: refills itself however often it is scooped. */
    private boolean endless(BlockPos p) {
        if (!source(p)) {
            return false;
        }
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (source(p.relative(d))) {
                n++;
            }
        }
        return n >= 2;
    }

    private static boolean hasBucket(ClonePlayer p, boolean full) {
        for (ItemStack s : p.getInventory().items) {
            if (s.is(full ? Items.WATER_BUCKET : Items.BUCKET)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private Bases.Base home() {
        return Bases.get(self.getServer()).nearest(self.level().dimension(), self.position(), 64);
    }

    private boolean waterNear(BlockPos c, int r) {
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -2, -r), c.offset(r, 2, r))) {
            if (level().getFluidState(p).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** The nearest endless water within 32 blocks (looked for now and then; remembered). */
    @Nullable
    private BlockPos findSpring() {
        long now = level().getGameTime();
        if (spring != null && endless(spring)) {
            return spring;
        }
        if (now - springAt < 100) {
            return null;
        }
        springAt = now;
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-32, -6, -32), feet.offset(32, 4, 32))) {
            if (!level().getFluidState(p).isSource()) {
                continue;
            }
            double d = p.distSqr(feet);
            if (d < bestD && endless(p) && !level().getFluidState(p.above()).is(FluidTags.WATER)) {
                bestD = d;
                best = p.immutable();
            }
        }
        spring = best;
        return best;
    }

    /** A bucket in the bag, a base without water beside it, and somewhere to fill the bucket (or it is full already). */
    public boolean hasWork() {
        long now = level().getGameTime();
        if (now - checkedAt < 40) {
            return cachedWork;
        }
        checkedAt = now;
        cachedWork = false;
        if (!Config.get(Config.ALLOW_BLOCK_BREAKING, true) || !Config.get(Config.ALLOW_BLOCK_PLACING, true) || self.isCreative()
                || !hasBucket(self, true) && !hasBucket(self, false)) {
            return false;
        }
        Bases.Base home = home();
        if (home == null || Bases.get(self.getServer()).hasProject(self.level().dimension(), "water", home.center, 16)) {
            return false;
        }
        if (site != null && stage > 0) {
            cachedWork = true; // our spring, half made
            return true;
        }
        if (waterNear(home.center, 6)) {
            return false;
        }
        cachedWork = hasBucket(self, true) || findSpring() != null;
        return cachedWork;
    }

    /** Between FARM runs: the project itself (site, hole, pours) carries on where it was. */
    public void reset() {
        ticks = 0;
        stuck = 0;
        motor.resetMining();
    }

    private void abandon() {
        stage = 0;
        site = null;
        pours = 0;
        cells.clear();
        reset();
    }

    private boolean solid(BlockPos p) {
        return !level().getBlockState(p).getCollisionShape(level(), p).isEmpty() && level().getFluidState(p).isEmpty();
    }

    /** A flat 2x2 patch next to the base whose ground can be dug out one deep, with walls all round to hold the water. */
    @Nullable
    private BlockPos pickSite(BlockPos c) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-12, -2, -12), c.offset(11, 2, 11))) {
            int dx = Math.abs(p.getX() - c.getX());
            int dz = Math.abs(p.getZ() - c.getZ());
            if (Math.max(dx, dz) < SITE_GAP) {
                continue; // R-28: well clear of the house and its chests (they got in the way of the field)
            }
            boolean ok = true;
            for (int x = -1; x <= 2 && ok; x++) {
                for (int z = -1; z <= 2 && ok; z++) {
                    BlockPos q = p.offset(x, 0, z);
                    boolean inner = x >= 0 && x <= 1 && z >= 0 && z <= 1;
                    BlockState st = level().getBlockState(q);
                    ok = solid(q) && st.getDestroySpeed(level(), q) >= 0 && level().getBlockState(q.above()).isAir()
                            && (!inner || solid(q.below()) && st.getDestroySpeed(level(), q) < 3 && st.getBlock().asItem() != Items.CHEST);
                }
            }
            if (!ok) {
                continue;
            }
            double d = p.distSqr(c);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Turn to {@code point} at once and use the bucket in hand (a bucket aims along the line of sight). */
    private boolean useBucket(Vec3 point) {
        Vec3 eye = self.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        float yaw = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(Mth.clamp(pitch, -89f, 89f));
        self.resetLastActionTime();
        boolean used = self.gameMode.useItem(self, level(), self.getMainHandItem(), InteractionHand.MAIN_HAND).consumesAction();
        if (used) {
            self.swing(InteractionHand.MAIN_HAND);
        }
        return used;
    }

    private void hold(net.minecraft.world.item.Item item) {
        var inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(item)) {
                Equipment.select(self, i);
                return;
            }
        }
    }

    /** Within reach of {@code target} standing on dry ground; walks there otherwise. */
    private boolean reach(BlockPos target, double within) {
        if (self.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= within && !self.isInWater()) {
            motor.stop();
            return true;
        }
        BlockPos stand = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(target.offset(-3, -1, -3), target.offset(3, 2, 3))) {
            if (solid(p.below()) && level().getBlockState(p).isAir() && level().getBlockState(p.above()).isAir() && !cells.contains(p) && !cells.contains(p.below())
                    && new Vec3(p.getX() + 0.5, p.getY() + self.getEyeHeight(), p.getZ() + 0.5).distanceTo(Vec3.atCenterOf(target)) <= within - 0.3) {
                double d = p.distSqr(self.blockPosition());
                if (d < bestD) {
                    bestD = d;
                    stand = p.immutable();
                }
            }
        }
        lastStand = stand;
        motor.navigate(stand != null ? Vec3.atBottomCenterOf(stand) : Vec3.atCenterOf(target), stand != null ? 0.4 : 2.5, false);
        if (motor.stuckCount() > 6) {
            stuck++;
            motor.resetStuck();
        }
        return false;
    }

    public Status tick() {
        if (++ticks > 3000 || stuck > 4) {
            debug = "gave up at stage " + stage;
            abandon();
            return Status.FAILED;
        }
        Bases.Base home = home();
        if (home == null) {
            return Status.FAILED;
        }
        if (stage != tracedStage || ticks % 200 == 0) {
            tracedStage = stage;
            trace("s" + stage + "@" + ticks + (stage == 1 ? "/" + motor.mineDebug : "") + (stuck > 0 ? " stuck" + stuck : ""));
        }
        switch (stage) {
            case 0 -> {
                site = pickSite(home.center);
                if (site == null) {
                    debug = "no site near " + home.center.toShortString();
                    Bases.get(self.getServer()).addProject(self.level().dimension(), "water", home.center); // nowhere to put it: do not keep trying
                    return Status.FAILED;
                }
                cells.clear();
                cells.add(site);
                cells.add(site.offset(1, 0, 0));
                cells.add(site.offset(0, 0, 1));
                cells.add(site.offset(1, 0, 1));
                pours = 0;
                stage = 1;
                debug = "site " + site.toShortString();
            }
            case 1 -> {
                // dig the hole, one block deep
                BlockPos next = null;
                for (BlockPos c : cells) {
                    if (!level().getBlockState(c).isAir() && level().getFluidState(c).isEmpty()) {
                        next = c;
                        break;
                    }
                }
                if (next == null) {
                    stage = 2;
                    return Status.WORKING;
                }
                if (reach(next, Motor.BLOCK_REACH - 0.3)) {
                    Equipment.select(self, Equipment.bestToolSlot(self, level().getBlockState(next)));
                    motor.mine(next);
                }
            }
            case 2 -> {
                // a full bucket: from the endless water
                if (hasBucket(self, true)) {
                    stage = 3;
                    return Status.WORKING;
                }
                BlockPos src = findSpring();
                if (src == null || !hasBucket(self, false)) {
                    debug = "no water to fill from";
                    return src == null && ticks > 200 ? Status.FAILED : Status.WORKING;
                }
                if (ticks % 100 == 50) {
                    trace(String.format(java.util.Locale.ROOT, "[d%.1f w%s st%s src%s at%s]", self.getEyePosition().distanceTo(Vec3.atCenterOf(src)), self.isInWater(),
                            lastStand == null ? "-" : lastStand.subtract(src).toShortString(), src.toShortString(), self.blockPosition().subtract(src).toShortString()));
                }
                if (reach(src, 2.8)) {
                    hold(Items.BUCKET);
                    boolean used = useBucket(Vec3.atCenterOf(src).add(0, 0.3, 0)); // at the surface, from close by
                    if (ticks % 50 == 0) {
                        trace("use=" + used + "/" + self.getMainHandItem().getItem());
                    }
                    if (used && hasBucket(self, true)) {
                        bucketsFilled++;
                        debug = "filled at " + src.toShortString();
                        stage = 3;
                    }
                }
            }
            case 3 -> {
                // pour into a corner that is not a spring yet (opposite corners first), from right beside it
                BlockPos corner = null;
                for (int k : new int[]{0, 3, 1, 2}) {
                    if (!source(cells.get(k))) {
                        corner = cells.get(k);
                        break;
                    }
                }
                if (corner == null || source(cells.get(0)) && source(cells.get(3))) {
                    stage = 4;
                    waitFrom = ticks;
                    return Status.WORKING;
                }
                if (!hasBucket(self, true)) {
                    stage = 2;
                    return Status.WORKING;
                }
                if (pourFrom(corner)) {
                    hold(Items.WATER_BUCKET);
                    boolean used = useBucket(new Vec3(corner.getX() + 0.5, corner.getY() + 0.02, corner.getZ() + 0.5));
                    if (used) {
                        pours++;
                        debug = "poured " + pours + " at " + corner.toShortString();
                        trace("pour" + cells.indexOf(corner) + (source(corner) ? "+" : "-") + springs());
                        stage = source(cells.get(0)) && source(cells.get(3)) ? 4 : 2;
                        waitFrom = ticks;
                    }
                }
            }
            default -> {
                int sources = 0;
                for (BlockPos c : cells) {
                    if (source(c)) {
                        sources++;
                    }
                }
                if (sources < 4) {
                    if (ticks - waitFrom < 60) {
                        return Status.WORKING; // the other two corners fill in a moment
                    }
                    if (ticks < 2900) {
                        stage = 2; // not yet: another bucket
                        return Status.WORKING;
                    }
                }
                Bases.get(self.getServer()).addProject(self.level().dimension(), "water", site);
                springsMade += sources >= 4 ? 1 : 0;
                debug = "spring at " + site.toShortString() + " (" + sources + " sources)";
                cachedWork = false;
                abandon();
                return sources >= 4 ? Status.DONE : Status.FAILED;
            }
        }
        return Status.WORKING;
    }
}
