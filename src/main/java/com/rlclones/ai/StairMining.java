package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * With nothing left to do on the surface and no full iron gear yet: dig a staircase down towards the iron levels
 * (a 1-wide stair, three blocks cleared per step, torches from the lighting reflex). Staircases are recorded with
 * the bases, so a clone that finds one already started walks down it and carries on digging from its bottom.
 */
public final class StairMining {
    public enum Status {WORKING, DONE, FAILED}

    private final ClonePlayer self;
    private final Motor motor;
    @Nullable
    private Bases.Staircase stairs;
    private int stage;
    private int ticks;
    private int stuck;
    private BlockPos lastFeet;
    /** Progress in the current stage (best value so far, when it was reached): no progress for long = the staircase is given up. */
    private int bestMetric = Integer.MIN_VALUE;
    private int progressTick;
    /** Staircases this clone failed to follow: not used again before the game time given. */
    private final java.util.Map<BlockPos, Long> avoid = new java.util.HashMap<>();
    public int abandoned;
    public int giveUps;
    /** Cells of level tunnel dug at the target depth, all in all (plan.md P-05). */
    public int tunnelDug;
    private static final int TUNNEL_CAP = 160;
    private static final int TURN_EVERY = 32;

    /** Dig down to here; unset (MIN_VALUE) = what the next step needs (iron: 16, diamonds: -58), see {@link Progression}. */
    public int targetY = Integer.MIN_VALUE;
    /** Tests: treat the arena (underground) as the surface. */
    public boolean assumeSurface;
    public int stepsDug;
    public int resumed;
    public String debug = "";
    /** Recent events (diagnostics, tests). */
    public String trace = "";

    /** The last events only (soak diagnostics: {@link #trace} stops filling up after the first 500 characters). */
    public String recent = "";

    private void trace(String what) {
        if (trace.length() < 500) {
            trace += " " + what;
        }
        recent += " " + what;
        if (recent.length() > 300) {
            recent = recent.substring(recent.length() - 200);
        }
    }

    public StairMining(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    private Bases bases() {
        return Bases.get(self.getServer());
    }

    /** A full set of iron (or better) armour, an iron pickaxe and an iron sword. */
    public static boolean ironGeared(net.minecraft.world.entity.player.Player p) {
        boolean pick = false;
        boolean sword = false;
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof PickaxeItem t && t.getTier().getLevel() >= Tiers.IRON.getLevel()) {
                pick = true;
            }
            if (s.getItem() instanceof SwordItem t && t.getTier().getLevel() >= Tiers.IRON.getLevel()) {
                sword = true;
            }
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            boolean ok = p.getItemBySlot(slot).getItem() instanceof ArmorItem a && a.getDefense() >= ironDefense(slot);
            for (ItemStack s : p.getInventory().items) {
                ok |= s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot && a.getDefense() >= ironDefense(slot);
            }
            if (!ok) {
                return false;
            }
        }
        return pick && sword;
    }

    private static int ironDefense(EquipmentSlot slot) {
        return ArmorMaterials.IRON.getDefenseForType(switch (slot) {
            case HEAD -> ArmorItem.Type.HELMET;
            case CHEST -> ArmorItem.Type.CHESTPLATE;
            case LEGS -> ArmorItem.Type.LEGGINGS;
            default -> ArmorItem.Type.BOOTS;
        });
    }

    private int pickTier() {
        int best = -1;
        for (ItemStack s : self.getInventory().items) {
            if (s.getItem() instanceof PickaxeItem t) {
                best = Math.max(best, t.getTier().getLevel());
            }
        }
        return best;
    }

    /** How deep to dig now. */
    public int target() {
        return targetY != Integer.MIN_VALUE ? targetY : Progression.digTargetY(Progression.need(self));
    }

    /** On the surface (sky overhead), carrying a pickaxe, still missing iron or diamond gear, not deep enough yet. */
    public boolean wanted() {
        if (!Config.get(Config.ALLOW_BLOCK_BREAKING, true) || pickTier() < 0 || ironGeared(self) && !Progression.digWanted(self) || self.isInWater()) {
            return false;
        }
        boolean surface = assumeSurface || self.level().canSeeSky(self.blockPosition().above());
        Bases.Staircase known = bases().nearestStaircase(self.level().dimension(), self.position(), 64, this::usable);
        if (known != null && branchable(known)) {
            return true; // down at the depth already, iron / diamonds still to find: the tunnel goes on
        }
        return (surface || known != null) && self.getBlockY() > target();
    }

    /** At the target depth with iron / diamonds still to find and tunnel left to dig. */
    private boolean branchable(Bases.Staircase s) {
        return s.end.getY() <= target() && s.tunnelLen < TUNNEL_CAP && Progression.digWanted(self);
    }

    /** Digging is still worth it: depth to go down, or a tunnel to carry on with. */
    public boolean pending() {
        if (self.getBlockY() > target()) {
            return true;
        }
        Bases.Staircase known = bases().nearestStaircase(self.level().dimension(), self.position(), 64, this::usable);
        return known != null && branchable(known);
    }

    private boolean usable(Bases.Staircase s) {
        Long t = avoid.get(s.top);
        return t == null || self.level().getGameTime() >= t;
    }

    /** Staircases with their top or bottom within {@code radius} of {@code pos} are left alone until {@code until} (a pit we keep falling into). */
    public void avoidNear(BlockPos pos, int radius, long until) {
        for (Bases.Staircase s : bases().staircases) {
            if (s.dimension == self.level().dimension() && (s.top.distManhattan(pos) <= radius || s.end.distManhattan(pos) <= radius)) {
                avoid.put(s.top, until);
            }
        }
    }

    private void toStage(int n) {
        stage = n;
        bestMetric = Integer.MIN_VALUE;
        progressTick = ticks;
    }

    private void progress(int metric) {
        if (metric > bestMetric) {
            bestMetric = metric;
            progressTick = ticks;
        }
    }

    private boolean stalled(int limit) {
        return ticks - progressTick > limit;
    }

    /** The staircase could not be followed: avoid it for a long while; the second failure gives it up for everybody. */
    private Status failStairs(String why) {
        debug = why;
        trace("fail:" + why);
        giveUps++;
        Bases.Staircase s = stairs;
        if (s != null) {
            avoid.put(s.top, self.level().getGameTime() + 12000);
            if (++s.failures >= 2) {
                s.finished = true;
                bases().setDirty();
            }
        }
        return Status.FAILED;
    }

    /** Off the line of the staircase (fallen, pushed): leave it and dig a new one from where we stand. */
    private void abandonStairs(String why, boolean broken) {
        BlockPos feet = self.blockPosition();
        debug = "abandon " + why + " at " + feet.toShortString();
        trace("abandon:" + why);
        abandoned++;
        Bases.Staircase s = stairs;
        if (s != null) {
            avoid.put(s.top, self.level().getGameTime() + 12000);
            if (broken || ++s.failures >= 2) {
                s.finished = true;
                bases().setDirty();
            }
        }
        stairs = null;
        toStage(2);
    }

    public void begin() {
        trace("begin@" + self.blockPosition().toShortString());
        stairs = bases().nearestStaircase(self.level().dimension(), self.position(), 64, this::usable);
        int first = stairs == null ? 2 : 0; // 0: walk to the top of a known staircase, 1: down it, 2: dig
        if (stairs != null) {
            resumed++;
            BlockPos feet = self.blockPosition();
            if (feet.distManhattan(stairs.end) <= 2) {
                first = 2; // already down at the bottom
            } else if (feet.distManhattan(stairs.top) <= 2 || onStairs(feet)) {
                first = 1;
            }
            if (stairs.end.getY() <= target() && stairs.tunnelEnd != null && feet.distManhattan(stairs.tunnelEnd) <= 3) {
                first = 3; // down in the tunnel already
            }
            debug = "resume " + stairs.top.toShortString() + " -> " + stairs.end.toShortString() + " from stage " + first;
        }
        ticks = 0;
        toStage(first);
        stuck = 0;
        lastFeet = self.blockPosition();
        motor.resetStuck();
    }

    /** Which step of the staircase we stand on (0 = the top), -1 if none. */
    private int stepIndex(BlockPos feet) {
        Bases.Staircase s = stairs;
        if (s == null) {
            return -1;
        }
        int best = -1;
        for (int i = 0; i <= s.steps(); i++) {
            BlockPos step = s.top.relative(s.dir, i).below(i);
            if (step.getX() == feet.getX() && step.getZ() == feet.getZ() && Math.abs(step.getY() - feet.getY()) <= 1) {
                best = i;
            }
        }
        return best;
    }

    /** Standing somewhere on the steps between the top and the bottom. */
    private boolean onStairs(BlockPos feet) {
        Bases.Staircase s = stairs;
        if (s == null) {
            return false;
        }
        for (int i = 0; i <= s.steps(); i++) {
            if (s.top.relative(s.dir, i).below(i).distManhattan(feet) <= 1) {
                return true;
            }
        }
        return false;
    }

    private boolean solid(BlockPos p) {
        ServerLevel level = self.serverLevel();
        return level.getFluidState(p).isEmpty() && !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** Safe to dig out: breakable, no liquid next to it, nobody's base. */
    private boolean diggable(BlockPos p) {
        ServerLevel level = self.serverLevel();
        BlockState st = level.getBlockState(p);
        if (!st.getFluidState().isEmpty()) {
            return false;
        }
        if (!st.isAir()) {
            float h = st.getDestroySpeed(level, p);
            if (h < 0 || h >= 50 || st.hasBlockEntity() || !Equipment.canHarvest(self, st) && h > 2.0f) {
                return false;
            }
        }
        for (Direction f : Direction.values()) {
            if (!level.getFluidState(p.relative(f)).isEmpty()) {
                return false; // water or lava behind it
            }
        }
        return bases().nearest(level.dimension(), Vec3.atCenterOf(p), 8) == null;
    }

    public Status tick() {
        if (++ticks > 3000) {
            return stage < 2 && stairs != null ? failStairs("timed out in stage " + stage) : Status.DONE;
        }
        BlockPos feet = self.blockPosition();
        if (feet.equals(lastFeet)) {
            stuck++;
        } else {
            stuck = 0;
            lastFeet = feet;
        }
        if (ticks % 100 == 1) {
            trace("s" + stage + "@" + feet.toShortString() + (stage == 0 ? " " + motor.mineDebug.length() + "/" + motor.stuckCount() : ""));
        }
        switch (stage) {
            case 0 -> {
                Bases.Staircase s = stairs;
                if (s == null) {
                    toStage(2);
                    return Status.WORKING;
                }
                progress(-feet.distManhattan(s.top));
                if (stalled(300)) {
                    return failStairs("no nearer the top " + s.top.toShortString() + " from " + feet.toShortString());
                }
                if (motor.navigate(Vec3.atBottomCenterOf(s.top), 1.0, false) || stepIndex(feet) >= 0) {
                    toStage(1);
                    motor.resetStuck();
                } else if (motor.stuckCount() > 8) {
                    return failStairs("cannot reach the top " + s.top.toShortString() + " from " + feet.toShortString());
                }
            }
            case 1 -> {
                // down the steps to where the digging stopped, one step after the other
                Bases.Staircase s = stairs;
                if (s == null) {
                    return Status.FAILED;
                }
                int i = stepIndex(feet);
                if (stalled(300)) {
                    return failStairs("no further down at " + feet.toShortString() + " (step " + i + " of " + s.steps() + ")");
                }
                if (i < 0) {
                    if (feet.getY() <= s.top.getY() - 2 && feet.distManhattan(s.top) <= 6) {
                        abandonStairs("fell into a hole by the top", false); // jumping back up at the top does not work from down here
                        return Status.WORKING;
                    }
                    if (feet.distManhattan(s.top) <= 3) {
                        // right by the top step: onto it
                        motor.moveToward(Vec3.atBottomCenterOf(s.top));
                        if (s.top.getY() > feet.getY() && self.onGround()) {
                            motor.jump();
                        }
                        return stuck > 80 ? failStairs("stuck by the top at " + feet.toShortString()) : Status.WORKING;
                    }
                    toStage(0); // not on the stairs (any more): to the top first
                    return Status.WORKING;
                }
                progress(i);
                if (i >= s.steps()) {
                    toStage(2);
                    return Status.WORKING;
                }
                if (self.onGround()) {
                    motor.moveToward(Vec3.atBottomCenterOf(s.top.relative(s.dir, i + 1).below(i + 1)));
                }
                if (stuck > 80) {
                    return failStairs("stuck on step " + i + " of " + s.steps() + " at " + feet.toShortString());
                }
            }
            case 3 -> {
                return tunnelTick(feet);
            }
            default -> {
                return digTick(feet);
            }
        }
        return Status.WORKING;
    }

    private Status digTick(BlockPos feet) {
        if (stairs == null) {
            Direction d = pickDirection(feet);
            if (d == null) {
                return Status.FAILED;
            }
            stairs = bases().addStaircase(self.level().dimension(), feet, d);
            debug = "new staircase " + feet.toShortString() + " " + d;
            trace("new@" + feet.toShortString() + d);
        }
        Bases.Staircase s = stairs;
        progress(-s.end.getY());
        if (stalled(400)) {
            return failStairs("no progress digging at " + feet.toShortString());
        }
        if (self.onGround() && feet.equals(s.end.relative(s.dir).below())) {
            stepsDug++; // stepped down onto the new step: the staircase grows along its line only
            s.end = feet.immutable();
            bases().setDirty();
        } else if (!feet.equals(s.end) && !feet.equals(s.end.relative(s.dir))) { // (just past the end, level with it: on the way down onto the next step)
            if (!self.onGround()) {
                return Status.WORKING; // in the air (stepping down): see where we land
            }
            if (feet.getY() < s.end.getY()) {
                abandonStairs("off the line below the end", true); // fell / was pushed down: the record no longer fits the stone
                return Status.WORKING;
            }
            if (feet.distManhattan(s.end) > 3) {
                toStage(0); // away from the bottom: back down the stairs
                return Status.WORKING;
            }
            if (self.onGround()) {
                motor.moveToward(Vec3.atBottomCenterOf(s.end));
            }
            return stuck > 60 ? failStairs("cannot get to the end " + s.end.toShortString() + " from " + feet.toShortString()) : Status.WORKING;
        }
        if (feet.getY() <= target()) {
            if (branchable(s)) {
                toStage(3); // deep enough, but no iron / diamonds yet: along the level
                return Status.WORKING;
            }
            s.finished = true;
            bases().setDirty();
            return Status.DONE;
        }
        // the next step: one ahead, one down from the end; head room for walking down into it
        BlockPos next = s.end.relative(s.dir).below();
        BlockPos[] clear = {next.above(2), next.above(), next};
        for (BlockPos p : clear) {
            if (solid(p)) {
                if (!diggable(p)) {
                    s.finished = true; // water, lava, bedrock...: this staircase ends here
                    bases().setDirty();
                    debug = "stopped at " + p.toShortString() + " " + self.level().getBlockState(p);
                    return Status.DONE;
                }
                motor.stop();
                Equipment.select(self, Equipment.bestToolSlot(self, self.level().getBlockState(p)));
                motor.mine(p);
                return Status.WORKING;
            }
        }
        BlockPos floor = next.below();
        if (!solid(floor)) {
            int slot = Equipment.pillarBlockSlot(self);
            if (slot < 0 || !self.level().getFluidState(floor).isEmpty()) {
                s.finished = true;
                bases().setDirty();
                debug = "nothing to stand on at " + floor.toShortString();
                return Status.DONE;
            }
            Equipment.select(self, slot);
            motor.placeBlockAt(floor);
            return Status.WORKING;
        }
        // step down
        motor.moveToward(Vec3.atBottomCenterOf(next));
        if (stuck > 60) {
            return failStairs("cannot step down to " + next.toShortString() + " from " + feet.toShortString());
        }
        return Status.WORKING;
    }

    /** No liquid in the cell or next to it. */
    private boolean dry(BlockPos p) {
        ServerLevel level = self.serverLevel();
        if (!level.getFluidState(p).isEmpty()) {
            return false;
        }
        for (Direction f : Direction.values()) {
            if (!level.getFluidState(p.relative(f)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The cells (2 high) ahead in direction {@code d} from {@code tip}: nothing in the way that cannot be dug, no liquid. */
    private boolean openAhead(BlockPos tip, Direction d) {
        BlockPos next = tip.relative(d);
        for (BlockPos p : new BlockPos[]{next, next.above()}) {
            if (solid(p) ? !diggable(p) : !dry(p)) {
                return false;
            }
        }
        return true;
    }

    /** The way on is blocked (water, lava, bedrock...): turn if a side is open, else this staircase ends here. */
    private Status turnOrFinish(Bases.Staircase s, Direction d, String why) {
        BlockPos tip = s.tunnelEnd != null ? s.tunnelEnd : s.end;
        for (Direction t : new Direction[]{d.getClockWise(), d.getCounterClockWise()}) {
            if (openAhead(tip, t)) {
                s.tunnelDir = t;
                trace("turn@" + tip.toShortString() + t);
                return Status.WORKING;
            }
        }
        s.finished = true;
        bases().setDirty();
        debug = "tunnel ends at " + tip.toShortString() + ": " + why;
        return Status.DONE;
    }

    /** Level with the target depth and iron / diamonds still to find: a 1x2 tunnel straight on, turning right every so often. */
    private Status tunnelTick(BlockPos feet) {
        Bases.Staircase s = stairs;
        if (s == null) {
            toStage(2);
            return Status.WORKING;
        }
        if (!Progression.digWanted(self) || s.tunnelLen >= TUNNEL_CAP) {
            s.finished = true;
            bases().setDirty();
            return Status.DONE;
        }
        boolean hungry = self.getFoodData().getFoodLevel() < 8 && FoodAid.foodItems(self) == 0 && !Senses.hasFood(self);
        if (self.getHealth() < self.getMaxHealth() * 0.5f || hungry) {
            debug = "leaves the tunnel: " + (hungry ? "hungry" : "hurt");
            return Status.DONE; // (the tunnel stays: it is carried on later)
        }
        progress(s.tunnelLen);
        if (stalled(300)) {
            return failStairs("no progress in the tunnel at " + feet.toShortString());
        }
        BlockPos tip = s.tunnelEnd != null ? s.tunnelEnd : s.end;
        Direction d = s.tunnelDir != null ? s.tunnelDir : s.dir;
        if (!feet.equals(tip)) {
            if (!self.onGround()) {
                return Status.WORKING;
            }
            if (!feet.equals(tip.relative(d))) {
                if (feet.distManhattan(tip) > 2) {
                    motor.navigate(Vec3.atBottomCenterOf(tip), 1.0, false); // back to where the digging stopped
                    return motor.stuckCount() > 8 ? failStairs("cannot get to the tunnel tip " + tip.toShortString() + " from " + feet.toShortString()) : Status.WORKING;
                }
                motor.moveToward(Vec3.atBottomCenterOf(tip));
                return stuck > 60 ? failStairs("cannot get back to the tunnel tip at " + feet.toShortString()) : Status.WORKING;
            }
            // stepped forward onto the cell just dug
            s.tunnelEnd = feet.immutable();
            s.tunnelLen++;
            tunnelDug++;
            bases().setDirty();
            tip = s.tunnelEnd;
            if (s.tunnelLen % TURN_EVERY == 0) {
                s.tunnelDir = d.getClockWise();
                d = s.tunnelDir;
            }
        }
        BlockPos next = tip.relative(d);
        for (BlockPos p : new BlockPos[]{next.above(), next}) {
            if (solid(p)) {
                if (!diggable(p)) {
                    return turnOrFinish(s, d, "cannot dig " + p.toShortString() + " " + self.level().getBlockState(p));
                }
                motor.stop();
                Equipment.select(self, Equipment.bestToolSlot(self, self.level().getBlockState(p)));
                motor.mine(p);
                return Status.WORKING;
            }
            if (!dry(p)) {
                return turnOrFinish(s, d, "liquid at " + p.toShortString());
            }
        }
        BlockPos floor = next.below();
        if (!solid(floor)) {
            int slot = Equipment.pillarBlockSlot(self);
            if (slot < 0 || !dry(floor)) {
                return turnOrFinish(s, d, "no floor at " + floor.toShortString());
            }
            Equipment.select(self, slot);
            motor.placeBlockAt(floor);
            return Status.WORKING;
        }
        motor.moveToward(Vec3.atBottomCenterOf(next));
        return stuck > 60 ? failStairs("cannot step on in the tunnel at " + feet.toShortString()) : Status.WORKING;
    }

    /** A direction with solid ground to dig into (no liquid, no drop right ahead). */
    @Nullable
    private Direction pickDirection(BlockPos feet) {
        StringBuilder why = new StringBuilder();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos next = feet.relative(d).below();
            boolean floor = solid(next.below()) || Equipment.pillarBlockSlot(self) >= 0 && self.level().getFluidState(next.below()).isEmpty()
                    && solid(next.below(2));
            boolean a = diggable(next);
            boolean b = diggable(next.above());
            boolean c = diggable(next.above(2));
            if (floor && a && b && c) {
                return d;
            }
            why.append(' ').append(d).append(floor ? "" : ":nofloor").append(a ? "" : ":step").append(b ? "" : ":head").append(c ? "" : ":top");
        }
        debug = "no way down at " + feet.toShortString() + why;
        trace("nodir@" + feet.toShortString());
        return null;
    }

    public boolean busy() {
        return stairs != null && stage >= 0;
    }
}
