package com.rlclones.gametest;

import com.rlclones.clone.Bases;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Isolated namespace: -Drlclones.gameTestNamespaces=rlclones_stair_repair. */
@GameTestHolder("rlclones_stair_repair")
@PrefixGameTestTemplate(false)
public final class StairRepairTests {
    private static final java.util.Set<java.util.UUID> TEST_CLONES = new java.util.HashSet<>();

    @net.minecraft.gametest.framework.AfterBatch(batch = "stair-repair")
    public static void cleanup(net.minecraft.server.level.ServerLevel level) {
        var manager = CloneManager.get(level.getServer());
        for (ClonePlayer clone : new java.util.ArrayList<>(manager.clones())) {
            if (TEST_CLONES.contains(clone.getUUID())) manager.remove(clone, true, Component.literal("repair test cleanup"));
        }
        TEST_CLONES.clear();
    }

    private static ClonePlayer clone(GameTestHelper h) {
        Vec3 at = h.absoluteVec(new Vec3(6.5, 4, 7.5));
        ClonePlayer clone = CloneManager.get(h.getLevel().getServer()).summon(null, h.getLevel(), at, 0);
        if (clone == null) throw new IllegalStateException("could not summon test clone");
        TEST_CLONES.add(clone.getUUID());
        clone.setAiEnabled(false);
        clone.getInventory().clearContent();
        clone.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        clone.getInventory().selected = 0;
        return clone;
    }

    private static Bases.Staircase stair(GameTestHelper h, int steps) {
        var level = h.getLevel();
        BlockPos top = h.absolutePos(new BlockPos(4, 6, 7));
        var stair = Bases.get(level.getServer()).addStaircase(level.dimension(), top, Direction.EAST);
        stair.end = top.relative(Direction.EAST, steps).below(steps);
        for (int i = 0; i <= steps; i++) {
            BlockPos tread = top.relative(Direction.EAST, i).below(i + 1);
            level.setBlock(tread, Blocks.STONE.defaultBlockState(), 3);
            for (int y = 1; y <= 3; y++) level.setBlock(tread.above(y), Blocks.AIR.defaultBlockState(), 3);
        }
        return stair;
    }

    private static void finish(GameTestHelper h, ClonePlayer clone, Bases.Staircase stair) {
        Bases.get(h.getLevel().getServer()).staircases.remove(stair);
        CloneManager.get(h.getLevel().getServer()).remove(clone, true, Component.literal("repair test done"));
        TEST_CLONES.remove(clone.getUUID());
        h.succeed();
    }

    @GameTest(template = "arena", batch = "stair-repair", timeoutTicks = 40)
    public static void activeStairsAndSideLaneAreScanned(GameTestHelper h) {
        ClonePlayer clone = clone(h);
        clone.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        var stair = stair(h, 3);
        BlockPos hole = stair.top.relative(stair.dir, 2).below(3);
        h.getLevel().setBlock(hole, Blocks.AIR.defaultBlockState(), 3);
        BlockPos laneHole = hole.relative(stair.dir.getClockWise());
        h.getLevel().setBlock(laneHole, Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(laneHole.above(), Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(laneHole.above(2), Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(laneHole.relative(stair.dir.getOpposite()).above(), Blocks.STONE.defaultBlockState(), 3);
        clone.controller().scanTreads();
        h.assertTrue(!stair.finished && clone.controller().motor().repairs.contains(hole), "active staircase hole is queued");
        h.assertTrue(clone.controller().motor().repairs.contains(laneHole), "opened side lane is queued");
        finish(h, clone, stair);
    }

    @GameTest(template = "arena", batch = "stair-repair", timeoutTicks = 40)
    public static void treadOreWaitsForRepairMaterials(GameTestHelper h) {
        ClonePlayer clone = clone(h);
        var stair = stair(h, 3);
        BlockPos ore = stair.top.relative(stair.dir, 2).below(3);
        h.getLevel().setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), 3);
        h.assertTrue(!clone.controller().motor().mine(ore), "do not open a tread without replacement material");
        h.assertTrue(h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE), "ore tread stays intact");
        h.assertTrue(clone.controller().motor().repairs.isEmpty(), "no repair is queued for an unbroken block");
        finish(h, clone, stair);
    }

    @GameTest(template = "arena", batch = "stair-repair", timeoutTicks = 40)
    public static void adjacentMissingTreadsAreNotIgnored(GameTestHelper h) {
        ClonePlayer clone = clone(h);
        clone.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        var stair = stair(h, 4);
        BlockPos first = stair.top.relative(stair.dir, 1).below(2);
        BlockPos second = stair.top.relative(stair.dir, 2).below(3);
        h.getLevel().setBlock(first, Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(second, Blocks.AIR.defaultBlockState(), 3);
        clone.controller().scanTreads();
        h.assertTrue(clone.controller().motor().repairs.contains(first), "first consecutive gap is queued");
        h.assertTrue(clone.controller().motor().repairs.contains(second), "second consecutive gap is queued");
        finish(h, clone, stair);
    }

    @GameTest(template = "arena", batch = "stair-repair", timeoutTicks = 160)
    public static void fillsTreadWithoutBlockingHeadroom(GameTestHelper h) {
        ClonePlayer clone = clone(h);
        clone.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        var stair = stair(h, 3);
        BlockPos hole = stair.top.relative(stair.dir, 2).below(3);
        h.getLevel().setBlock(hole, Blocks.AIR.defaultBlockState(), 3);
        Vec3 at = Vec3.atBottomCenterOf(stair.top.relative(stair.dir).below());
        clone.teleportTo(h.getLevel(), at.x, at.y, at.z, 0, 30);
        clone.controller().motor().repairs.add(hole);
        h.onEachTick(() -> clone.controller().repairTreads(h.getLevel().getGameTime()));
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(hole).is(Blocks.COBBLESTONE), "missing tread is replaced with a full block");
            h.assertTrue(h.getLevel().getBlockState(hole.above()).isAir()
                    && h.getLevel().getBlockState(hole.above(2)).isAir(), "step and headroom stay open");
            finish(h, clone, stair);
        });
    }
    @GameTest(template = "arena", batch = "stair-repair", timeoutTicks = 240)
    public static void minesTreadOreAndRestoresTheStep(GameTestHelper h) {
        ClonePlayer clone = clone(h);
        clone.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        var stair = stair(h, 3);
        BlockPos ore = stair.top.relative(stair.dir, 2).below(3);
        h.getLevel().setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), 3);
        Vec3 at = Vec3.atBottomCenterOf(stair.top.relative(stair.dir).below());
        clone.teleportTo(h.getLevel(), at.x, at.y, at.z, 0, 30);
        boolean[] mined = {false};
        h.onEachTick(() -> {
            if (!mined[0]) {
                if (clone.controller().motor().mine(ore)) {
                    mined[0] = true;
                    h.assertTrue(clone.controller().motor().repairs.contains(ore), "successful ore mining queues tread repair");
                }
            } else {
                clone.controller().repairTreads(h.getLevel().getGameTime());
            }
            clone.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(mined[0] && h.getLevel().getBlockState(ore).is(Blocks.COBBLESTONE), "mined ore is replaced by a walkable tread");
            h.assertTrue(h.getLevel().getBlockState(ore.above()).isAir(), "repair does not create a pillar in the stairs");
            finish(h, clone, stair);
        });
    }

    @GameTest(template = "arena", batch = "stair-repair", timeoutTicks = 240)
    public static void stepsOutOfTheHoleBeforeRepairing(GameTestHelper h) {
        ClonePlayer clone = clone(h);
        clone.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        var stair = stair(h, 3);
        BlockPos hole = stair.top.relative(stair.dir, 2).below(3);
        h.getLevel().setBlock(hole, Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(hole.below(), Blocks.STONE.defaultBlockState(), 3);
        Vec3 at = Vec3.atBottomCenterOf(hole);
        clone.teleportTo(h.getLevel(), at.x, at.y, at.z, 0, 0);
        clone.controller().motor().repairs.add(hole);
        h.onEachTick(() -> {
            clone.controller().repairTreads(h.getLevel().getGameTime());
            clone.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(hole).is(Blocks.COBBLESTONE), "clone moves out and restores the tread");
            h.assertTrue(!clone.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(hole)), "clone is not buried by its repair");
            finish(h, clone, stair);
        });
    }

}
