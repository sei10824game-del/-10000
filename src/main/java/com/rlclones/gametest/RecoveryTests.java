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
    public static void rangedSelectionMatchesIndependentScoring(GameTestHelper h) {
        var c=GearRecipeTests.clone(h);
        var inv=c.getInventory();
        inv.clearContent();
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW);
        var crossbow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        var trident=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TRIDENT);
        inv.setItem(0,bow); inv.setItem(1,crossbow); inv.setItem(2,trident);
        for(boolean ammo:new boolean[]{false,true}) {
            inv.setItem(3,ammo?new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW,16):net.minecraft.world.item.ItemStack.EMPTY);
            for(boolean charged:new boolean[]{false,true}) {
                net.minecraft.world.item.CrossbowItem.setCharged(crossbow,charged);
                for(int selected=0;selected<4;selected++) {
                    inv.selected=selected;
                    int best=-1,bestScore=0;
                    for(int i=0;i<inv.items.size();i++) {
                        var stack=inv.items.get(i);
                        if(!com.rlclones.ai.Equipment.canFire(c,stack))continue;
                        int score=switch(com.rlclones.ai.Equipment.rangedKind(stack)){
                            case CROSSBOW -> net.minecraft.world.item.CrossbowItem.isCharged(stack)?5:3;
                            case BOW -> 4;
                            case THROWN -> 2;
                            default -> 0;
                        };
                        if(i==selected)score++;
                        if(score>bestScore){bestScore=score;best=i;}
                    }
                    h.assertTrue(best==com.rlclones.ai.Equipment.rangedSlot(c),"ranged selection must preserve original scoring and ties");
                    c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,bow.copy());
                    h.assertTrue(com.rlclones.ai.Equipment.offhandRanged(c)==com.rlclones.ai.Equipment.canFire(c,c.getOffhandItem()),"offhand firing eligibility");
                }
            }
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
