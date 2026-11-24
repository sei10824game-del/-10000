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
    @Nullable private BlockPos repairCell;
    private int repairTries;
    private boolean repairingReturn;
    private int returnBottom, lastRoutePlacement = -1;
    public int supportsPlaced;
    private static final int[] REPAIR_OFFSETS = {0, 1, 2, -1};

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
        if (!Config.get(Config.ALLOW_BLOCK_BREAKING, true) || !Config.get(Config.ALLOW_BLOCK_PLACING, true) || !hasPickaxe() || StairMining.ironGeared(self) && !Progression.digWanted(self) || self.isInWater()
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
            shaft.digger = me();
            shaft.lastDug = self.level().getGameTime();
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
        repairCell = null; repairTries = 0; repairingReturn = false; lastRoutePlacement = -1;
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
        BlockState state = self.level().getBlockState(p);
        return state.getBlock() instanceof LadderBlock && state.canSurvive(self.level(), p)
                && state.getFluidState().isEmpty();
    }

    private enum Repair { READY, WORKING, BLOCKED }

    private boolean inReach(BlockPos wall) {
        Vec3 hit = Vec3.atCenterOf(wall).add(-shaft.wall.getStepX() * 0.5, 0, -shaft.wall.getStepZ() * 0.5);
        return self.getEyePosition().distanceTo(hit) <= Motor.BLOCK_REACH;
    }

    private boolean supported(BlockPos cell) {
        BlockPos wall = cell.relative(shaft.wall);
        return self.level().getBlockState(wall).isFaceSturdy(self.level(), wall, shaft.wall.getOpposite());
    }

    /** Build a genuine attachment face while the old floor still supports the digger. */
    private Repair support(BlockPos cell) {
        if (supported(cell)) return Repair.READY;
        BlockPos wall = cell.relative(shaft.wall);
        if (!Config.get(Config.ALLOW_BLOCK_PLACING, true) || !inReach(wall)
                || !self.level().getFluidState(wall).isEmpty()) return Repair.BLOCKED;
        int slot = Equipment.pillarBlockSlot(self);
        if (slot < 0) return Repair.BLOCKED;
        Equipment.select(self, slot);
        if (motor.placeBlockAt(wall) && supported(cell)) {
            supportsPlaced++;
            return Repair.READY;
        }
        return Repair.WORKING;
    }

    /** One rung at a time, with the real inventory and placement interaction. */
    private Repair rung(BlockPos cell) {
        if (ladderAt(cell)) return Repair.READY;
        motor.stop();
        motor.sneak(true); // hold the last sound rung even when materials/permissions prevent the next one
        if (!cell.equals(repairCell)) { repairCell = cell.immutable(); repairTries = 0; }
        if (!Config.get(Config.ALLOW_BLOCK_PLACING, true) || ladders() == 0
                || !inReach(cell.relative(shaft.wall)) || !self.level().getBlockState(cell).canBeReplaced()
                || !self.level().getFluidState(cell).isEmpty()) return Repair.BLOCKED;
        if (++repairTries > 80) return Repair.BLOCKED;
        motor.stop();
        motor.sneak(true);
        Repair backing = support(cell);
        if (backing != Repair.READY) return backing;
        Equipment.select(self, slotOf(Items.LADDER));
        motor.useOnFace(cell.relative(shaft.wall), shaft.wall.getOpposite());
        if (!ladderAt(cell)) return Repair.WORKING;
        laddersPlaced++;
        repairTries = 0;
        return Repair.READY;
    }

    /** Current rung first (including while falling), then nearby return rungs and the next known rung below. */
    private Repair repairNearby(BlockPos feet) {
        if (shaft == null || !inColumn(feet)) return Repair.READY;
        int lowest = Math.min(shaft.end.getY(), feet.getY());
        for (int offset : REPAIR_OFFSETS) {
            BlockPos cell = feet.offset(0, offset, 0);
            if (cell.getY() < lowest || cell.getY() >= shaft.top.getY() || ladderAt(cell)) continue;
            if (offset < 0 && (stage == 4 || !self.level().getBlockState(cell).canBeReplaced())) continue;
            if (!inReach(cell.relative(shaft.wall))) continue;
            Repair result = rung(cell);
            if (result != Repair.READY) {
                debug = "repair ladder " + cell.toShortString() + " " + result;
                return result;
            }
        }
        return Repair.READY;
    }

    private boolean returnPathIntact(int bottom) {
        for (int y = bottom; y < shaft.top.getY(); y++)
            if (!ladderAt(new BlockPos(shaft.top.getX(), y, shaft.top.getZ()))) return false;
        return true;
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
        if (shaft != null) {
            long now = self.level().getGameTime();
            if (!shaft.digger.isEmpty() && !shaft.digger.equals(me()) && now - shaft.lastDug < 200) {
                debug = "shaft reserved by " + shaft.digger;
                return Status.FAILED;
            }
            shaft.digger = me(); shaft.lastDug = now;
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
        Repair repair = repairNearby(feet);
        if (repair != Repair.READY) {
            if (repair == Repair.BLOCKED) stage = 4;
            return Status.WORKING;
        }
        motor.stop();
        motor.dare(); // the checked ladder column is an intentional descent, not an accidental edge
        Vec3 c = new Vec3(s.top.getX() + 0.5, self.getY(), s.top.getZ() + 0.5);
        if (Motor.horizontalDistance(self.position(), c) > 0.05) {
            motor.movePrecisely(c);
        }
        motor.sneak(false);
        if (self.onGround() && (feet.getY() <= s.end.getY() || stuck > 20)) {
            returnBottom = Math.max(feet.getY(), s.end.getY());
            if (returnPathIntact(returnBottom)) stage = 3;
            else { repairingReturn = true; stage = 4; debug = "repair return route before digging"; }
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
            if (bases().stairsNear(self.level().dimension(), feet)) {
                debug = "a staircase runs by " + feet.toShortString() + ": no shaft here";
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
        Repair repair = repairNearby(feet);
        if (repair != Repair.READY) {
            if (repair == Repair.BLOCKED) stage = 4;
            return Status.WORKING;
        }
        BlockPos existingBelow = feet.below();
        if (feet.getY() > target() && (ladderAt(existingBelow) || self.level().getBlockState(existingBelow).isAir())) {
            Repair next = rung(existingBelow);
            stage = next == Repair.BLOCKED ? 4 : next == Repair.READY ? 2 : 3;
            return Status.WORKING;
        }
        if (!self.onGround() && !(self.onClimbable() && solid(feet.below()) && self.getY() - feet.getY() < 0.15)) {
            return Status.WORKING; // still sliding down; an actual supported ladder foot is also safe
        }
        if (feet.getY() < s.end.getY()) {
            s.end = feet.immutable();
            levelsDug++;
            bases().setDirty();
        }
        Vec3 c = new Vec3(s.top.getX() + 0.5, self.getY(), s.top.getZ() + 0.5);
        if (Motor.horizontalDistance(self.position(), c) > 0.05) {
            motor.movePrecisely(c); // slow down near the centre; sneak would prevent stepping into the shaft
            return Status.WORKING;
        }
        motor.stop();
        BlockPos below = feet.below();
        if (ladders() == 0) {
            debug = "out of ladders at " + feet.toShortString();
            stage = 4;
            return Status.WORKING;
        }
        if (feet.getY() <= target() || !diggable(below) || !solid(below.below()) && !ladderAt(below.below()) && !self.level().getBlockState(below.below()).isAir()
                || self.level().getBlockState(below.below()).isAir() && !solid(below.below(2))) {
            s.finished = true; // bedrock, liquid, a cave under us: this shaft ends here
            bases().setDirty();
            debug = "bottom at " + feet.toShortString() + " target=" + target() + " diggable=" + diggable(below)
                    + " lower=" + self.level().getBlockState(below.below()) + " (" + self.level().getBlockState(below) + ")";
            stage = 4;
            return Status.WORKING;
        }
        if (!Config.get(Config.ALLOW_BLOCK_PLACING, true) || !Config.get(Config.ALLOW_BLOCK_BREAKING, true)) {
            debug = "shaft digging/placement disabled"; stage = 4; return Status.WORKING;
        }
        int drop = self.level().getBlockState(below.below()).isAir() ? 2 : 1;
        int needed = 0;
        for (int i = 0; i < drop; i++) if (!ladderAt(below.below(i))) needed++;
        if (ladders() < needed) {
            debug = "need " + needed + " ladders before opening " + below.toShortString();
            stage = 4;
            return Status.WORKING;
        }
        for (int i = 0; i < drop; i++) {
            Repair backing = support(below.below(i));
            if (backing != Repair.READY) {
                debug = "support needed before digging " + below.below(i).toShortString();
                if (backing == Repair.BLOCKED || stuck > 80) stage = 4;
                return Status.WORKING;
            }
        }
        self.setXRot(90f);
        Equipment.select(self, Equipment.bestToolSlot(self, self.level().getBlockState(below)));
        if (motor.mine(below)) {
            // Fill the intermediate level too, before the next physics step can drop past it.
            for (int i = 0; i < drop; i++) if (rung(below.below(i)) != Repair.READY) break;
        }
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
        if (repairingReturn && (ticks % 20 == 0 || lastRoutePlacement != laddersPlaced)) {
            lastRoutePlacement = laddersPlaced;
            if (returnPathIntact(returnBottom)) {
                repairingReturn = false; stage = 2; repairCell = null; stuck = 0;
                return Status.WORKING;
            }
        }
        if (!inColumn(feet) && feet.getY() >= s.top.getY()) {
            s.digger = "";
            climbedOut++;
            return Status.DONE;
        }
        if (stuck > 160) {
            debug += " | stuck climbing at " + feet.toShortString();
            return Status.DONE; // the escape reflex takes it from here
        }
        if (inColumn(feet)) {
            Repair repair = repairNearby(feet);
            if (repair != Repair.READY) return Status.WORKING;
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

    /** Also mend old/completed routes in passing; never extend their depth or travel to an unseen hole. */
    public boolean repairKnownRoute() {
        if (ladders() == 0 || !Config.get(Config.ALLOW_BLOCK_PLACING, true) || self.isInWater() || !self.isAlive()) return false;
        BlockPos feet = self.blockPosition();
        long now = self.level().getGameTime();
        for (Bases.Shaft known : bases().shafts) {
            if (known.dimension != self.level().dimension() || feet.getX() != known.top.getX() || feet.getZ() != known.top.getZ()
                    || feet.getY() < known.end.getY() || feet.getY() >= known.top.getY()
                    || !known.digger.isEmpty() && !known.digger.equals(me()) && now - known.lastDug < 200) continue;
            Bases.Shaft saved = shaft; int savedStage = stage;
            int beforeLadders = laddersPlaced, beforeSupports = supportsPlaced;
            try {
                shaft = known; stage = 2;
                Repair result = repairNearby(feet);
                return result == Repair.WORKING || beforeLadders != laddersPlaced || beforeSupports != supportsPlaced;
            } finally { shaft = saved; stage = savedStage; }
        }
        return false;
    }

    public boolean busy() {
        return shaft != null && stage >= 2 && stage <= 3;
    }

    @Nullable
    public Bases.Shaft shaft() {
        return shaft;
    }
}
