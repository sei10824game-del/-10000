package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import com.rlclones.ai.brain.Brain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
        return bridging || parkour || tunnel != null;
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
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        Vec3 goal = motor.recentGoal();
        if (goal == null || Motor.horizontalDistance(self.position(), goal) < 6 || motor.stuckCount() < 2 || !self.onGround()
                || self.isInWater() || self.isPassenger()) {
            return false;
        }
        Direction d = Direction.getNearest(goal.x - self.getX(), 0, goal.z - self.getZ());
        int g = gapAhead(d);
        debug = "gap=" + g + " dir=" + d;
        if (g == 0) {
            return !threatened && startTunnel(d);
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
}
