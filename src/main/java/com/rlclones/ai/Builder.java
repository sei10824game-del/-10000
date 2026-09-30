package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a small village-style hut (5x5, two wall layers with a doorway, log corners, flat roof) block by block,
 * standing in the middle and placing every block against a neighbouring face like a player would.
 */
public final class Builder {
    public enum Status {WORKING, DONE, FAILED}

    public static final int SIZE = 5;
    public static final int BLOCKS = 55;

    private final ServerPlayer self;
    private final Motor motor;
    private BlockPos origin;
    private final List<BlockPos> plan = new ArrayList<>();
    private int ticks;
    private int stuckOn;
    private int cooldown;
    public int placed;

    public Builder(ServerPlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public BlockPos origin() {
        return origin;
    }

    public BlockPos center() {
        return origin.offset(2, 0, 2);
    }

    /** Interior spots for storage chests (never the stand spot in the centre or the doorway line). */
    public static List<BlockPos> chestSpots(BlockPos origin) {
        return List.of(origin.offset(1, 0, 3), origin.offset(3, 0, 3), origin.offset(1, 0, 1), origin.offset(3, 0, 1));
    }

    public static BlockPos centerOf(BlockPos origin) {
        return origin.offset(2, 0, 2);
    }

    private static boolean corner(int x, int z) {
        return (x == 0 || x == SIZE - 1) && (z == 0 || z == SIZE - 1);
    }

    public void start(BlockPos origin) {
        this.origin = origin.immutable();
        plan.clear();
        ticks = 0;
        placed = 0;
        for (int y = 0; y <= 1; y++) {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    boolean edge = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                    boolean door = x == 2 && z == 0;
                    if (edge && !door) {
                        plan.add(origin.offset(x, y, z));
                    }
                }
            }
        }
        // roof: outer ring (rests on the walls), then inwards so every block has a neighbour to be placed against
        for (int ring = 0; ring <= 2; ring++) {
            for (int x = ring; x < SIZE - ring; x++) {
                for (int z = ring; z < SIZE - ring; z++) {
                    if (x == ring || z == ring || x == SIZE - 1 - ring || z == SIZE - 1 - ring) {
                        plan.add(origin.offset(x, 2, z));
                    }
                }
            }
        }
    }

    public static int buildingBlocks(ServerPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) {
            if (Equipment.isBuildingBlock(p, s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Pick a material: logs for the corner posts, then planks / cobblestone / stone ..., dirt last. */
    private int materialSlot(boolean cornerPost) {
        Inventory inv = self.getInventory();
        int best = -1;
        int bestScore = -1;
        // keep enough wood for a crafting table + chest unless we already carry a chest
        int wood = 0;
        for (ItemStack s : inv.items) {
            wood += s.is(ItemTags.PLANKS) ? s.getCount() : s.is(ItemTags.LOGS) ? s.getCount() * 4 : 0;
        }
        int reserve = inv.countItem(Items.CHEST) > 0 ? 0 : 12;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!Equipment.isBuildingBlock(self, s)) {
                continue;
            }
            if ((s.is(ItemTags.PLANKS) && wood - 1 < reserve) || (s.is(ItemTags.LOGS) && wood - 4 < reserve)) {
                continue;
            }
            int score;
            if (s.is(ItemTags.LOGS)) {
                score = cornerPost ? 10 : 2;
            } else if (s.is(ItemTags.PLANKS)) {
                score = cornerPost ? 5 : 9;
            } else if (s.is(Items.COBBLESTONE) || s.is(Items.STONE_BRICKS) || s.is(Items.COBBLED_DEEPSLATE)) {
                score = 7;
            } else if (s.is(Items.DIRT) || s.is(Items.GRASS_BLOCK) || s.is(Items.NETHERRACK)) {
                score = 1;
            } else {
                score = 4;
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    public Status tick() {
        ServerLevel level = self.serverLevel();
        if (++ticks > 3600) {
            return Status.FAILED;
        }
        Vec3 stand = Vec3.atBottomCenterOf(center());
        if (Motor.horizontalDistance(self.position(), stand) > 0.6 || Math.abs(self.getY() - stand.y) > 0.6) {
            motor.navigate(stand, 0.3, false);
            if (Motor.horizontalDistance(self.position(), stand) < 1.2) {
                motor.moveToward(stand);
            }
            return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
        }
        motor.stop();
        if (cooldown > 0) {
            cooldown--;
            return Status.WORKING;
        }
        BlockPos next = null;
        for (BlockPos p : plan) {
            if (level.getBlockState(p).canBeReplaced()) {
                next = p;
                break;
            }
        }
        if (next == null) {
            return Status.DONE;
        }
        BlockPos rel = next.subtract(origin);
        int slot = materialSlot(rel.getY() < 2 && corner(rel.getX(), rel.getZ()));
        if (slot < 0) {
            return Status.FAILED; // out of material
        }
        Equipment.select(self, slot);
        if (motor.placeBlockAt(next)) {
            placed++;
            stuckOn = 0;
            cooldown = 3; // a human needs a moment per block too
        } else if (++stuckOn > 20) {
            plan.remove(next); // something is in the way; build around it
            stuckOn = 0;
        }
        return Status.WORKING;
    }

    /** Find a 5x5 flat, free spot (with room for a doorway in front) near the player. */
    @Nullable
    public static BlockPos findSite(ServerPlayer p, List<BlockPos> avoidCenters) {
        ServerLevel level = p.serverLevel();
        BlockPos feet = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos origin = feet.offset(dx - 2, dy, dz - 2);
                    double d = centerOf(origin).distSqr(feet);
                    if (d >= bestD || !siteOk(level, origin, avoidCenters)) {
                        continue;
                    }
                    bestD = d;
                    best = origin;
                }
            }
        }
        return best;
    }

    private static boolean siteOk(ServerLevel level, BlockPos origin, List<BlockPos> avoid) {
        for (BlockPos c : avoid) {
            if (c.distSqr(origin) < 12 * 12) {
                return false;
            }
        }
        for (int x = -1; x <= SIZE; x++) {
            for (int z = -1; z <= SIZE; z++) {
                boolean inside = x >= 0 && z >= 0 && x < SIZE && z < SIZE;
                boolean doorFront = x == 2 && z == -1;
                if (!inside && !doorFront) {
                    continue;
                }
                BlockPos ground = origin.offset(x, -1, z);
                if (!level.getFluidState(ground).isEmpty()
                        || !level.getBlockState(ground).isCollisionShapeFullBlock(level, ground)) {
                    return false;
                }
                for (int y = 0; y <= 2; y++) {
                    BlockPos c = origin.offset(x, y, z);
                    if (!level.getBlockState(c).canBeReplaced() || !level.getFluidState(c).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
