package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * R-19: a hole in front of us that would kill a fall (8+ blocks). When digging down is what we want and ladders are at hand,
 * climb down it, putting a ladder on the wall below as we go; near a base (a small opening) cover it with blocks instead;
 * otherwise leave it to the edge crouch. ponytail: one wall block + ladder per level; no record of the ladder pit for the way
 * back (the clone climbs up the ladders it put down if it needs to), add to Bases if clones get lost down there.
 */
public final class Pits {
    private static final int DEEP = 8;
    private enum Stage {NONE, ENTER, DOWN, COVER}

    private final ClonePlayer self;
    private final Motor motor;
    private Stage stage = Stage.NONE;
    private BlockPos edge;
    private Direction dir;
    private final List<BlockPos> cover = new ArrayList<>();
    private int ticks;
    private long cooldownUntil;
    /** Test hook: climb down any deep hole, whatever the clone wants. */
    public boolean force;
    /** Ladders put down / blocks laid over holes / holes climbed to the bottom (tests). */
    public int laddersPlaced;
    public int covered;
    public int descents;

    public Pits(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public boolean busy() {
        return stage != Stage.NONE;
    }

    private ServerLevel level() {
        return self.serverLevel();
    }

    private int ladders() {
        return self.getInventory().countItem(Items.LADDER);
    }

    private int ladderSlot() {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(Items.LADDER)) {
                return i;
            }
        }
        return -1;
    }

    private boolean solid(BlockPos p) {
        return level().getBlockState(p).isFaceSturdy(level(), p, Direction.UP) || !level().getBlockState(p).getCollisionShape(level(), p).isEmpty();
    }

    private boolean air(BlockPos p) {
        return level().getBlockState(p).getCollisionShape(level(), p).isEmpty() && level().getFluidState(p).isEmpty();
    }

    /** How far down the hole at {@code p} (at our feet level) goes; 0 if it is no hole. */
    private int depth(BlockPos p) {
        if (!air(p) || !air(p.above())) {
            return 0;
        }
        int d = motor.dropAt(p.getX() + 0.5, p.getZ() + 0.5);
        return d >= 64 ? 0 : d; // 64: lava / no ground: not for a ladder
    }

    /** @return true while it has the clone's hands. */
    public boolean tick(long now, @Nullable com.rlclones.ai.strategy.Option option) {
        if (self.isCreative() || self.isPassenger() || self.isInWater() || !Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            stage = Stage.NONE;
            return false;
        }
        if (stage == Stage.NONE) {
            if (now < cooldownUntil || !self.onGround() || (now + self.getId()) % 10 != 0) {
                return false;
            }
            BlockPos feet = self.blockPosition();
            Direction d = self.getDirection();
            BlockPos e = feet.relative(d);
            int depth = depth(e);
            if (depth < DEEP) {
                return false;
            }
            boolean wantDown = force || option == com.rlclones.ai.strategy.Option.EXPLORE && Progression.digWanted(self);
            if (wantDown && ladders() >= Math.min(depth, 24) && solid(e.relative(d).below())) {
                stage = Stage.ENTER;
                edge = e;
                dir = d;
            } else if (!wantDown && com.rlclones.clone.Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 24) != null
                    && Equipment.pillarBlockSlot(self) >= 0 && planCover(e)) {
                stage = Stage.COVER;
            } else {
                cooldownUntil = now + 200;
                return false;
            }
            ticks = 0;
        }
        if (++ticks > 600) {
            stage = Stage.NONE;
            cooldownUntil = now + 600;
            return false;
        }
        switch (stage) {
            case ENTER -> {
                BlockPos wall = edge.relative(dir).below();
                Equipment.select(self, ladderSlot());
                if (!ladderAt(edge.below())) {
                    motor.useOnFace(wall, dir.getOpposite());
                    if (ladderAt(edge.below())) {
                        laddersPlaced++;
                    }
                    return true;
                }
                motor.dare(); // over the edge on purpose: no crouching there
                motor.moveToward(Vec3.atBottomCenterOf(edge));
                if (self.blockPosition().equals(edge)) {
                    stage = Stage.DOWN;
                }
            }
            case DOWN -> {
                BlockPos here = self.blockPosition();
                BlockPos below = here.below();
                if (solid(below) || !level().getFluidState(below).isEmpty() || ladders() == 0 && !ladderAt(below)) {
                    if (!ladderAt(below)) {
                        descents++;
                        stage = Stage.NONE;
                        cooldownUntil = now + 400;
                        return false;
                    }
                }
                if (ladderAt(below) || ladderAt(here)) {
                    motor.stop(); // slides down the ladder by itself
                    if (!ladderAt(below)) {
                        placeBelow(below);
                    }
                    return true;
                }
                placeBelow(below);
            }
            case COVER -> {
                if (cover.isEmpty()) {
                    covered++;
                    stage = Stage.NONE;
                    cooldownUntil = now + 100;
                    return false;
                }
                BlockPos c = cover.get(0).below();
                Equipment.select(self, Equipment.pillarBlockSlot(self));
                if (!air(c) || motor.placeBlockAt(c)) {
                    cover.remove(0);
                }
            }
            default -> {
            }
        }
        return true;
    }

    private boolean ladderAt(BlockPos p) {
        return level().getBlockState(p).is(net.minecraft.world.level.block.Blocks.LADDER);
    }

    /** A ladder in the cell {@code cell}, on whichever side has a wall. */
    private void placeBelow(BlockPos cell) {
        if (!air(cell)) {
            return;
        }
        Equipment.select(self, ladderSlot());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos wall = cell.relative(d);
            if (solid(wall) && level().getBlockState(wall).isFaceSturdy(level(), wall, d.getOpposite())) {
                if (motor.useOnFace(wall, d.getOpposite()) && ladderAt(cell)) {
                    laddersPlaced++;
                    return;
                }
            }
        }
    }

    /** The opening (at most 3x3 around {@code e}); false when the hole is wider than that (a ravine): leave it. */
    private boolean planCover(BlockPos e) {
        cover.clear();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos p = e.offset(dx, 0, dz);
                if (depth(p) >= DEEP) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        cover.clear();
                        return false; // reaches past 3x3
                    }
                    cover.add(p);
                }
            }
        }
        cover.sort(Comparator.comparingDouble(p -> p.distSqr(self.blockPosition())));
        return !cover.isEmpty();
    }
}
