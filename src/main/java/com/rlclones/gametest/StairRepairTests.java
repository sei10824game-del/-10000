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

    @net.minecraft.gametest.framework.AfterBatch(batch="stair-lift") public static void liftCleanup(net.minecraft.server.level.ServerLevel level){cleanup(level);}
    private static BlockPos narrowHole(GameTestHelper h,ClonePlayer c,Bases.Staircase stair,boolean breakable){
        BlockPos hole=stair.top.relative(stair.dir,2).below(3);var level=h.getLevel();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=0;dy<=3;dy++)if(dx!=0||dz!=0)level.setBlock(hole.offset(dx,dy,dz),Blocks.BEDROCK.defaultBlockState(),3);
        level.setBlock(hole,Blocks.AIR.defaultBlockState(),3);level.setBlock(hole.below(),Blocks.STONE.defaultBlockState(),3);level.setBlock(hole.above(),Blocks.AIR.defaultBlockState(),3);
        level.setBlock(hole.above(2),(breakable?Blocks.STONE:Blocks.BEDROCK).defaultBlockState(),3);level.setBlock(hole.above(3),Blocks.BEDROCK.defaultBlockState(),3);
        var pos=Vec3.atBottomCenterOf(hole);c.teleportTo(level,pos.x,pos.y,pos.z,0,0);c.setOnGround(true);c.controller().motor().repairs.add(hole);return hole;
    }
    @GameTest(template="arena",batch="stair-lift",timeoutTicks=240)
    public static void clearsKnownHeadroomThenLiftsOnceAndRepairsFromInsideGap(GameTestHelper h){
        var c=clone(h);c.getInventory().add(new ItemStack(Items.COBBLESTONE,32));var stair=stair(h,3);var hole=narrowHole(h,c,stair,true);
        h.onEachTick(()->{c.controller().repairTreads(h.getLevel().getGameTime());c.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(h.getLevel().getBlockState(hole).is(Blocks.COBBLESTONE),"self-occupied gap restored after safe bounded lift");h.assertTrue(h.getLevel().getBlockState(hole.above(2)).isAir(),"only known blocked headroom mined");h.assertTrue(!c.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(hole)),"never places a block through its own body");h.assertTrue(c.getInventory().countItem(Items.COBBLESTONE)==31,"exactly one repair block consumed");finish(h,c,stair);});
    }
    @GameTest(template="arena",batch="stair-lift",timeoutTicks=100)
    public static void protectedLowCeilingStopsJumpingWithoutDestroyingOrSpendingBlocks(GameTestHelper h){
        var c=clone(h);c.getInventory().add(new ItemStack(Items.COBBLESTONE,32));var stair=stair(h,3);var hole=narrowHole(h,c,stair,false);double[] highest={c.getY()};
        h.onEachTick(()->{c.controller().motor().jump();c.controller().repairTreads(h.getLevel().getGameTime());c.controller().motor().tick();highest[0]=Math.max(highest[0],c.getY());});
        h.runAtTickTime(60,()->{h.assertTrue(highest[0]<hole.getY()+.2,"no futile jumps against unbreakable ceiling");h.assertTrue(h.getLevel().getBlockState(hole.above(2)).is(Blocks.BEDROCK)&&c.getInventory().countItem(Items.COBBLESTONE)==32,"protected blocks and materials preserved");finish(h,c,stair);h.succeed();});
    }

    @GameTest(template="arena",batch="stair-lift",timeoutTicks=300)
    public static void actualAscentRepairsKnownGapBeforeTryingToJumpUp(GameTestHelper h){
        var c=clone(h);c.getInventory().add(new ItemStack(Items.COBBLESTONE,32));var stair=stair(h,4);var hole=stair.top.relative(stair.dir,2).below(3);
        h.getLevel().setBlock(hole,Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(hole.below(),Blocks.STONE.defaultBlockState(),3);
        var pos=Vec3.atBottomCenterOf(stair.top.relative(stair.dir,3).below(3));c.teleportTo(h.getLevel(),pos.x,pos.y,pos.z,0,0);c.setOnGround(true);c.controller().motor().repairs.add(hole);
        com.rlclones.ai.Motor.clearSharedPaths();
        h.assertTrue(c.controller().stairs().beginAscent(),"real ascent begins on lower stair");
        try{var chosen=c.controller().stairs().getClass().getDeclaredField("stairs");chosen.setAccessible(true);chosen.set(c.controller().stairs(),stair);}catch(Exception error){throw new RuntimeException(error);}
        boolean[] arrived={false};
        h.onEachTick(()->{if(!arrived[0]){c.controller().stairs().tick();arrived[0]=c.blockPosition().distManhattan(stair.top)<=2&&c.getY()>=pos.y+1;}c.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(h.getLevel().getBlockState(hole).is(Blocks.COBBLESTONE),"ascending work prioritizes missing tread repair");h.assertTrue(arrived[0]&&c.blockPosition().distManhattan(stair.top)<=2&&c.getY()>=pos.y+1,"clone reaches the ascent arrival area: "+c.blockPosition()+" "+c.controller().stairs().debug);h.assertTrue(h.getLevel().getBlockState(stair.top.relative(stair.dir).below(2)).is(Blocks.STONE),"upper intact tread was not mined away");finish(h,c,stair);});
    }

}
