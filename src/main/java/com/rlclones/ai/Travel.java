package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

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
        return bridging;
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
        for (int i = 1; i <= 16; i++) {
            BlockPos c = feet.relative(d, i);
            if (!passable(c) || !passable(c.above())) {
                return i == 1 ? 0 : -1; // a wall: not a gap problem
            }
            if (solid(c.below())) {
                return i - 1;
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
    public boolean tick(long now) {
        if (bridging) {
            return bridgeTick();
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
            return false;
        }
        if (g > 0 && Config.get(Config.ALLOW_BLOCK_PLACING, true) && bridgeBlocks() >= g) {
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
        BlockPos under = next.below();
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

    private boolean finish() {
        bridging = false;
        cooldown = 20;
        motor.resetStuck();
        return false;
    }
}
