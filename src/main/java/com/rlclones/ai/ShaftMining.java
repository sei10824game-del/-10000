package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The other way down for ore: a shaft dug straight down. Ladders first (crafted from sticks), then one block after the
 * other right under the feet, a ladder put on the wall at every level so the way back up is always there - until the
 * ladders run out. Then back up the ladders. Shafts are recorded with the bases: a clone that comes across one nobody
 * is digging climbs down it and carries on from its bottom.
 */
public final class ShaftMining {
    public enum Status {WORKING, DONE, FAILED}

    /** Ladders made before going down (one craft gives three). */
    public static final int WANT_LADDERS = 3;

    private final ClonePlayer self;
    private final Motor motor;
    private final Crafting crafting;
    @Nullable
    private Bases.Shaft shaft;
    private int stage;
    private int ticks;
    private int stuck;
    private int craftTries;
    private int lastLadders;
    private BlockPos lastFeet = BlockPos.ZERO;

    /** Dig down to here at most; unset (MIN_VALUE) = what the next step needs (iron: 16, diamonds: -58), see {@link Progression}. */
    public int targetY = Integer.MIN_VALUE;
    /** Tests: treat the arena (underground) as the surface. */
    public boolean assumeSurface;
    public int laddersCrafted;
    public int laddersPlaced;
    public int levelsDug;
    public int resumed;
    public int climbedOut;
    public String debug = "";
    public String trace = "";

    public ShaftMining(ClonePlayer self, Motor motor, Crafting crafting) {
        this.self = self;
        this.motor = motor;
        this.crafting = crafting;
    }

    private void trace(String what) {
        if (trace.length() < 500) {
            trace += " " + what;
        }
    }

    private Bases bases() {
        return Bases.get(self.getServer());
    }

    private String me() {
        return self.getGameProfile().getName();
    }

    private int ladders() {
        return self.getInventory().countItem(Items.LADDER);
    }

    private boolean craftable(Item it) {
        for (CraftingRecipe r : Crafting.recipesFor(self, s -> s.is(it))) {
            if (Crafting.canCraft(self, r)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPickaxe() {
        for (ItemStack s : self.getInventory().items) {
            if (s.getItem() instanceof PickaxeItem) {
                return true;
            }
        }
        return false;
    }

    /** How deep to dig now. */
    public int target() {
        return targetY != Integer.MIN_VALUE ? targetY : Progression.digTargetY(Progression.need(self));
    }

    /** A pickaxe, ladders (or what they are made of), no iron / diamond gear yet, up on the surface or near a shaft to carry on. */
    public boolean wanted() {
        if (!Config.get(Config.ALLOW_BLOCK_BREAKING, true) || !hasPickaxe() || StairMining.ironGeared(self) && !Progression.digWanted(self) || self.isInWater()
                || self.getBlockY() <= target()) {
            return false;
        }
        if (ladders() == 0 && !craftable(Items.LADDER) && !craftable(Items.STICK)) {
            return false;
        }
        boolean surface = assumeSurface || self.level().canSeeSky(self.blockPosition().above());
        return surface || bases().nearestShaft(self.level().dimension(), self.position(), 64, me(), self.level().getGameTime()) != null;
    }

    public void begin() {
        shaft = bases().nearestShaft(self.level().dimension(), self.position(), 64, me(), self.level().getGameTime());
        if (shaft != null) {
            resumed++;
            debug = "resume " + shaft.top.toShortString() + " depth " + shaft.depth();
            trace("resume@" + shaft.top.toShortString());
        }
        stage = 0;
        ticks = 0;
        stuck = 0;
        craftTries = 0;
        lastLadders = ladders();
        lastFeet = self.blockPosition();
        motor.resetStuck();
    }

    private boolean inColumn(BlockPos feet) {
        return shaft != null && feet.getX() == shaft.top.getX() && feet.getZ() == shaft.top.getZ();
    }

    private boolean solid(BlockPos p) {
        ServerLevel level = self.serverLevel();
        return level.getFluidState(p).isEmpty() && !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private boolean ladderAt(BlockPos p) {
        return self.level().getBlockState(p).getBlock() instanceof LadderBlock;
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
            if (f != Direction.UP && !level.getFluidState(p.relative(f)).isEmpty()) {
                return false; // water or lava behind it
            }
        }
        return bases().nearest(level.dimension(), Vec3.atCenterOf(p), 8) == null;
    }

    public Status tick() {
        if (++ticks > 4000) {
            return Status.DONE;
        }
        BlockPos feet = self.blockPosition();
        if (feet.equals(lastFeet)) {
            stuck++;
        } else {
            stuck = 0;
            lastFeet = feet;
        }
        if (ticks % 100 == 1) {
            trace("s" + stage + "@" + feet.toShortString() + " l" + ladders());
        }
        return switch (stage) {
            case 0 -> craftTick();
            case 1 -> toRimTick(feet);
            case 2 -> downTick(feet);
            case 3 -> digTick(feet);
            default -> climbTick(feet);
        };
    }

    /** Ladders first: sticks if need be, then ladders (through the crafting table like anyone). */
    private Status craftTick() {
        int have = ladders();
        if (have > lastLadders) {
            laddersCrafted += have - lastLadders; // (whichever tick the crafting finished on)
            trace("crafted" + have);
        }
        lastLadders = have;
        if (have >= WANT_LADDERS || have > 0 && craftTries > 2) {
            crafting.forcedTarget = null;
            crafting.reset();
            stage = shaft != null ? 1 : 3;
            return Status.WORKING;
        }
        Item target = craftable(Items.LADDER) ? Items.LADDER : craftable(Items.STICK) ? Items.STICK : null;
        if (target == null) {
            crafting.forcedTarget = null;
            if (have > 0) {
                craftTries = 99;
                return Status.WORKING;
            }
            debug = "nothing to make ladders from";
            return Status.FAILED;
        }
        crafting.forcedTarget = target;
        if (crafting.tick() == Crafting.Status.DONE) {
            int now = ladders();
            if (now <= have && target == Items.LADDER) {
                craftTries++;
                if (craftTries > 4 && now == 0) {
                    crafting.forcedTarget = null;
                    debug = "could not craft ladders";
                    return Status.FAILED;
                }
            }
        }
        return Status.WORKING;
    }

    /** To the rim of a shaft someone started, and in. */
    private Status toRimTick(BlockPos feet) {
        Bases.Shaft s = shaft;
        if (s == null) {
            stage = 3;
            return Status.WORKING;
        }
        if (inColumn(feet)) {
            stage = 2;
            return Status.WORKING;
        }
        Vec3 top = Vec3.atBottomCenterOf(s.top);
        if (Motor.horizontalDistance(self.position(), top) < 1.6) {
            motor.dare(); // step into the hole on purpose (the ladders break the fall)
            motor.moveToward(top);
        } else {
            motor.navigate(top, 1.2, false);
        }
        if (motor.stuckCount() > 8 || stuck > 200) {
            debug = "cannot reach the shaft at " + s.top.toShortString();
            return Status.FAILED;
        }
        return Status.WORKING;
    }

    /** Down the ladders to where the digging stopped. */
    private Status downTick(BlockPos feet) {
        Bases.Shaft s = shaft;
        if (s == null || !inColumn(feet)) {
            stage = 1;
            return Status.WORKING;
        }
        motor.stop();
        Vec3 c = new Vec3(s.top.getX() + 0.5, self.getY(), s.top.getZ() + 0.5);
        if (Motor.horizontalDistance(self.position(), c) > 0.2) {
            motor.moveToward(c);
        }
        if (self.onGround() && (feet.getY() <= s.end.getY() || stuck > 20)) {
            stage = 3;
        }
        return Status.WORKING;
    }

    @Nullable
    private Direction pickWall(BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (solid(feet.below().relative(d)) && solid(feet.below(2).relative(d))) {
                return d;
            }
        }
        return null;
    }

    private Status digTick(BlockPos feet) {
        if (shaft == null) {
            Direction wall = pickWall(feet);
            if (wall == null || !diggable(feet.below())) {
                debug = "no place for a shaft at " + feet.toShortString();
                return Status.FAILED;
            }
            shaft = bases().addShaft(self.level().dimension(), feet, wall);
            debug = "new shaft " + feet.toShortString() + " ladders on " + wall;
            trace("new@" + feet.toShortString() + wall);
        }
        Bases.Shaft s = shaft;
        s.digger = me();
        s.lastDug = self.level().getGameTime();
        if (!inColumn(feet)) {
            if (feet.getY() < s.top.getY() || stuck > 100) {
                debug = "lost the shaft at " + feet.toShortString();
                stage = 4;
                return Status.WORKING;
            }
            motor.navigate(Vec3.atBottomCenterOf(s.top), 0.3, false);
            return Status.WORKING;
        }
        if (!self.onGround()) {
            return Status.WORKING; // still sliding down
        }
        if (feet.getY() < s.end.getY()) {
            s.end = feet.immutable();
            levelsDug++;
            bases().setDirty();
        }
        Vec3 c = new Vec3(s.top.getX() + 0.5, self.getY(), s.top.getZ() + 0.5);
        if (Motor.horizontalDistance(self.position(), c) > 0.15) {
            motor.moveToward(c); // dead centre: the ladder goes beside us, the hole right under us
            motor.sneak(true); // small careful steps
            return Status.WORKING;
        }
        motor.stop();
        if (feet.getY() < s.top.getY() && !ladderAt(feet)) {
            if (ladders() == 0) {
                debug = "out of ladders at " + feet.toShortString();
                stage = 4;
                return Status.WORKING;
            }
            BlockPos wallBlock = feet.relative(s.wall);
            if (!solid(wallBlock)) {
                int slot = Equipment.pillarBlockSlot(self);
                if (slot < 0) {
                    debug = "no wall for the ladder at " + feet.toShortString();
                    stage = 4;
                    return Status.WORKING;
                }
                Equipment.select(self, slot);
                motor.placeBlockAt(wallBlock);
                return Status.WORKING;
            }
            Equipment.select(self, slotOf(Items.LADDER));
            motor.useOnFace(wallBlock, s.wall.getOpposite());
            if (ladderAt(feet)) {
                laddersPlaced++;
            } else if (stuck > 60) {
                debug = "the ladder will not stay at " + feet.toShortString();
                stage = 4;
            }
            return Status.WORKING;
        }
        if (ladders() == 0) {
            debug = "out of ladders at " + feet.toShortString();
            stage = 4;
            return Status.WORKING;
        }
        BlockPos below = feet.below();
        if (feet.getY() <= target() || !diggable(below) || !solid(below.below()) && !self.level().getBlockState(below.below()).isAir()
                || self.level().getBlockState(below.below()).isAir() && !solid(below.below(2))) {
            s.finished = true; // bedrock, liquid, a cave under us: this shaft ends here
            bases().setDirty();
            debug = "bottom at " + feet.toShortString() + " (" + self.level().getBlockState(below) + ")";
            stage = 4;
            return Status.WORKING;
        }
        self.setXRot(90f);
        Equipment.select(self, Equipment.bestToolSlot(self, self.level().getBlockState(below)));
        motor.mine(below);
        if (stuck > 400) {
            debug = "cannot dig " + below.toShortString();
            stage = 4;
        }
        return Status.WORKING;
    }

    private int slotOf(Item it) {
        var inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(it)) {
                return i;
            }
        }
        return -1;
    }

    /** Back up the ladders and out onto the rim. */
    private Status climbTick(BlockPos feet) {
        Bases.Shaft s = shaft;
        if (s == null) {
            return Status.DONE;
        }
        s.digger = "";
        if (!inColumn(feet) && feet.getY() >= s.top.getY()) {
            climbedOut++;
            return Status.DONE;
        }
        if (stuck > 160) {
            debug += " | stuck climbing at " + feet.toShortString();
            return Status.DONE; // the escape reflex takes it from here
        }
        Direction out = s.wall;
        if (solid(s.top.relative(s.wall)) || !solid(s.top.relative(s.wall).below())) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos rim = s.top.relative(d);
                if (!solid(rim) && solid(rim.below())) {
                    out = d; // open ground at the rim on this side
                    break;
                }
            }
        }
        motor.moveToward(Vec3.atBottomCenterOf(s.top.relative(out))); // into the ladder wall: up we go
        motor.jump();
        return Status.WORKING;
    }

    public boolean busy() {
        return shaft != null && stage >= 2 && stage <= 3;
    }

    @Nullable
    public Bases.Shaft shaft() {
        return shaft;
    }
}
