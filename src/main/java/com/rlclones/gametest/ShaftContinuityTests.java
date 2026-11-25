package com.rlclones.gametest;

import com.rlclones.Config;
import com.rlclones.ai.ShaftMining;
import com.rlclones.clone.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("rlclones_shaft_repair")
@PrefixGameTestTemplate(false)
public final class ShaftContinuityTests {
    private static final Set<Bases.Shaft> OWNED=new HashSet<>();
    @AfterBatch(batch="shaft-continuity")
    public static void cleanup(ServerLevel level){GearRecipeTests.cleanup(level);Bases.get(level.getServer()).shafts.removeAll(OWNED);OWNED.clear();}
    @AfterBatch(batch="shaft-permission")
    public static void permissionCleanup(ServerLevel level){cleanup(level);}
    private record Fixture(ClonePlayer actor,ShaftMining mining,Bases.Shaft record,BlockPos top) {}
    private static void set(Object target,String name,Object value){try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    @AfterBatch(batch="shaft-claim")
    public static void claimCleanup(ServerLevel level){cleanup(level);}
    private static Fixture fixture(GameTestHelper h,int top,int bottom,int ladders,int stage){
        for(int y=1;y<top;y++)for(int x=6;x<=8;x++)for(int z=6;z<=8;z++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        for(int y=bottom;y<=top+2;y++)h.setBlock(new BlockPos(7,y,7),Blocks.AIR);
        var state=Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING,Direction.WEST);
        for(int y=bottom;y<top;y++)h.getLevel().setBlock(h.absolutePos(new BlockPos(7,y,7)),state,3);
        var c=GearRecipeTests.clone(h);var feet=h.absolutePos(new BlockPos(7,bottom,7));
        c.teleportTo(h.getLevel(),feet.getX()+0.5,feet.getY(),feet.getZ()+0.5,0,0);c.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
        if(ladders>0)c.getInventory().setItem(1,new ItemStack(Items.LADDER,ladders));c.getInventory().setItem(2,new ItemStack(Items.COBBLESTONE,64));c.getInventory().setItem(3,new ItemStack(Items.TORCH,32));c.getInventory().setItem(4,new ItemStack(Items.BREAD,8));
        var record=Bases.get(h.getLevel().getServer()).addShaft(h.getLevel().dimension(),h.absolutePos(new BlockPos(7,top,7)),Direction.EAST);record.end=feet;OWNED.add(record);
        var mining=c.controller().shafts();mining.assumeSurface=true;mining.targetY=h.absolutePos(new BlockPos(7,1,7)).getY();set(mining,"shaft",record);set(mining,"stage",stage);
        return new Fixture(c,mining,record,record.top);
    }
    private static void tick(Fixture f){f.mining.tick();f.actor.controller().motor().tick();}
    private static boolean ladder(GameTestHelper h,int y){return h.getLevel().getBlockState(h.absolutePos(new BlockPos(7,y,7))).is(Blocks.LADDER);}
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=120)
    public static void repairsConsecutiveGapsAndTheirMissingWall(GameTestHelper h){
        var f=fixture(h,12,4,8,4);
        for(int y:new int[]{5,6}){h.setBlock(new BlockPos(7,y,7),Blocks.AIR);h.setBlock(new BlockPos(8,y,7),Blocks.AIR);}
        h.onEachTick(()->tick(f));
        h.succeedWhen(()->{h.assertTrue(ladder(h,5)&&ladder(h,6),"two consecutive rungs are repaired: "+f.mining.debug);h.assertTrue(f.mining.supportsPlaced==2,"real backing blocks were placed");h.assertTrue(f.actor.getInventory().countItem(Items.COBBLESTONE)==62&&f.actor.getInventory().countItem(Items.LADDER)==6,"repair consumed real materials");});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=80)
    public static void repairsMissingCurrentRungWithoutWaitingForLanding(GameTestHelper h){
        var f=fixture(h,12,6,4,3);h.setBlock(new BlockPos(7,6,7),Blocks.AIR);f.actor.setOnGround(false);
        h.onEachTick(()->tick(f));h.succeedWhen(()->h.assertTrue(ladder(h,6),"airborne/current rung is repaired before any deeper digging: "+f.mining.debug));
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=200)
    public static void twoLevelDropGetsBothRungsBeforePassingIntermediateLevel(GameTestHelper h){
        var f=fixture(h,8,8,2,3);h.setBlock(new BlockPos(7,6,7),Blocks.AIR);
        h.onEachTick(()->tick(f));
        h.succeedWhen(()->{h.assertTrue(ladder(h,7)&&ladder(h,6),"intermediate and landing rungs both exist: "+f.mining.debug+f.mining.trace);h.assertTrue(f.actor.getInventory().countItem(Items.LADDER)==0,"two actual ladders consumed");});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=40)
    public static void oneLadderCannotOpenATwoLevelDrop(GameTestHelper h){
        var f=fixture(h,8,8,1,3);h.setBlock(new BlockPos(7,6,7),Blocks.AIR);f.actor.setOnGround(true);f.mining.tick();
        h.assertTrue(h.getBlockState(new BlockPos(7,7,7)).is(Blocks.STONE),"old floor stays intact without both ladders");h.assertTrue(f.actor.getInventory().countItem(Items.LADDER)==1,"no fake consumption");h.succeed();
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=40)
    public static void missingWallWithoutBuildingMaterialStopsDeeperMining(GameTestHelper h){
        var f=fixture(h,12,6,4,3);h.setBlock(new BlockPos(7,6,7),Blocks.AIR);h.setBlock(new BlockPos(8,6,7),Blocks.AIR);f.actor.getInventory().setItem(2,ItemStack.EMPTY);f.actor.setOnGround(true);f.mining.tick();
        h.assertTrue(h.getBlockState(new BlockPos(7,5,7)).is(Blocks.STONE),"missing return support prevents deeper mining");h.assertTrue(!ladder(h,6)&&f.actor.getInventory().countItem(Items.LADDER)==4,"no artificial ladder or wall is created");h.succeed();
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=300)
    public static void resumedStoneLedgeCanReconnectToExistingLaddersBelow(GameTestHelper h){
        var f=fixture(h,8,4,4,2);h.setBlock(new BlockPos(7,7,7),Blocks.STONE);var top=f.top;
        f.actor.teleportTo(h.getLevel(),top.getX()+0.5,top.getY(),top.getZ()+0.5,0,0);
        h.onEachTick(()->tick(f));h.succeedWhen(()->{h.assertTrue(ladder(h,7),"stone ledge was opened and joined to the older lower ladders: "+f.mining.debug+f.mining.trace);h.assertTrue(!f.record.finished,"an older lower ladder is not mistaken for bedrock or liquid");});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=900)
    public static void repairsDistantReturnGapBeforeExtendingShaft(GameTestHelper h){
        var f=fixture(h,12,4,16,2);h.setBlock(new BlockPos(7,9,7),Blocks.AIR);
        h.onEachTick(()->{tick(f);if(!h.getBlockState(new BlockPos(7,3,7)).is(Blocks.STONE))h.assertTrue(ladder(h,9),"return route must be repaired before digging deeper");});
        h.succeedWhen(()->{h.assertTrue(ladder(h,9)&&f.record.end.getY()<h.absolutePos(new BlockPos(7,4,7)).getY(),"repaired the high gap, descended again and resumed digging: "+f.mining.debug+f.mining.trace);});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=100)
    public static void completedRoutesCanBeMendedWithoutStartingAnotherDig(GameTestHelper h){
        var f=fixture(h,12,4,8,4);f.record.finished=true;h.setBlock(new BlockPos(7,5,7),Blocks.AIR);
        h.onEachTick(()->{f.mining.repairKnownRoute();f.actor.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(ladder(h,5),"completed shaft gaps are mended in passing");h.assertTrue(f.record.finished&&h.getBlockState(new BlockPos(7,3,7)).is(Blocks.STONE),"repair neither reopens nor deepens a finished shaft");});
    }
    @GameTest(template="arena",batch="shaft-claim",timeoutTicks=40)
    public static void resumingWorkerReservesShaftBeforeDescent(GameTestHelper h){
        var f=fixture(h,8,4,8,3);f.record.digger="";f.mining.begin();
        var other=GearRecipeTests.clone(h);other.teleportTo(h.getLevel(),f.actor.getX()+2,f.actor.getY(),f.actor.getZ(),0,0);other.getInventory().setItem(1,new ItemStack(Items.LADDER,8));other.controller().shafts().begin();
        h.assertTrue(f.record.digger.equals(f.actor.getGameProfile().getName()),"reservation starts before crafting/descent");
        h.assertTrue(other.controller().shafts().shaft()!=f.record,"second worker cannot take the same shaft while it is being resumed");h.succeed();
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=240)
    public static void minesExposedWallOreWhileHoldingTheLadder(GameTestHelper h){
        var f=fixture(h,12,4,8,3);f.mining.targetY=h.absolutePos(new BlockPos(7,4,7)).getY();
        var ore=h.absolutePos(new BlockPos(6,5,7));h.getLevel().setBlock(ore,Blocks.IRON_ORE.defaultBlockState(),3);
        // An embedded wall, not a one-block shelf opening onto the outside of the test arena:
        // otherwise loot can randomly fly into the unreachable void behind the ore.
        for(int y=4;y<=7;y++)for(int z=6;z<=8;z++)h.setBlock(new BlockPos(5,y,z),Blocks.STONE);
        h.onEachTick(()->{tick(f);f.actor.setOnGround(false);});
        h.succeedWhen(()->{h.assertTrue(f.mining.oresMined>=1&&h.getLevel().getBlockState(ore).isAir(),"ore visible beside the ladder is harvested: "+f.mining.debug+" "+f.actor.controller().motor().mineDebug);h.assertTrue(ladder(h,4)&&ladder(h,5),"ore harvest keeps the ladder column intact");h.assertTrue(f.actor.getInventory().countItem(Items.RAW_IRON)>0,"real raw iron is picked up without leaving the shaft; pos="+f.actor.position()+" drops="+h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,f.actor.getBoundingBox().inflate(4)).stream().map(e->e.getItem()+"@"+e.position()).toList());});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=320)
    public static void restoresOreBearingLadderWallAndRungBeforeDiggingDeeper(GameTestHelper h){
        var f=fixture(h,12,4,8,3);f.mining.targetY=h.absolutePos(new BlockPos(7,4,7)).getY();
        set(f.mining,"ticks",18); // cross the periodic return-route check while this rung is deliberately detached
        var ore=h.absolutePos(new BlockPos(8,4,7));h.getLevel().setBlock(ore,Blocks.IRON_ORE.defaultBlockState(),3);
        h.onEachTick(()->tick(f));
        h.succeedWhen(()->{h.assertTrue(f.mining.oresMined>=1&&h.getLevel().getBlockState(ore).is(Blocks.COBBLESTONE)&&ladder(h,4),"real ore was mined and the backing/rung restored: pos="+f.actor.position()+" wall="+h.getLevel().getBlockState(ore)+" cell="+h.getBlockState(new BlockPos(7,4,7))+" inventory="+f.actor.getInventory().items+" "+f.mining.debug+" "+f.actor.controller().motor().mineDebug);h.assertTrue(h.getBlockState(new BlockPos(7,3,7)).is(Blocks.STONE),"floor was not opened during ladder-wall extraction");});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=100)
    public static void ladderWallOreIsKeptWhenRepairMaterialIsMissing(GameTestHelper h){
        var f=fixture(h,12,4,8,3);f.mining.targetY=h.absolutePos(new BlockPos(7,4,7)).getY();f.actor.getInventory().setItem(2,ItemStack.EMPTY);
        var ore=h.absolutePos(new BlockPos(8,4,7));h.getLevel().setBlock(ore,Blocks.IRON_ORE.defaultBlockState(),3);
        h.onEachTick(()->tick(f));h.runAtTickTime(40,()->{h.assertTrue(h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE)&&ladder(h,4),"no repair materials means no broken ladder wall");h.succeed();});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=100)
    public static void inaccessibleToolTierDoesNotWasteShaftOre(GameTestHelper h){
        var f=fixture(h,12,4,8,3);f.mining.targetY=h.absolutePos(new BlockPos(7,4,7)).getY();f.actor.getInventory().setItem(0,new ItemStack(Items.WOODEN_PICKAXE));
        var ore=h.absolutePos(new BlockPos(6,4,7));h.getLevel().setBlock(ore,Blocks.IRON_ORE.defaultBlockState(),3);
        h.onEachTick(()->tick(f));h.runAtTickTime(40,()->{h.assertTrue(h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE)&&f.mining.oresMined==0,"iron is left intact without a suitable pickaxe");h.succeed();});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=100)
    public static void oreWithLavaBehindItIsNotOpened(GameTestHelper h){
        var f=fixture(h,12,4,8,3);f.mining.targetY=h.absolutePos(new BlockPos(7,4,7)).getY();
        var ore=h.absolutePos(new BlockPos(6,4,7));var lava=ore.west();h.getLevel().setBlock(ore,Blocks.IRON_ORE.defaultBlockState(),3);h.getLevel().setBlock(lava,Blocks.LAVA.defaultBlockState(),3);
        h.onEachTick(()->tick(f));h.runAtTickTime(40,()->{h.assertTrue(h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE)&&f.mining.oresMined==0,"shaft ore collection must not expose lava");h.getLevel().setBlock(lava,Blocks.AIR.defaultBlockState(),3);h.succeed();});
    }
    @GameTest(template="arena",batch="shaft-continuity",timeoutTicks=240)
    public static void interruptedWallHarvestStillAllowsRungRepair(GameTestHelper h){
        var f=fixture(h,12,4,8,3);f.mining.targetY=h.absolutePos(new BlockPos(7,4,7)).getY();var ore=h.absolutePos(new BlockPos(8,4,7));h.getLevel().setBlock(ore,Blocks.IRON_ORE.defaultBlockState(),3);
        boolean[] paused={false};
        h.onEachTick(()->{if(!paused[0]){tick(f);if(!ladder(h,4)&&h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE)){f.mining.pause();paused[0]=true;}}
            else {f.mining.repairKnownRoute();f.actor.controller().motor().tick();}});
        h.succeedWhen(()->{h.assertTrue(paused[0]&&ladder(h,4),"interrupted ore harvest no longer excludes its detached rung from repair");h.assertTrue(h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE)&&h.getBlockState(new BlockPos(7,3,7)).is(Blocks.STONE),"interruption restores the route without inventing an ore success");});
    }
    @GameTest(template="arena",batch="shaft-permission",timeoutTicks=40)
    public static void disabledPlacementKeepsFloorIntact(GameTestHelper h){
        var f=fixture(h,8,8,8,3);
        // ConfigValue.set writes a watched file; an asynchronous reload can leak the test setting to later batches.
        // Override only Forge's in-memory cache for this synchronous test, then restore it without file I/O.
        Config.get(Config.ALLOW_BLOCK_PLACING,true);
        try {
            var cache=net.minecraftforge.common.ForgeConfigSpec.ConfigValue.class.getDeclaredField("cachedValue");cache.setAccessible(true);
            Object old=cache.get(Config.ALLOW_BLOCK_PLACING);
            try{cache.set(Config.ALLOW_BLOCK_PLACING,false);h.assertTrue(!Config.get(Config.ALLOW_BLOCK_PLACING,true),"placement is actually disabled in this test");f.actor.setOnGround(true);f.mining.tick();h.assertTrue(h.getBlockState(new BlockPos(7,7,7)).is(Blocks.STONE),"cannot start a rung-less hole when placing is disabled");}
            finally{cache.set(Config.ALLOW_BLOCK_PLACING,old);}
        }catch(ReflectiveOperationException e){throw new RuntimeException(e);}h.succeed();
    }
    @GameTest(template="arena",batch="shaft-legacy-new",timeoutTicks=3000)
    public static void originalNewShaftStillCraftsAndDigs(GameTestHelper h){CloneGameTests.digsAShaftStraightDownWithLadders(h);}
    @GameTest(template="arena",batch="shaft-legacy-resume",timeoutTicks=3000)
    public static void originalShaftResumeStillWorks(GameTestHelper h){CloneGameTests.carriesOnDownAShaftAlreadyDug(h);}
}
