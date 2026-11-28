package com.rlclones.gametest;

import com.rlclones.ai.Motor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.*;

@GameTestHolder("rlclones_recovery")
@PrefixGameTestTemplate(false)
public final class RecoveryTests {
    @AfterBatch(batch="recovery")
    public static void cleanup(ServerLevel level) {GearRecipeTests.cleanup(level);}
    private static void field(Motor motor,String name,Object value) {
        try {var f=Motor.class.getDeclaredField(name);f.setAccessible(true);f.set(motor,value);}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    private static Object call(Motor motor,String name,boolean... arg) {
        try {var method=arg.length==0?Motor.class.getDeclaredMethod(name):Motor.class.getDeclaredMethod(name,boolean.class);
            method.setAccessible(true);return arg.length==0?method.invoke(motor):method.invoke(motor,arg[0]);}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    @GameTest(template="arena",batch="recovery",timeoutTicks=180)
    public static void realMovementRecordsRecoveryAndReportsActualEvidence(GameTestHelper h) {
        var c=GearRecipeTests.clone(h);var motor=c.controller().motor();var goal=h.absoluteVec(new Vec3(14.5,2,7.5));
        for(var p:BlockPos.betweenClosed(h.absolutePos(new BlockPos(2,1,2)),h.absolutePos(new BlockPos(15,1,14))))h.getLevel().setBlock(p,Blocks.STONE.defaultBlockState(),3);
        for(var p:BlockPos.betweenClosed(h.absolutePos(new BlockPos(2,2,2)),h.absolutePos(new BlockPos(15,3,14))))h.getLevel().setBlock(p,Blocks.WATER.defaultBlockState(),3);
        h.onEachTick(()->{motor.navigate(goal,0.5,false);motor.tick();});
        h.runAtTickTime(5,()->{field(motor,"escapeBase",new Vec3(1,0,0));call(motor,"beginRecovery");});
        h.succeedWhen(()->{
            h.assertTrue(c.getCloneBrain().recovery.trials()>0,"actual recovery endpoint adds learning evidence: "+motor.recoveryReport());
            h.assertTrue(c.getCloneBrain().copy().recovery.trials()==c.getCloneBrain().recovery.trials(),"evidence survives brain save/load copy");
            h.assertTrue(c.getCloneBrain().summary().stream().anyMatch(s->s.contains("水中の立て直し")),"brain command exposes real evidence");
        });
    }
    @GameTest(template="arena",batch="recovery",timeoutTicks=40)
    public static void lowAirOverridesLearningWithoutRewardingForcedAction(GameTestHelper h) {
        var c=GearRecipeTests.clone(h);var motor=c.controller().motor();c.setAirSupply(0);
        field(motor,"escapeBase",new Vec3(1,0,0));call(motor,"beginRecovery");
        h.assertTrue(!motor.recoveryReport().contains("active=true"),"forced emergency maneuver is not a learning trial");
        h.assertTrue(c.getCloneBrain().recovery.trials()==0,"no reward before outcome");
        try {var f=Motor.class.getDeclaredField("escapeAction");f.setAccessible(true);h.assertTrue(f.getInt(motor)==2,"low air forces upward escape");}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
        h.succeed();
    }
    @GameTest(template="arena",batch="recovery",timeoutTicks=40)
    public static void negativePathMemoryChecksEveryVerticalSection(GameTestHelper h) {
        var level=h.getLevel();
        var memory=new com.rlclones.ai.util.UnreachableMemory();
        // Use the same loaded column, but a different section above the arena.
        var from=h.absolutePos(new BlockPos(7,2,7));
        var target=from.above(20);
        long now=level.getGameTime();
        memory.remember(level,from,target,now,80);
        h.assertTrue(memory.blocked(level,from,target,now),"unchanged path failure can be reused");
        var old=level.getBlockState(target);
        try {
            level.setBlock(target,old.is(Blocks.STONE)?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState(),2);
            h.assertTrue(!memory.blocked(level,from,target,now),"a changed upper section invalidates the cached failure");
            memory.remember(level,from,target,now,80);
            h.assertTrue(memory.blocked(level,from,target,now),"new terrain is remembered");
            h.assertTrue(!memory.blocked(level,from,target,now+80),"original expiry boundary is preserved");
        } finally {
            level.setBlock(target,old,2);
        }
        h.succeed();
    }
    @GameTest(template="arena",batch="recovery",timeoutTicks=40)
    public static void strategyDecisionMatchesIndependentQueries(GameTestHelper h) {
        var c=GearRecipeTests.clone(h);
        var observer=c.controller().perception();
        var brain=c.getCloneBrain();
        long now=h.getLevel().getGameTime();
        c.getInventory().clearContent();
        var ore=h.absolutePos(new BlockPos(9,2,7));
        var old=h.getLevel().getBlockState(ore);
        try {
            for(int i=0;i<6;i++) {
                boolean food=(i&1)!=0;
                c.getFoodData().setFoodLevel(i<3?10:20);
                c.getInventory().setItem(0,food?new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD,3):net.minecraft.world.item.ItemStack.EMPTY);
                h.getLevel().setBlock(ore,food?Blocks.COAL_ORE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
                if(food)observer.noteBlock(ore);else observer.forgetBlock(ore);
                int expectedState=com.rlclones.ai.Senses.strategyState(observer,c,c,brain,now);
                int expectedMask=com.rlclones.ai.Senses.strategyMask(observer,c,c,now);
                var decision=com.rlclones.ai.Senses.strategyDecision(observer,c,c,brain,now);
                h.assertTrue(expectedState==decision.state(),"paired strategy state matches independent evaluation");
                h.assertTrue(expectedMask==decision.mask(),"paired strategy mask matches independent evaluation");
            }
        } finally {
            observer.forgetBlock(ore);
            h.getLevel().setBlock(ore,old,2);
        }
        h.succeed();
    }
    @GameTest(template="arena",batch="recovery",timeoutTicks=40)
    public static void changingDestinationDoesNotRewardUnrelatedProgress(GameTestHelper h) {
        var c=GearRecipeTests.clone(h);var motor=c.controller().motor();var goal=c.position().add(7,0,0);
        motor.navigate(goal,0.5,false);field(motor,"escapeBase",new Vec3(1,0,0));call(motor,"beginRecovery");
        h.runAtTickTime(10,()->{
            motor.navigate(c.position().add(-7,0,0),0.5,false);call(motor,"finishRecovery",false);
            h.assertTrue(c.getCloneBrain().recovery.trials()==0,"new goal cannot give credit to an earlier trial");h.succeed();
        });
    }
}
