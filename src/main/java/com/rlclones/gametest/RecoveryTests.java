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
    public static void movementHazardSamplesMatchOriginalTraversal(GameTestHelper h) {
        var c=GearRecipeTests.clone(h);var motor=c.controller().motor();
        var level=h.getLevel();
        var lava=c.blockPosition().south();
        var previous=level.getBlockState(lava);
        try {
            level.setBlock(lava,Blocks.LAVA.defaultBlockState(),2);
            var hazard=Motor.class.getDeclaredMethod("hazardAhead",float.class,float.class,float.class);
            var drop=Motor.class.getDeclaredMethod("deadlyDropAhead",float.class,float.class,float.class);
            hazard.setAccessible(true);drop.setAccessible(true);
            for(float yaw=0;yaw<360;yaw+=15) for(float forward:new float[]{-1,0,1}) for(float side:new float[]{-1,0,1}) {
                double rad=Math.toRadians(yaw);
                double mx=-Math.sin(rad)*forward+Math.cos(rad)*side;
                double mz=Math.cos(rad)*forward+Math.sin(rad)*side;
                double len=Math.sqrt(mx*mx+mz*mz);
                boolean expectedHazard=false,expectedDrop=false;
                if(len>=1e-4) {
                    mx/=len;mz/=len;
                    for(double ahead:new double[]{0.6,1.1}) {
                        var feet=BlockPos.containing(c.getX()+mx*ahead,c.getY()+0.01,c.getZ()+mz*ahead);
                        for(int dy=0;dy<=1;dy++) {
                            var pos=feet.above(dy);
                            expectedHazard |= level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)
                                    ||level.getBlockState(pos).is(net.minecraft.tags.BlockTags.FIRE);
                        }
                    }
                    for(double ahead:new double[]{0.5,0.9})
                        expectedDrop |= motor.dropAt(c.getX()+mx*ahead,c.getZ()+mz*ahead)>=4+(c.getHealth()>10?1:0);
                }
                h.assertTrue(expectedHazard==(boolean)hazard.invoke(motor,yaw,forward,side),"hazard sample mismatch");
                h.assertTrue(expectedDrop==(boolean)drop.invoke(motor,yaw,forward,side),"drop sample mismatch");
            }
        } catch(ReflectiveOperationException e) {throw new RuntimeException(e);}
        finally {level.setBlock(lava,previous,2);}
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
