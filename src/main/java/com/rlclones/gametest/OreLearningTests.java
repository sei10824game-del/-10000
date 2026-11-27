package com.rlclones.gametest;

import com.rlclones.ai.*;
import com.rlclones.ai.strategy.Option;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;

@GameTestHolder("rlclones_ore_learning")
@PrefixGameTestTemplate(false)
public final class OreLearningTests {
    private static final java.util.List<Object> LISTENERS=new java.util.ArrayList<>();
    @AfterBatch(batch="ore-learning")
    public static void cleanup(ServerLevel level) {for(var listener:LISTENERS)MinecraftForge.EVENT_BUS.unregister(listener);LISTENERS.clear();GearRecipeTests.cleanup(level);}
    private static Object field(Object object,String name) {
        try {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    private static ClonePlayer setup(GameTestHelper h) {
        var c=GearRecipeTests.clone(h);c.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
        for(var p:BlockPos.betweenClosed(h.absolutePos(new BlockPos(5,1,5)),h.absolutePos(new BlockPos(12,1,10))))h.getLevel().setBlock(p,Blocks.STONE.defaultBlockState(),3);
        for(var p:BlockPos.betweenClosed(h.absolutePos(new BlockPos(5,2,5)),h.absolutePos(new BlockPos(12,5,10))))h.getLevel().setBlock(p,Blocks.AIR.defaultBlockState(),3);
        var high=h.absolutePos(new BlockPos(7,4,7));var low=h.absolutePos(new BlockPos(10,2,7));
        h.getLevel().setBlock(high,Blocks.IRON_ORE.defaultBlockState(),3);h.getLevel().setBlock(low,Blocks.IRON_ORE.defaultBlockState(),3);
        c.controller().perception().noteBlock(high);c.controller().perception().noteBlock(low);
        for(int state=0;state<8;state++)for(int action=0;action<4;action++)c.getCloneBrain().oreWork.record(state,action,action==1?1:-1,false);
        ((java.util.Random)field(field(c.controller(),"oreWorkLearning"),"random")).setSeed(1);
        c.controller().forcedOption=Option.MINE;
        return c;
    }
    @GameTest(template="arena",batch="ore-learning",timeoutTicks=400)
    public static void learnedMethodSelectsAndActuallyMinesItsTarget(GameTestHelper h) {
        var c=setup(h);var low=h.absolutePos(new BlockPos(10,2,7));
        c.setAiEnabled(true);
        h.succeedWhen(()->{
            h.assertTrue(c.getCloneBrain().oreWork.wins()==1,"real confirmed destruction records an ore success: "+((OreWorkLearning)field(c.controller(),"oreWorkLearning")).report()+" target="+c.controller().harvestTarget()+" trace="+c.controller().harvestTrace+" mining="+c.controller().motor().mineDebug);
            h.assertTrue(h.getLevel().getBlockState(low).isAir(),"learned low-height method chose and mined the lower ore");
            h.assertTrue(c.getCloneBrain().copy().oreWork.wins()==1,"result survives save/copy");
        });
    }
    public static final class Protection {
        int attempts; final BlockPos protectedOre;Protection(BlockPos p){protectedOre=p;}
        @SubscribeEvent public void cancel(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {if(event.getPos().equals(protectedOre)){attempts++;event.setCanceled(true);}}
    }
    @GameTest(template="arena",batch="ore-learning",timeoutTicks=300)
    public static void canceledBreakEventDoesNotCreateSuccess(GameTestHelper h) {
        var c=setup(h);var low=h.absolutePos(new BlockPos(10,2,7));
        var listener=new Protection(low);LISTENERS.add(listener);MinecraftForge.EVENT_BUS.register(listener);
        c.setAiEnabled(true);
        h.runAtTickTime(200,()->{
            h.assertTrue(listener.attempts>0,"real AI attempted the protected mining operation");
            h.assertTrue(h.getLevel().getBlockState(low).is(Blocks.IRON_ORE),"protection kept the ore in the world");
            h.assertTrue(c.getCloneBrain().oreWork.wins()==0,"pre-break events cannot manufacture successful mining experience");h.succeed();
        });
    }
    @GameTest(template="arena",batch="ore-learning",timeoutTicks=40)
    public static void unknownOreAndMissingToolsDoNotExpandLearningCandidates(GameTestHelper h) {
        var c=setup(h);var module=(OreWorkLearning)field(c.controller(),"oreWorkLearning");
        var high=h.absolutePos(new BlockPos(7,4,7));var low=h.absolutePos(new BlockPos(10,2,7));
        c.controller().perception().forgetBlock(low);
        h.assertTrue(module.select(high,24,p->true).equals(high),"unknown world ore is not an alternative");
        h.assertTrue(module.report().contains("active=false"),"one known target does not invent a method comparison");
        c.controller().perception().noteBlock(low);c.getInventory().clearContent();
        h.assertTrue(module.select(high,24,p->true).equals(high)&&module.report().contains("active=false"),"tool preparation remains with the existing planner");
        h.succeed();
    }

    @AfterBatch(batch="mining-continuity") public static void cleanContinuity(ServerLevel level){GearRecipeTests.cleanup(level);}
    private static void setField(Object o,String name,Object value){
        try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    @GameTest(template="arena",batch="mining-continuity",timeoutTicks=400)
    public static void startedLogSurvivesNormalPlannerTimeout(GameTestHelper h){
        for(var p:BlockPos.betweenClosed(new BlockPos(3,1,3),new BlockPos(12,6,12))){
            boolean shell=p.getY()==1||p.getY()==6||p.getX()==3||p.getX()==12||p.getZ()==3||p.getZ()==12;
            h.setBlock(p,shell?Blocks.STONE:Blocks.AIR);
        }
        var c=GearRecipeTests.clone(h);var feet=h.absolutePos(new BlockPos(7,2,7));
        c.teleportTo(h.getLevel(),feet.getX()+.5,feet.getY(),feet.getZ()+.5,-90,0);
        c.setYHeadRot(-90);c.setOnGround(true);
        var local=new BlockPos(9,3,7);var log=h.absolutePos(local);
        h.setBlock(local,Blocks.OAK_LOG);h.setBlock(local.above(),Blocks.OAK_LEAVES);
        c.getInventory().setItem(0,new ItemStack(Items.WOODEN_AXE));c.getInventory().selected=0;
        c.controller().perception().noteBlock(log);c.controller().forcedOption=Option.GATHER_WOOD;
        c.setAiEnabled(true);
        boolean[] stressed={false},continued={false};
        h.onEachTick(()->{
            var ctrl=c.controller();
            if(!stressed[0]&&ctrl.option()==Option.GATHER_WOOD&&ctrl.motor().miningCommitted(log)){
                setField(ctrl,"blockTicks",241);setField(ctrl,"optionTicks",Option.GATHER_WOOD.maxTicks);
                stressed[0]=true;
                var item=new net.minecraft.world.entity.item.ItemEntity(h.getLevel(),c.getX()-2,c.getY()+.2,c.getZ(),new ItemStack(Items.COBBLESTONE));
                h.getLevel().addFreshEntity(item);
            }
            if(stressed[0]&&"mining-commit".equals(ctrl.idleWhy))continued[0]=true;
        });
        h.succeedWhen(()->{
            h.assertTrue(stressed[0]&&continued[0],"committed logging outlasts routine timeout and nearby drops");
            h.assertTrue(h.getLevel().getBlockState(log).isAir(),"actual log destroyed after commitment");
        });
    }

}
