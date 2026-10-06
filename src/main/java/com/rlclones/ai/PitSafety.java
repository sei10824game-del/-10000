package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.ai.strategy.Option;
import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Predicate;

/** Keeps small, lethal pits out of a clone's walking line and makes a ladder route when a lower goal calls for one. */
public final class PitSafety {
    private enum Mode {NONE, COVER, CRAFT_LADDERS, DESCEND}

    private final ClonePlayer self;
    private final Motor motor;
    private final Crafting crafting;
    private Mode mode = Mode.NONE;
    private int scanCooldown;
    private int ticks;
    private int ladderFailures;
    private int ladderCraftNoProgress;
    @Nullable
    private BlockPos pendingPit;
    private int pendingDrop;
    @Nullable
    private BlockPos topFeet;
    @Nullable
    private BlockPos bottomFeet;
    @Nullable
    private BlockPos approach;
    @Nullable
    private Direction wall;
    private List<BlockPos> cover = List.of();
    private int coverIndex;

    public int pitsCovered;
    public int laddersPlaced;
    public int pitsDescended;
    public int failedDescents;
    public String debug = "";

    public PitSafety(ClonePlayer self, Motor motor, Crafting crafting) {
        this.self = self;
        this.motor = motor;
        this.crafting = crafting;
    }

    /** Called after the current behaviour has set its movement intent and immediately before {@link Motor#tick()}. */
    public boolean tick(Option option, @Nullable Vec3 goal, Predicate<BlockPos> oftenVisited) {
        if (mode == Mode.COVER) {
            return coverTick();
        }
        if (mode == Mode.CRAFT_LADDERS) {
            return craftLaddersTick();
        }
        if (mode == Mode.DESCEND) {
            return descendTick();
        }
        if (scanCooldown > 0) {
            scanCooldown--;
        }

        Vec3 direction = motor.intendedDirection();
        if (direction != null) {
            BlockPos pit = pitAhead(direction);
            if (pit != null) {
                int drop = motor.dropAt(pit.getX() + 0.5, pit.getZ() + 0.5);
                if (wantsDown(option, goal) && beginDescent(pit, drop)) {
                    return descendTick();
                }
                if (trusted(pit, oftenVisited) && beginCover(pit)) {
                    return coverTick();
                }
            }
        }

        boolean nearBase = Bases.get(self.getServer()).nearest(self.level().dimension(), self.position(), 24) != null;
        BlockPos here = self.blockPosition();
        boolean wellTravelled = oftenVisited.test(here);
        if (scanCooldown == 0 && (nearBase || wellTravelled) && Equipment.pillarBlockSlot(self) >= 0) {
            scanCooldown = 10;
            BlockPos pit = nearbyPit();
            if (pit != null && trusted(pit, oftenVisited) && beginCover(pit)) {
                return coverTick();
            }
        }
        return false;
    }

    /** Test / task hook: begin a safe descent into the opening whose floor block is {@code holeFloor}. */
    public boolean beginDescentAt(BlockPos holeFloor) {
        int drop = motor.dropAt(holeFloor.getX() + 0.5, holeFloor.getZ() + 0.5);
        return beginDescent(holeFloor, drop);
    }

    public boolean busy() {
        return mode != Mode.NONE;
    }

    private boolean wantsDown(Option option, @Nullable Vec3 goal) {
        if (option == Option.STAIRS || option == Option.SHAFT) {
            return Progression.digWanted(self);
        }
        return goal != null && goal.y < self.getY() - 4.0
                && (option == Option.EXPLORE || option == Option.MINE || option == Option.QUARRY);
    }

    private int deadlyThreshold() {
        return self.getHealth() <= 10.0f ? 4 : 8;
    }

    @Nullable
    private BlockPos pitAhead(Vec3 direction) {
        double len = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (len < 1.0e-4) {
            return null;
        }
        double dx = direction.x / len;
        double dz = direction.z / len;
        for (double ahead = 0.45; ahead <= 1.55; ahead += 0.2) {
            double x = self.getX() + dx * ahead;
            double z = self.getZ() + dz * ahead;
            if (motor.dropAt(x, z) >= deadlyThreshold()) {
                return new BlockPos(net.minecraft.util.Mth.floor(x), self.getBlockY() - 1, net.minecraft.util.Mth.floor(z));
            }
        }
        return null;
    }

    @Nullable
    private BlockPos nearbyPit() {
        int radius = 3;
        BlockPos feet = self.blockPosition();
        double best = Double.MAX_VALUE;
        BlockPos result = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                double x = feet.getX() + 0.5 + dx;
                double z = feet.getZ() + 0.5 + dz;
                int drop = motor.dropAt(x, z);
                if (drop < deadlyThreshold()) {
                    continue;
                }
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    result = new BlockPos(net.minecraft.util.Mth.floor(x), feet.getY() - 1, net.minecraft.util.Mth.floor(z));
                }
            }
        }
        return result;
    }

    private boolean trusted(BlockPos pit, Predicate<BlockPos> oftenVisited) {
        return Bases.get(self.getServer()).nearest(self.level().dimension(), Vec3.atCenterOf(pit), 24) != null || oftenVisited.test(pit);
    }

    private boolean beginCover(BlockPos seed) {
        if (!Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            return false;
        }
        List<BlockPos> cells = opening(seed, deadlyThreshold());
        if (cells.isEmpty() || cells.size() > 9 || !withinThreeByThree(cells)) {
            debug = "wide pit left open at " + seed.toShortString();
            return false;
        }
        if (Equipment.pillarBlockSlot(self) < 0) {
            debug = "no blocks to cover pit at " + seed.toShortString();
            return false;
        }
        cover = cells;
        coverIndex = 0;
        ticks = 0;
        mode = Mode.COVER;
        debug = "covering " + cells.size() + " cells at " + seed.toShortString();
        return true;
    }

    private boolean coverTick() {
        if (++ticks > 240 || !Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            mode = Mode.NONE;
            debug = "cover timed out";
            return false;
        }
        if (coverIndex >= cover.size()) {
            pitsCovered++;
            mode = Mode.NONE;
            debug = "covered pit";
            return true;
        }
        BlockPos target = cover.get(coverIndex);
        ServerLevel level = self.serverLevel();
        if (!level.getBlockState(target).canBeReplaced()) {
            coverIndex++;
            return true;
        }
        if (Equipment.pillarBlockSlot(self) < 0) {
            mode = Mode.NONE;
            debug = "ran out of blocks while covering " + target.toShortString();
            return false;
        }
        if (self.getEyePosition().distanceTo(Vec3.atCenterOf(target)) > Motor.BLOCK_REACH - 0.25) {
            motor.navigate(Vec3.atBottomCenterOf(target.above()), 0.5, false);
            return true;
        }
        Equipment.select(self, Equipment.pillarBlockSlot(self));
        if (motor.placeBlockAt(target) || !level.getBlockState(target).canBeReplaced()) {
            coverIndex++;
        } else if (motor.stuckCount() > 8) {
            mode = Mode.NONE;
            debug = "could not place cover at " + target.toShortString();
            return false;
        }
        motor.stop();
        return true;
    }

    private boolean beginDescent(BlockPos holeFloor, int drop) {
        if (!Config.get(Config.ALLOW_BLOCK_PLACING, true) || drop < 8 || drop >= 32) {
            return false;
        }
        int feetY = self.getBlockY();
        int bottomY = feetY - drop;
        List<BlockPos> opening = opening(holeFloor, deadlyThreshold());
        if (opening.size() != 1) {
            debug = "only a one-cell shaft can be descended safely";
            return false;
        }
        int x = holeFloor.getX();
        int z = holeFloor.getZ();
        BlockPos firstFeet = new BlockPos(x, feetY, z);
        BlockPos lastFeet = new BlockPos(x, bottomY, z);
        Direction chosen = null;
        int bestSupport = -1;
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            int supports = 0;
            for (int y = bottomY; y <= feetY; y++) {
                if (solid(firstFeet.atY(y).relative(candidate))) {
                    supports++;
                }
            }
            if (supports > bestSupport) {
                chosen = candidate;
                bestSupport = supports;
            }
        }
        int missingSupports = drop + 1 - Math.max(0, bestSupport);
        int blocks = buildingBlocks();
        if (bestSupport == 0 && blocks < drop + 1 || missingSupports > blocks) {
            debug = "no ladder wall or blocks for the pit";
            return false;
        }
        Direction selected = chosen == null ? Direction.NORTH : chosen;
        BlockPos outside = firstFeet.relative(selected.getOpposite());
        BlockPos standingCell = outside;
        if (!solid(standingCell.below()) || !replaceable(standingCell) || !replaceable(standingCell.above())) {
            debug = "no safe rim at " + standingCell.toShortString();
            return false;
        }
        if (self.getInventory().countItem(Items.LADDER) < drop + 1) {
            if (!canCraftLadder()) {
                debug = "need " + (drop + 1) + " ladders, have " + self.getInventory().countItem(Items.LADDER);
                return false;
            }
            pendingPit = holeFloor.immutable();
            pendingDrop = drop;
            ladderCraftNoProgress = 0;
            ticks = 0;
            mode = Mode.CRAFT_LADDERS;
            crafting.forcedTarget = Items.LADDER;
            debug = "crafting ladders for " + holeFloor.toShortString();
            return true;
        }
        topFeet = firstFeet;
        bottomFeet = lastFeet;
        approach = standingCell;
        wall = selected;
        ticks = 0;
        ladderFailures = 0;
        mode = Mode.DESCEND;
        debug = "descending pit " + firstFeet.toShortString() + " to " + lastFeet.toShortString() + " wall=" + selected;
        return true;
    }

    private boolean canCraftLadder() {
        for (net.minecraft.world.item.crafting.CraftingRecipe recipe : Crafting.recipesFor(self, stack -> stack.is(Items.LADDER))) {
            if (Crafting.canCraft(self, recipe)) {
                return true;
            }
        }
        return false;
    }

    private boolean craftLaddersTick() {
        if (pendingPit == null || ++ticks > 2400) {
            stopCraftingLadders("ladder crafting timed out");
            return false;
        }
        int before = self.getInventory().countItem(Items.LADDER);
        if (before >= pendingDrop + 1) {
            BlockPos pit = pendingPit;
            int drop = motor.dropAt(pit.getX() + 0.5, pit.getZ() + 0.5);
            stopCraftingLadders("ladders ready");
            if (beginDescent(pit, drop)) {
                return descendTick();
            }
            return false;
        }
        if (!canCraftLadder()) {
            stopCraftingLadders("cannot craft enough ladders");
            return false;
        }
        crafting.forcedTarget = Items.LADDER;
        Crafting.Status status = crafting.tick();
        int after = self.getInventory().countItem(Items.LADDER);
        if (after > before) {
            ladderCraftNoProgress = 0;
        } else if (status == Crafting.Status.DONE && ++ladderCraftNoProgress >= 2) {
            stopCraftingLadders("crafting made no ladder progress");
            return false;
        }
        return true;
    }

    private void stopCraftingLadders(String why) {
        crafting.forcedTarget = null;
        crafting.reset();
        pendingPit = null;
        pendingDrop = 0;
        mode = Mode.NONE;
        debug = why;
    }

    private boolean descendTick() {
        if (++ticks > 1200 || topFeet == null || bottomFeet == null || approach == null || wall == null) {
            failedDescents++;
            mode = Mode.NONE;
            debug = "ladder descent timed out";
            return false;
        }
        BlockPos feet = self.blockPosition();
        int dx = Math.abs(feet.getX() - topFeet.getX());
        int dz = Math.abs(feet.getZ() - topFeet.getZ());
        boolean inColumn = dx == 0 && dz == 0;
        if (inColumn && feet.getY() <= bottomFeet.getY() && self.onGround()) {
            Bases.get(self.getServer()).addLadderPit(self.level().dimension(), topFeet, bottomFeet, wall);
            pitsDescended++;
            mode = Mode.NONE;
            motor.stop();
            debug = "reached pit bottom " + feet.toShortString();
            return true;
        }

        if (!inColumn && (Motor.horizontalDistance(self.position(), Vec3.atBottomCenterOf(approach)) > 0.22
                || Math.abs(self.getY() - approach.getY()) > 1.2)) {
            motor.navigate(Vec3.atBottomCenterOf(approach), 0.25, false);
            return true;
        }

        if (!inColumn) {
            // Place the first rung before stepping over the edge.
            if (!ensureLadder(topFeet)) {
                motor.stop();
                return true;
            }
            motor.dare();
            motor.moveToward(new Vec3(topFeet.getX() + 0.5, self.getY(), topFeet.getZ() + 0.5));
            return true;
        }

        // Rungs are placed one block ahead of the feet while the player descends slowly on the existing ladder.
        BlockPos current = new BlockPos(topFeet.getX(), feet.getY(), topFeet.getZ());
        if (!ensureLadder(current)) {
            motor.stop();
            return true;
        }
        if (feet.getY() > bottomFeet.getY() && !ensureLadder(current.below())) {
            motor.stop();
            return true;
        }
        motor.stop();
        motor.dare();
        motor.sneak(true);
        if (self.onClimbable()) {
            Vec3 velocity = self.getDeltaMovement();
            self.setDeltaMovement(velocity.x * 0.7, -0.12, velocity.z * 0.7);
        }
        return true;
    }

    /** Ensure a solid support and ladder exist in the column cell; true only once the rung is physically present. */
    private boolean ensureLadder(BlockPos rung) {
        ServerLevel level = self.serverLevel();
        if (level.getBlockState(rung).getBlock() instanceof LadderBlock) {
            return true;
        }
        if (!replaceable(rung)) {
            return false;
        }
        BlockPos support = rung.relative(wall);
        if (!solid(support)) {
            int slot = Equipment.pillarBlockSlot(self);
            if (slot < 0) {
                debug = "no support block for ladder at " + rung.toShortString();
                ladderFailures++;
                return false;
            }
            Equipment.select(self, slot);
            if (motor.placeBlockAt(support)) {
                return false; // the next tick will attach the ladder to this new wall block
            }
            ladderFailures++;
            return false;
        }
        int ladder = slotOf(Items.LADDER);
        if (ladder < 0) {
            debug = "ran out of ladders at " + rung.toShortString();
            ladderFailures++;
            return false;
        }
        Equipment.select(self, ladder);
        if (motor.useOnFace(support, wall.getOpposite())) {
            if (level.getBlockState(rung).getBlock() instanceof LadderBlock) {
                laddersPlaced++;
                return true;
            }
        }
        ladderFailures++;
        return level.getBlockState(rung).getBlock() instanceof LadderBlock;
    }

    private List<BlockPos> opening(BlockPos seed, int threshold) {
        List<BlockPos> found = new ArrayList<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        while (!queue.isEmpty() && found.size() <= 9) {
            BlockPos p = queue.remove();
            if (found.contains(p) || motor.dropAt(p.getX() + 0.5, p.getZ() + 0.5) < threshold) {
                continue;
            }
            found.add(p);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (Math.abs(n.getX() - seed.getX()) <= 2 && Math.abs(n.getZ() - seed.getZ()) <= 2 && !found.contains(n)) {
                    queue.add(n);
                }
            }
        }
        return found;
    }

    private static boolean withinThreeByThree(List<BlockPos> cells) {
        if (cells.isEmpty()) {
            return false;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return maxX - minX < 3 && maxZ - minZ < 3;
    }

    private boolean solid(BlockPos p) {
        ServerLevel level = self.serverLevel();
        return level.getFluidState(p).isEmpty() && !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private boolean replaceable(BlockPos p) {
        return self.level().getBlockState(p).canBeReplaced() && self.level().getFluidState(p).isEmpty();
    }

    private int buildingBlocks() {
        int count = 0;
        for (ItemStack stack : self.getInventory().items) {
            if (Equipment.isPillarBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int slotOf(net.minecraft.world.item.Item item) {
        for (int i = 0; i < self.getInventory().items.size(); i++) {
            if (self.getInventory().items.get(i).is(item)) {
                return i;
            }
        }
        return -1;
    }
}
