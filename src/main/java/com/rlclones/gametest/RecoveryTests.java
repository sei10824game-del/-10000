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
    public static void bestWeaponOfKindPreservesLiveScoresAndTies(GameTestHelper h) {
        var c=GearRecipeTests.clone(h);
        var inv=c.getInventory();
        inv.clearContent();
        var items=new net.minecraft.world.item.Item[]{
            net.minecraft.world.item.Items.WOODEN_SWORD,
            net.minecraft.world.item.Items.IRON_SWORD,
            net.minecraft.world.item.Items.IRON_SWORD,
            net.minecraft.world.item.Items.DIAMOND_AXE,
            net.minecraft.world.item.Items.STONE_AXE};
        for(int i=0;i<items.length;i++)inv.setItem(i,new net.minecraft.world.item.ItemStack(items[i]));
        for(int step=0;step<3;step++) {
            for(Class<?> kind:new Class<?>[]{net.minecraft.world.item.SwordItem.class,net.minecraft.world.item.AxeItem.class,net.minecraft.world.item.BowItem.class}) {
                int best=-1;
                double bestScore=0;
                for(int i=0;i<inv.items.size();i++) {
                    var stack=inv.items.get(i);
                    if(!stack.isEmpty()&&kind.isInstance(stack.getItem())&&com.rlclones.ai.Equipment.attackDamage(stack)>bestScore) {
                        bestScore=com.rlclones.ai.Equipment.attackDamage(stack);
                        best=i;
                    }
                }
                h.assertTrue(best==com.rlclones.ai.Equipment.bestOfKind(c,kind),"same result as original selection including ties and empty inventory");
            }
            if(step==0)inv.getItem(2).enchant(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS,1);
            if(step==1)inv.clearContent();
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
