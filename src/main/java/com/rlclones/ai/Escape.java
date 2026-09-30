package com.rlclones.ai;

import com.rlclones.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Getting out of holes like a player does: detect that no walkable way out exists (small flood fill), then
 * pillar up with blocks, dig a staircase, or tunnel horizontally towards where we wanted to go.
 */
public final class Escape {
    public enum Status {WORKING, DONE, FAILED}

    private final ServerPlayer self;
    private final Motor motor;

    @Nullable
    private Vec3 goal;
    private int ticks;
    @Nullable
    private Direction dir;
    private final Set<Direction> badDirs = EnumSet.noneOf(Direction.class);
    private BlockPos pillarBase;
    private boolean pillarPlaced;
    private int pillarTicks;
    @Nullable
    private BlockPos mining;
    private int miningTicks;
    public int blocksMined;
    public int blocksPlaced;

    public Escape(ServerPlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    // ------------------------------------------------------------------ detection

    private boolean solid(BlockPos p) {
        BlockState s = self.level().getBlockState(p);
        if (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof TrapDoorBlock) {
            return false; // a player can open those
        }
        return !s.getCollisionShape(self.level(), p).isEmpty();
    }

    private boolean liquid(BlockPos p) {
        return !self.level().getFluidState(p).isEmpty();
    }

    private boolean standable(BlockPos c) {
        return !solid(c) && !solid(c.above()) && (solid(c.below()) || liquid(c) || liquid(c.below()));
    }

    /**
     * True when the set of places reachable by walking / 1-block jumps / safe drops is tiny: we are in a pit or
     * walled in. Water counts as a way up (we can swim).
     */
    public boolean isTrapped() {
        if (self.isInWater() || self.isFallFlying() || !self.onGround()) {
            return false;
        }
        BlockPos start = self.blockPosition();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < 40) {
            BlockPos c = queue.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos m = n.above(dy);
                    if (dy == 1 && solid(c.above(2))) {
                        continue; // no headroom to jump
                    }
                    if (dy < 0 && (solid(n) || solid(n.above()))) {
                        break; // can't step off that side
                    }
                    if (standable(m)) {
                        if (seen.add(m.immutable()) && Math.abs(m.getX() - start.getX()) <= 6 && Math.abs(m.getZ() - start.getZ()) <= 6) {
                            queue.add(m.immutable());
                        }
                        break;
                    }
                }
            }
        }
        return seen.size() < 10;
    }

    // ------------------------------------------------------------------ escaping

    public void start(@Nullable Vec3 goal) {
        this.goal = goal;
        ticks = 0;
        dir = null;
        badDirs.clear();
        pillarBase = null;
        mining = null;
        motor.resetMining();
    }

    public Status tick() {
        ticks++;
        if (ticks > 900) {
            motor.resetMining();
            return Status.FAILED;
        }
        if (ticks % 10 == 0 && pillarBase == null && self.onGround() && !isTrapped()) {
            motor.resetMining();
            return Status.DONE;
        }
        BlockPos feet = self.blockPosition();
        boolean pit = enclosedAbove(feet);
        boolean canPlace = Config.get(Config.ALLOW_BLOCK_PLACING, true) && Equipment.pillarBlockSlot(self) >= 0;
        boolean canBreak = Config.get(Config.ALLOW_BLOCK_BREAKING, true);

        if (pillarBase != null) {
            return pillar(feet);
        }
        if (!pit) {
            // open sides but every way down is a dangerous drop (e.g. on top of a tall pillar): dig down like a player
            return canBreak ? digDown(feet) : Status.FAILED;
        }
        boolean sameLevelGoal = goal != null && Math.abs(goal.y - feet.getY()) < 1.0;
        if (canPlace && !solid(feet.above(2))) {
            return pillar(feet); // with blocks at hand, climbing out is the quickest and never breaks anything
        }
        if (!canBreak) {
            return canPlace ? pillar(feet) : Status.FAILED;
        }
        if (dir == null || badDirs.contains(dir)) {
            dir = chooseDirection(feet);
            if (dir == null) {
                return Status.FAILED;
            }
        }
        return sameLevelGoal ? tunnel(feet) : stair(feet);
    }

    private Status digDown(BlockPos feet) {
        BlockPos below = feet.below();
        if (liquid(below.below()) || dangerAround(below) || self.level().getBlockState(below).getDestroySpeed(self.level(), below) < 0) {
            return Status.FAILED;
        }
        motor.lookAt(Vec3.atCenterOf(below));
        return dig(below);
    }

    /** The pit walls are higher than a jump in every direction. */
    private boolean enclosedAbove(BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!solid(feet.relative(d).above())) {
                return false;
            }
        }
        return true;
    }

    private Status pillar(BlockPos feet) {
        motor.lookAngles(self.getYRot(), 90f);
        motor.stop();
        if (pillarBase == null) {
            if (!self.onGround()) {
                return Status.WORKING;
            }
            pillarBase = feet;
            pillarPlaced = false;
            pillarTicks = 0;
            Equipment.select(self, Equipment.pillarBlockSlot(self));
        }
        pillarTicks++;
        if (!pillarPlaced) {
            if (pillarTicks <= 1 && self.onGround()) {
                motor.jump();
            }
            if (self.getY() >= pillarBase.getY() + 1.0 && self.level().getBlockState(pillarBase).canBeReplaced()) {
                if (!Equipment.isPillarBlock(self.getMainHandItem())) {
                    Equipment.select(self, Equipment.pillarBlockSlot(self));
                }
                pillarPlaced = motor.useOnTopFace(pillarBase.below());
                if (pillarPlaced) {
                    blocksPlaced++;
                } else {
                    pillarBase = null;
                    return Status.FAILED;
                }
            } else if (pillarTicks > 14) {
                pillarBase = null; // jump was blocked, retry
            }
        } else if (self.onGround()) {
            pillarBase = null;
            // step off the pillar if an edge is walkable now
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = self.blockPosition().relative(d);
                if (standable(n)) {
                    motor.moveToward(Vec3.atBottomCenterOf(n));
                    break;
                }
            }
        }
        return Status.WORKING;
    }

    @Nullable
    private Direction chooseDirection(BlockPos feet) {
        Direction best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (badDirs.contains(d)) {
                continue;
            }
            double score = 0;
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos p = feet.relative(d).above(dy);
                BlockState st = self.level().getBlockState(p);
                if (st.getDestroySpeed(self.level(), p) < 0) {
                    score += 1000; // bedrock & co.
                } else if (solid(p)) {
                    score += 1 + st.getDestroySpeed(self.level(), p);
                }
                if (dangerAround(p)) {
                    score += 500;
                }
            }
            if (goal != null) {
                Vec3 to = goal.subtract(Vec3.atBottomCenterOf(feet));
                score -= 3 * (to.x * d.getStepX() + to.z * d.getStepZ()) / Math.max(1e-3, Math.sqrt(to.x * to.x + to.z * to.z));
            }
            if (score < bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return bestScore >= 500 ? null : best;
    }

    /** Opening this block would let lava / water pour in. */
    private boolean dangerAround(BlockPos p) {
        for (Direction d : Direction.values()) {
            FluidState f = self.level().getFluidState(p.relative(d));
            if (!f.isEmpty()) {
                return true;
            }
        }
        return self.level().getBlockState(p).is(BlockTags.FIRE);
    }

    /** Dig a step: clear head room, then the two blocks in front above the step, then climb onto it. */
    private Status stair(BlockPos feet) {
        BlockPos head2 = feet.above(2);
        BlockPos step = feet.relative(dir);
        BlockPos f1 = step.above();
        BlockPos f2 = f1.above();
        if (solid(head2)) {
            return dig(head2);
        }
        if (solid(f1)) {
            return dig(f1);
        }
        if (solid(f2)) {
            return dig(f2);
        }
        if (!solid(step)) {
            motor.moveToward(Vec3.atBottomCenterOf(step));
            return Status.WORKING;
        }
        motor.lookAt(Vec3.atCenterOf(f1));
        motor.moveToward(Vec3.atBottomCenterOf(f1));
        if (self.onGround()) {
            motor.jump();
        }
        return Status.WORKING;
    }

    private Status tunnel(BlockPos feet) {
        BlockPos f0 = feet.relative(dir);
        BlockPos f1 = f0.above();
        if (solid(f1)) {
            return dig(f1);
        }
        if (solid(f0)) {
            return dig(f0);
        }
        motor.moveToward(Vec3.atBottomCenterOf(f0));
        return Status.WORKING;
    }

    private Status dig(BlockPos p) {
        ServerLevel level = self.serverLevel();
        BlockState st = level.getBlockState(p);
        if (st.getDestroySpeed(level, p) < 0 || dangerAround(p)) {
            if (dir == null) {
                return Status.FAILED;
            }
            badDirs.add(dir);
            dir = null;
            return Status.WORKING;
        }
        if (!p.equals(mining)) {
            mining = p.immutable();
            miningTicks = 0;
            Equipment.select(self, Equipment.bestToolSlot(self, st));
        }
        motor.stop();
        if (motor.mine(p)) {
            blocksMined++;
            mining = null;
        } else if (++miningTicks > 400) {
            if (dir == null) {
                return Status.FAILED;
            }
            badDirs.add(dir); // too hard for what we hold
            dir = null;
            mining = null;
        }
        return Status.WORKING;
    }
}
