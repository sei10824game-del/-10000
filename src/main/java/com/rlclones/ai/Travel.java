package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import com.rlclones.ai.brain.Brain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Getting across: when the way to a far goal is cut by a gap (a drop, lava, water), build a bridge with blocks the
 * clone does not need (crouching at the edge), or - with nothing to build with - throw an ender pearl across.
 */
public final class Travel {
    private final ClonePlayer self;
    private final Motor motor;
    private final Consumables consumables;

    private boolean bridging;
    private Direction dir = Direction.NORTH;
    private int gap;
    private int placed;
    private int ticks;
    private int steps;
    private BlockPos lastFeet;
    private int cooldown;

    public int bridges;
    public int blocksBridged;
    public String debug = "";

    public Travel(ClonePlayer self, Motor motor, Consumables consumables) {
        this.self = self;
        this.motor = motor;
        this.consumables = consumables;
    }

    public boolean busy() {
        return bridging || parkour || tunnel != null || stairsDir != null || stairBridge != null || waterColumn != null || dropWater != null;
    }

    private boolean solid(BlockPos p) {
        ServerLevel level = self.serverLevel();
        return level.getFluidState(p).isEmpty() && !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private boolean passable(BlockPos p) {
        ServerLevel level = self.serverLevel();
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty();
    }

    /** Length of the gap straight ahead (0 = none, -1 = no land within 16 blocks). */
    public int gapAhead(Direction d) {
        BlockPos feet = self.blockPosition();
        int start = solid(feet.below()) ? 1 : 0; // crouching over the edge: the gap already starts under us
        for (int i = start; i <= 16; i++) {
            BlockPos c = feet.relative(d, i);
            if (i > 0 && (!passable(c) || !passable(c.above()))) {
                return i == 1 ? 0 : -1; // a wall: not a gap problem
            }
            if (solid(c.below())) {
                return i - start;
            }
        }
        return -1;
    }

    /** A block worth less than the trip: unneeded ones first, then ordinary building blocks (not wood). */
    private int bridgeBlockSlot() {
        Inventory inv = self.getInventory();
        for (int i : Storage.unneededSlots(self)) {
            if (Equipment.isBuildingBlock(self, inv.items.get(i))) {
                return i;
            }
        }
        int fallback = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (Equipment.isBuildingBlock(self, s)) {
                if (!s.is(ItemTags.LOGS) && !s.is(ItemTags.PLANKS)) {
                    return i;
                }
                fallback = i;
            }
        }
        return fallback;
    }

    private int bridgeBlocks() {
        int n = 0;
        for (ItemStack s : self.getInventory().items) {
            if (Equipment.isBuildingBlock(self, s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Called every tick while walking somewhere: returns true when it took over the movement. */
    /** Set by the controller each tick: monsters close by (no time to dig a tunnel). */
    public boolean threatened;
    public String guardDebug = "";

    public boolean tick(long now) {
        if (bridging) {
            return bridgeTick();
        }
        if (parkour) {
            return parkourTick();
        }
        if (tunnel != null) {
            return tunnelTick();
        }
        if (stairsDir != null) {
            return stairsTick();
        }
        if (stairBridge != null) {
            return stairBridgeTick();
        }
        if (waterColumn != null) {
            return waterClimbTick();
        }
        if (dropWater != null) {
            return waterDropTick();
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        Vec3 goal = motor.recentGoal();
        if (goal != null && motor.stuckCount() >= 2 && !self.isPassenger() && Motor.horizontalDistance(self.position(), goal) > 1.5) {
            double up = goal.y - self.getY();
            if (up >= 3 && startWaterClimb(goal)) {
                return true; // a waterfall / water column up there: swim up it
            }
            if (up <= -3 && self.onGround() && startWaterDrop(goal)) {
                return true; // water below the cliff: jump into it instead of climbing down
            }
        }
        if (goal != null && motor.stuckCount() >= 1) {
            guardDebug = "goal " + BlockPos.containing(goal).toShortString() + " stuck=" + motor.stuckCount() + " ground=" + self.onGround();
        }
        if (goal == null || Motor.horizontalDistance(self.position(), goal) < 6 && Math.abs(goal.y - self.getY()) < 3 || motor.stuckCount() < 2
                || !self.onGround() || self.isInWater() || self.isPassenger()) {
            return false;
        }
        Direction d = Direction.getNearest(goal.x - self.getX(), 0, goal.z - self.getZ());
        int g = gapAhead(d);
        debug = "gap=" + g + " dir=" + d;
        double rise = goal.y - self.getY();
        if (g == 0) {
            if (threatened) {
                return false;
            }
            // a cliff / slope between us and a goal up (or down) there: a staircase, not a level tunnel
            if (rise >= 2 && startStairs(d, 1, goal)) {
                return true;
            }
            if (rise <= -2 && startStairs(d, -1, goal)) {
                return true;
            }
            return startTunnel(d);
        }
        if (rise <= -3 && Motor.horizontalDistance(self.position(), goal) < 3 && !threatened && startStairs(d, -1, goal)) {
            return true; // right above it, down in the ground: dig our way down to it
        }
        if (g < 0 && Config.get(Config.ALLOW_BLOCK_PLACING, true) && startStairBridge(d, goal)) {
            return true; // ground on the far side at another height: a bridge that climbs / descends to it
        }
        boolean blocks = Config.get(Config.ALLOW_BLOCK_PLACING, true) && bridgeBlocks() >= g;
        boolean lava = g > 0 && lavaBelow(d, g);
        if (g > 0 && g < Brain.PARKOUR_GAPS && (!blocks || g == 1) && brain() != null
                && (!lava || Math.max(brain().parkourValue(g, 0), brain().parkourValue(g, 1)) > 0.5f && !consumables.hasPearl())) {
            // jump it: how (walking up / with a sprinting run-up) is learned from what worked before
            // (over lava only with a jump we know works and no pearl to throw instead)
            how = brain().chooseParkour(g, self.getRandom());
            if (lava) {
                how = brain().parkourValue(g, 1) >= brain().parkourValue(g, 0) ? 1 : 0;
            }
            parkour = true;
            dir = d;
            gap = g;
            ticks = 0;
            stage = how == 1 ? 0 : 1;
            edge = edgeBlock(d);
            startY = self.getY();
            parkourJumps++;
            return parkourTick();
        }
        if (g > 0 && blocks) {
            bridging = true;
            dir = d;
            gap = g;
            placed = 0;
            ticks = 0;
            steps = 0;
            lastFeet = self.blockPosition();
            bridges++;
            return bridgeTick();
        }
        cooldown = 40;
        return consumables.pearlToward(goal);
    }

    // ------------------------------------------------------------------ water: swimming up falls, diving off cliffs

    private BlockPos waterColumn;
    private int waterTop;
    private int waterTicks;
    private double waterStartY;
    private Vec3 waterGoal;
    public int waterClimbs;
    public String waterDebug = "";

    private boolean water(BlockPos p) {
        return self.level().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER);
    }

    /** A column of water (falling water, a waterfall) nearby that reaches up to about the goal's height. */
    private boolean startWaterClimb(Vec3 goal) {
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        int bestTop = 0;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-8, -1, -8), feet.offset(8, 1, 8))) {
            if (!water(q) || water(q.below())) {
                continue;
            }
            BlockPos p = q.immutable();
            int top = p.getY();
            while (top - p.getY() < 48 && water(new BlockPos(p.getX(), top + 1, p.getZ()))) {
                top++;
            }
            if (top - feet.getY() < 3 || top + 1.5 < goal.y) {
                continue; // not much of a climb / does not get us up there
            }
            double score = Motor.horizontalDistance(self.position(), Vec3.atBottomCenterOf(p)) + 0.5 * Vec3.atCenterOf(new BlockPos(p.getX(), top, p.getZ())).distanceTo(goal);
            if (score < bestScore) {
                bestScore = score;
                best = p;
                bestTop = top;
            }
        }
        if (best == null) {
            return false;
        }
        waterColumn = best;
        waterTop = bestTop;
        waterTicks = 0;
        waterStartY = self.getY();
        waterGoal = goal;
        waterDebug = "climb " + best.toShortString() + " to y" + bestTop;
        return waterClimbTick();
    }

    private boolean waterClimbTick() {
        BlockPos col = waterColumn;
        if (++waterTicks > 500 || !water(col)) {
            waterDebug += " gave up at " + self.blockPosition().toShortString();
            waterColumn = null;
            cooldown = 100;
            return false;
        }
        boolean inColumn = self.getBlockX() == col.getX() && self.getBlockZ() == col.getZ();
        if (self.getY() >= waterTop - 0.8 || (!inColumn && self.getY() > col.getY() + 1.5)) {
            // up at the top: out onto the land beside it, towards the goal
            motor.moveToward(waterGoal);
            motor.jump();
            if (self.onGround() && !self.isInWater()) {
                if (self.getY() - waterStartY >= 2.5) {
                    waterClimbs++;
                }
                waterDebug += " out at " + self.blockPosition().toShortString();
                waterColumn = null;
                motor.resetStuck();
            }
            return true;
        }
        Vec3 center = new Vec3(col.getX() + 0.5, self.getY(), col.getZ() + 0.5);
        if (!inColumn) {
            motor.moveToward(center); // into the bottom of the fall
            if (self.horizontalCollision && self.onGround()) {
                motor.jump();
            }
            return true;
        }
        if (Motor.horizontalDistance(self.position(), center) > 0.15) {
            motor.moveToward(center);
        }
        motor.jump(); // swim up against the falling water
        return true;
    }

    private BlockPos dropWater;
    private int dropTicks;
    private double dropStartY;
    public int waterDrops;

    /** Standing high above the goal: water down below, close enough to jump into, breaks the fall. */
    private boolean startWaterDrop(Vec3 goal) {
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-4, -32, -4), feet.offset(4, -3, 4))) {
            if (!water(q) || water(q.above())) {
                continue; // the surface of the water
            }
            double hd = Motor.horizontalDistance(self.position(), Vec3.atBottomCenterOf(q));
            if (hd > 4.2) {
                continue;
            }
            boolean clear = true;
            for (int y = q.getY() + 1; y <= feet.getY() + 1 && clear; y++) {
                clear = passable(new BlockPos(q.getX(), y, q.getZ()));
            }
            if (!clear) {
                continue;
            }
            double score = hd + 0.3 * Vec3.atCenterOf(q).distanceTo(goal);
            if (score < bestScore) {
                bestScore = score;
                best = q.immutable();
            }
        }
        if (best == null) {
            return false;
        }
        dropWater = best;
        dropTicks = 0;
        dropStartY = self.getY();
        waterDebug = "drop into " + best.toShortString() + " from " + feet.toShortString();
        return waterDropTick();
    }

    private boolean waterDropTick() {
        BlockPos w = dropWater;
        if (++dropTicks > 200) {
            dropWater = null;
            cooldown = 60;
            return false;
        }
        if (self.getY() < dropStartY - 2 && (self.isInWater() || self.onGround())) {
            if (self.isInWater()) {
                waterDrops++;
                waterDebug += " splash";
            }
            dropWater = null;
            motor.resetStuck();
            return false;
        }
        Vec3 target = new Vec3(w.getX() + 0.5, self.getY(), w.getZ() + 0.5);
        motor.dare(); // off the edge on purpose
        motor.moveToward(target);
        double hd = Motor.horizontalDistance(self.position(), target);
        if (self.onGround() && hd > 2.5) {
            Direction d = Direction.getNearest(target.x - self.getX(), 0, target.z - self.getZ());
            if (passable(self.blockPosition().relative(d).below())) {
                motor.sprint(true);
                motor.jump(); // a run and a leap out over the water
            }
        }
        return true;
    }

    private boolean bridgeTick() {
        if (++ticks > 800 || placed > gap + 2) {
            return finish();
        }
        BlockPos feet = self.blockPosition();
        if (!feet.equals(lastFeet)) {
            steps++;
            lastFeet = feet;
        }
        BlockPos next = feet.relative(dir);
        // hanging over the edge: fill the spot under us first, then the one ahead
        BlockPos under = solid(feet.below()) ? next.below() : feet.below();
        if (!passable(next) || !passable(next.above())) {
            return finish(); // walked into something: let normal walking take over
        }
        Vec3 nextCenter = Vec3.atBottomCenterOf(next);
        if (solid(under)) {
            if (steps > gap) {
                return finish(); // across
            }
            motor.moveToward(nextCenter);
            motor.sneak(true); // carefully, it is a narrow bridge
            return true;
        }
        int slot = bridgeBlockSlot();
        if (slot < 0) {
            return finish();
        }
        Equipment.select(self, slot);
        motor.stop();
        motor.sneak(true);
        if (motor.placeBlockAt(under)) {
            placed++;
            blocksBridged++;
        } else {
            // step a bit closer to the edge (crouching keeps us from falling)
            motor.moveToward(nextCenter);
            motor.sneak(true);
        }
        return true;
    }

    // ------------------------------------------------------------------ parkour

    private com.rlclones.ai.brain.Brain brain() {
        return self.getCloneBrain();
    }

    private boolean parkour;
    private int how;
    private int stage;
    private BlockPos edge;
    private double startY;
    private boolean jumped;
    private String trace = "";
    private int airTicks;
    private int takeoff;
    public String lastParkour = "";
    public int parkourJumps;
    public int parkourSuccesses;
    public int lastHow = -1;

    /** Lava (or fire) down in the gap: a missed jump is death. */
    private boolean lavaBelow(Direction d, int g) {
        BlockPos feet = self.blockPosition();
        ServerLevel level = self.serverLevel();
        for (int i = 1; i <= g + 1; i++) {
            BlockPos c = feet.relative(d, i);
            for (int down = 1; down <= 8; down++) {
                BlockPos p = c.below(down);
                if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA) || level.getBlockState(p).is(net.minecraft.tags.BlockTags.FIRE)) {
                    return true;
                }
                if (solid(p)) {
                    break;
                }
            }
        }
        return false;
    }

    /** The last block before the gap, straight ahead. */
    private BlockPos edgeBlock(Direction d) {
        BlockPos feet = self.blockPosition();
        if (!solid(feet.below()) && solid(feet.relative(d.getOpposite()).below())) {
            return feet.relative(d.getOpposite()); // crouching over the rim: the edge is the block behind us
        }
        for (int i = 0; i < 3; i++) {
            BlockPos c = feet.relative(d, i);
            if (!solid(c.relative(d).below())) {
                return c;
            }
        }
        return feet;
    }

    private boolean parkourTick() {
        if (++ticks > 200) {
            trace += " timeout stage=" + stage;
            return endParkour(false);
        }
        Vec3 edgeCenter = Vec3.atBottomCenterOf(edge);
        Vec3 fwd = new Vec3(dir.getStepX(), 0, dir.getStepZ());
        motor.dare();
        switch (stage) {
            case 0 -> {
                // back off for a run-up
                Vec3 runStart = edgeCenter.subtract(fwd.scale(3));
                motor.lookAngles(dir.toYRot(), 0f);
                if (Motor.horizontalDistance(self.position(), runStart) < 0.4 || ticks > 60) {
                    stage = 1;
                } else {
                    motor.moveDirection(runStart.subtract(self.position()));
                    motor.lookAngles(dir.toYRot(), 0f);
                }
            }
            case 1 -> {
                // run at the edge and take off from its last bit
                motor.lookAngles(dir.toYRot(), 0f);
                motor.moveDirection(fwd);
                motor.sprint(how == 1);
                double along = self.position().subtract(edgeCenter).dot(fwd); // 0 = middle of the edge block, 0.5 = its rim
                double speed = self.getDeltaMovement().horizontalDistance();
                if (self.onGround() && along >= 0.45 - 1.2 * speed) {
                    motor.jump();
                    jumped = true;
                    stage = 2;
                    airTicks = 0;
                    takeoff = ticks;
                    trace = "how=" + how + " off=" + String.format("%.2f", along) + " v=" + String.format("%.2f", speed) + " sprint=" + self.isSprinting();
                } else if (along > 0.7) {
                    trace = "nojump along=" + String.format("%.2f", along) + " ground=" + self.onGround() + " v=" + String.format("%.2f", speed)
                            + " sneak=" + self.isShiftKeyDown() + " sprint=" + self.isSprinting();
                    stage = 2; // off the edge without a jump
                    airTicks = 0;
                    takeoff = ticks;
                }
            }
            default -> {
                motor.lookAngles(dir.toYRot(), 0f);
                motor.moveDirection(fwd);
                motor.sprint(how == 1);
                if (!self.onGround()) {
                    airTicks++;
                }
                if (self.onGround() && (airTicks > 2 || ticks - takeoff > 25)) {
                    double along = self.position().subtract(edgeCenter).dot(fwd);
                    boolean across = along > gap + 0.2 && self.getY() >= startY - 0.5;
                    trace += " land=" + String.format("%.2f", along) + " dy=" + String.format("%.2f", self.getY() - startY);
                    return endParkour(across);
                }
                if (self.getY() < startY - 2.5) {
                    return endParkour(false); // falling into it
                }
            }
        }
        return true;
    }

    private boolean endParkour(boolean success) {
        if (brain() != null && (jumped || !success)) {
            brain().learnParkour(gap, how, success ? 1f : -1f);
        }
        if (success) {
            parkourSuccesses++;
        }
        lastHow = how;
        debug = "parkour gap=" + gap + " how=" + how + " ok=" + success + " [" + trace + "]";
        lastParkour = debug;
        trace = "";
        parkour = false;
        jumped = false;
        return finish();
    }

    // ------------------------------------------------------------------ tunnelling

    private java.util.List<BlockPos> tunnel;
    public int blocksTunneled;

    /** Can this block go to make way? Not a container / base, not holding back water or lava, not too hard. */
    private boolean diggable(BlockPos p) {
        ServerLevel level = self.serverLevel();
        BlockState st = level.getBlockState(p);
        if (st.isAir() || st.getCollisionShape(level, p).isEmpty()) {
            return true;
        }
        float hardness = st.getDestroySpeed(level, p);
        if (hardness < 0 || hardness >= 50 || st.hasBlockEntity() || !Config.get(Config.ALLOW_BLOCK_BREAKING, true)
                || st.is(net.minecraft.tags.BlockTags.FENCES) || st.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                || st.is(net.minecraft.tags.BlockTags.DOORS) || st.is(net.minecraft.tags.BlockTags.BEDS)) {
            return false; // somebody built that
        }
        for (Direction f : Direction.values()) {
            if (f != Direction.DOWN && !level.getFluidState(p.relative(f)).isEmpty()) {
                return false;
            }
        }
        if (com.rlclones.clone.Bases.get(self.getServer()).nearest(level.dimension(), Vec3.atCenterOf(p), 12) != null) {
            return false; // never through somebody's base
        }
        return true;
    }

    /** Stuck at a wall on the way somewhere far: dig a 2-high hole through it. */
    private boolean startTunnel(Direction d) {
        BlockPos feet = self.blockPosition();
        BlockPos low = feet.relative(d);
        BlockPos high = low.above();
        boolean step = solid(low) && !solid(high) && !solid(feet.above(2)); // a step we can jump up
        if (step || (!solid(low) && !solid(high))) {
            return false;
        }
        if (!diggable(low) || !diggable(high)) {
            return false;
        }
        tunnel = new java.util.ArrayList<>();
        if (solid(high)) {
            tunnel.add(high);
        }
        if (solid(low)) {
            tunnel.add(low);
        }
        ticks = 0;
        return tunnelTick();
    }

    private boolean tunnelTick() {
        if (tunnel.isEmpty() || ++ticks > 400) {
            tunnel = null;
            return finish();
        }
        BlockPos next = tunnel.get(0);
        if (!solid(next)) {
            tunnel.remove(0);
            return true;
        }
        motor.stop();
        Equipment.select(self, Equipment.bestToolSlot(self, self.level().getBlockState(next)));
        if (motor.mine(next)) {
            tunnel.remove(0);
            blocksTunneled++;
        }
        return true;
    }

    private boolean finish() {
        bridging = false;
        cooldown = 20;
        motor.resetStuck();
        return false;
    }

    // ------------------------------------------------------------------ stairs dug through the ground

    @javax.annotation.Nullable
    private Direction stairsDir;
    private int stairsSign;
    private int stairsLeft;
    private int airTicksStairs;
    @javax.annotation.Nullable
    private BlockPos stairsNext;
    private BlockPos stairsFrom;
    public int stairSteps;
    public String stairsDebug = "";

    /** Start digging a staircase up ({@code sign} 1) or down (-1) in direction {@code d}, as many steps as the goal is high. */
    private boolean startStairs(Direction d, int sign, Vec3 goal) {
        if (!Config.get(Config.ALLOW_BLOCK_BREAKING, true)) {
            return false;
        }
        BlockPos feet = self.blockPosition();
        BlockPos ahead = feet.relative(d);
        if (sign > 0 && !(solid(ahead) && diggable(ahead.above()) && diggable(ahead.above(2)) && diggable(feet.above(2)))) {
            return false;
        }
        if (sign < 0 && !(solid(ahead.below(2)) && diggable(ahead.above()) && diggable(ahead) && diggable(ahead.below()))) {
            return false;
        }
        stairsDir = d;
        stairsSign = sign;
        stairsLeft = Math.max(1, (int) Math.ceil(Math.abs(goal.y - self.getY())) + 1);
        stairsFrom = feet;
        ticks = 0;
        stairsDebug = (sign > 0 ? "up " : "down ") + d + " x" + stairsLeft;
        return stairsTick();
    }

    private boolean stairsTick() {
        Direction d = stairsDir;
        if (d == null || ++ticks > 600 || stairsLeft <= 0) {
            stairsDebug += ticks > 600 ? " timeout at " + self.blockPosition().subtract(stairsFrom).toShortString() + " ground=" + self.onGround() : "";
            stairsDir = null;
            return finish();
        }
        if (!self.onGround() && ++airTicksStairs < 20) {
            if (stairsNext != null) {
                motor.moveToward(Vec3.atBottomCenterOf(stairsNext)); // keep pushing onto the step while in the air
            }
            return true; // mid-jump / dropping onto the step
        }
        airTicksStairs = 0;
        BlockPos feet = self.blockPosition();
        if (!feet.equals(stairsFrom)) {
            if (feet.getY() == stairsFrom.getY() + stairsSign) {
                stairSteps++;
                stairsLeft--;
                if (stairsLeft <= 0) {
                    stairsDir = null;
                    return finish();
                }
            } else if (Math.abs(feet.getY() - stairsFrom.getY()) > 1 || feet.distManhattan(stairsFrom) > 3) {
                stairsDebug += " moved off";
                stairsDir = null; // fell or got pushed away
                return finish();
            }
            stairsFrom = feet;
        }
        BlockPos ahead = feet.relative(d);
        if (ticks % 50 == 0 && stairsDebug.length() < 400) {
            stairsDebug += " {" + (feet.getY() - stairsFrom.getY()) + " a" + (solid(ahead) ? 1 : 0) + (solid(ahead.above()) ? 1 : 0)
                    + (solid(ahead.above(2)) ? 1 : 0) + " h" + (solid(feet.above(2)) ? 1 : 0) + " g" + (self.onGround() ? 1 : 0) + "}";
        }
        BlockPos[] clear;
        BlockPos next;
        if (stairsSign > 0) {
            clear = new BlockPos[]{feet.above(2), ahead.above(2), ahead.above()};
            next = ahead.above();
            if (!solid(ahead)) {
                stairsDebug += " top@" + stairSteps;
                stairsDir = null; // nothing to step up on: the slope is behind us
                return finish();
            }
        } else {
            clear = new BlockPos[]{ahead.above(), ahead, ahead.below()};
            next = ahead.below();
            if (!solid(next.below())) {
                stairsDir = null; // a drop below: no stair to dig into
                return finish();
            }
        }
        for (BlockPos p : clear) {
            if (solid(p)) {
                if (!diggable(p)) {
                    stairsDebug += " undiggable " + self.level().getBlockState(p).getBlock();
                    stairsDir = null;
                    return finish();
                }
                motor.stop();
                Equipment.select(self, Equipment.bestToolSlot(self, self.level().getBlockState(p)));
                if (motor.mine(p)) {
                    blocksTunneled++;
                    if (stairsDebug.length() < 300) {
                        stairsDebug += " dug" + (p.getX() - feet.getX()) + "," + (p.getY() - feet.getY());
                    }
                } else if (ticks % 100 == 0 && stairsDebug.length() < 300) {
                    stairsDebug += " [" + motor.mineDebug + "]";
                }
                return true;
            }
        }
        if (ticks % 100 == 0 && stairsDebug.length() < 300) {
            stairsDebug += " move@" + feet.toShortString();
        }
        stairsNext = next;
        motor.lookAt(Vec3.atCenterOf(next));
        motor.moveToward(Vec3.atBottomCenterOf(next));
        if (stairsSign > 0) {
            motor.jump();
        }
        return true;
    }

    // ------------------------------------------------------------------ bridges that climb or come down

    /** Planned bridge: support level of each column from the start (index 0) to the landing (last). */
    @javax.annotation.Nullable
    private int[] stairBridge;
    private BlockPos bridgeStart;
    private int bridgeCol;
    public int stairBridges;
    public int stairBridgeBlocks;
    public String stairBridgeDebug = "";

    /** The far side ahead at another height: {column, support level}; null if none within 12 blocks. */
    @javax.annotation.Nullable
    private int[] landingAhead(BlockPos from, Direction d, Vec3 goal) {
        int l0 = from.getY() - 1;
        boolean gapSeen = false;
        for (int i = 1; i <= 12; i++) {
            BlockPos c = from.relative(d, i);
            boolean ground = solid(c.atY(l0)) && passable(c.atY(l0 + 1)) && passable(c.atY(l0 + 2));
            if (!gapSeen) {
                if (ground) {
                    continue; // still our own ground
                }
                if (solid(c.atY(l0 + 1)) || solid(c.atY(l0 + 2))) {
                    return null; // a wall right ahead: digging, not bridging
                }
                gapSeen = true;
            }
            if (ground && i > 1) {
                return null; // ground at our own level: an ordinary bridge does
            }
            // higher: the lowest top with room to stand
            if (solid(c.atY(l0 + 1)) || solid(c.atY(l0 + 2))) {
                for (int up = 1; up <= 6; up++) {
                    if (solid(c.atY(l0 + up)) && passable(c.atY(l0 + up + 1)) && passable(c.atY(l0 + up + 2))) {
                        return new int[]{i, l0 + up};
                    }
                }
                return null;
            }
            // lower: ground not below the goal's level (not just the bottom of a pit)
            for (int down = 1; down <= 6; down++) {
                int y = l0 - down;
                if (solid(c.atY(y)) && passable(c.atY(y + 1)) && passable(c.atY(y + 2))) {
                    if (y + 1 >= goal.y - 2) {
                        return new int[]{i, y};
                    }
                    break;
                }
            }
        }
        return null;
    }

    private boolean startStairBridge(Direction d, Vec3 goal) {
        BlockPos from = self.blockPosition();
        if (!solid(from.below())) {
            from = from.relative(d.getOpposite()); // crouching over the rim
            if (!solid(from.below())) {
                return false;
            }
        }
        int[] land = landingAhead(from, d, goal);
        if (land == null) {
            return false;
        }
        int cols = land[0];
        int[] level = new int[cols + 1];
        level[0] = from.getY() - 1;
        for (int k = 1; k < cols; k++) {
            level[k] = level[k - 1] + Mth.clamp(land[1] - level[k - 1], -1, 1);
        }
        level[cols] = land[1];
        if (land[1] - level[cols - 1] > 1) {
            return false; // too steep to climb in the room there is
        }
        int need = 0;
        for (int k = 1; k < cols; k++) {
            need += level[k] == level[k - 1] ? 1 : 2;
        }
        if (bridgeBlocks() < need) {
            return false;
        }
        stairBridge = level;
        bridgeStart = from;
        bridgeCol = 0;
        dir = d;
        ticks = 0;
        stairBridges++;
        stairBridgeDebug = "cols=" + cols + " from " + level[0] + " to " + land[1] + " blocks=" + need;
        return stairBridgeTick();
    }

    private boolean stairBridgeTick() {
        int[] level = stairBridge;
        if (level == null || ++ticks > 1200) {
            stairBridgeDebug += " timeout at " + bridgeCol;
            stairBridge = null;
            return finish();
        }
        int k = bridgeCol;
        BlockPos col = bridgeStart.relative(dir, k);
        BlockPos stand = col.atY(level[k] + 1);
        BlockPos feet = self.blockPosition();
        if (self.getY() < level[Math.max(0, k - 1)] - 0.5 && self.onGround()) {
            stairBridge = null; // fell off
            stairBridgeDebug += " fell at " + k;
            return finish();
        }
        if (!(feet.getX() == stand.getX() && feet.getZ() == stand.getZ()) || Math.abs(feet.getY() - stand.getY()) > 0) {
            // get onto column k (one step forward: up, level or down)
            motor.moveToward(Vec3.atBottomCenterOf(stand));
            boolean levelStep = level[k] == level[Math.max(0, k - 1)];
            motor.sneak(levelStep);
            if (!levelStep) {
                motor.dare(); // a step up or down onto the block we just put there: no edge-crouching
            }
            if (stand.getY() > feet.getY() && self.onGround()) {
                motor.jump();
            }
            return true;
        }
        if (k + 1 >= level.length) {
            stairBridge = null; // on the far side
            return finish();
        }
        BlockPos nextCol = bridgeStart.relative(dir, k + 1);
        java.util.List<BlockPos> needed = new java.util.ArrayList<>();
        if (k + 1 < level.length - 1) {
            if (level[k + 1] > level[k]) {
                needed.add(nextCol.atY(level[k]));          // the block under the new step
            } else if (level[k + 1] < level[k]) {
                needed.add(col.atY(level[k] - 1));          // under our own step, to build the lower one against
            }
            needed.add(nextCol.atY(level[k + 1]));
        }
        for (BlockPos p : needed) {
            if (!solid(p) && self.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))) {
                // hanging over the rim: back onto the middle of our column before building the step ahead
                motor.moveToward(Vec3.atBottomCenterOf(stand));
                motor.sneak(true);
                return true;
            }
        }
        for (BlockPos p : needed) {
            if (!solid(p)) {
                int slot = bridgeBlockSlot();
                if (slot < 0) {
                    stairBridge = null;
                    return finish();
                }
                Equipment.select(self, slot);
                motor.stop();
                motor.sneak(true);
                if (motor.placeBlockAt(p)) {
                    stairBridgeBlocks++;
                    blocksBridged++;
                    if (stairBridgeDebug.length() < 300) {
                        stairBridgeDebug += " +" + (p.getX() - bridgeStart.getX()) + "," + (p.getY() - bridgeStart.getY());
                    }
                }
                return true;
            }
        }
        bridgeCol = k + 1;
        return true;
    }
}
