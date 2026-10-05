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

    /** Dig down to here; unset (MIN_VALUE) = what the next step needs (iron: 16, diamonds: -58), see {@link Progression}. */
    public int targetY = Integer.MIN_VALUE;
    /** Tests: treat the arena (underground) as the surface. */
    public boolean assumeSurface;
    public int stepsDug;
    public int resumed;
    public String debug = "";
    /** Recent events (diagnostics, tests). */
    public String trace = "";

    private void trace(String what) {
        if (trace.length() < 500) {
            trace += " " + what;
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
        Bases.Staircase known = bases().nearestStaircase(self.level().dimension(), self.position(), 64);
        return (surface || known != null) && self.getBlockY() > target();
    }

    public void begin() {
        trace("begin@" + self.blockPosition().toShortString());
        stairs = bases().nearestStaircase(self.level().dimension(), self.position(), 64);
        stage = stairs == null ? 2 : 0; // 0: walk to the top of a known staircase, 1: down it, 2: dig
        if (stairs != null) {
            resumed++;
            BlockPos feet = self.blockPosition();
            if (feet.distManhattan(stairs.end) <= 2) {
                stage = 2; // already down at the bottom
            } else if (feet.distManhattan(stairs.top) <= 2 || onStairs(feet)) {
                stage = 1;
            }
            debug = "resume " + stairs.top.toShortString() + " -> " + stairs.end.toShortString() + " from stage " + stage;
        }
        ticks = 0;
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
            trace("s" + stage + "@" + feet.toShortString() + (stage == 0 ? " " + motor.mineDebug.length() + "/" + motor.stuckCount() : ""));
        }
        switch (stage) {
            case 0 -> {
                Bases.Staircase s = stairs;
                if (s == null) {
                    stage = 2;
                    return Status.WORKING;
                }
                if (motor.navigate(Vec3.atBottomCenterOf(s.top), 1.0, false) || stepIndex(feet) >= 0) {
                    stage = 1;
                    motor.resetStuck();
                } else if (motor.stuckCount() > 8) {
                    debug = "cannot reach the top " + s.top.toShortString() + " from " + feet.toShortString();
                    return Status.FAILED;
                }
            }
            case 1 -> {
                // down the steps to where the digging stopped, one step after the other
                Bases.Staircase s = stairs;
                if (s == null) {
                    return Status.FAILED;
                }
                int i = stepIndex(feet);
                if (i < 0) {
                    if (feet.distManhattan(s.top) <= 3) {
                        // right by the top step: onto it
                        motor.moveToward(Vec3.atBottomCenterOf(s.top));
                        if (s.top.getY() > feet.getY() && self.onGround()) {
                            motor.jump();
                        }
                        return stuck > 80 ? Status.FAILED : Status.WORKING;
                    }
                    stage = 0; // not on the stairs (any more): to the top first
                    return Status.WORKING;
                }
                if (i >= s.steps()) {
                    stage = 2;
                    return Status.WORKING;
                }
                if (self.onGround()) {
                    motor.moveToward(Vec3.atBottomCenterOf(s.top.relative(s.dir, i + 1).below(i + 1)));
                }
                if (stuck > 80) {
                    debug = "stuck on step " + i + " of " + s.steps() + " at " + feet.toShortString();
                    return Status.FAILED;
                }
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
        if (self.onGround() && feet.getY() < s.end.getY() && feet.distManhattan(s.end) <= 2) {
            stepsDug++; // stepped down onto the new step
            s.end = feet.immutable();
            bases().setDirty();
        }
        if (feet.getY() <= target()) {
            s.finished = true;
            bases().setDirty();
            return Status.DONE;
        }
        // the next step: one ahead, one down; head room for walking down into it
        BlockPos next = feet.relative(s.dir).below();
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
            debug = "cannot step down to " + next.toShortString() + " from " + feet.toShortString();
            return Status.FAILED;
        }
        return Status.WORKING;
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
