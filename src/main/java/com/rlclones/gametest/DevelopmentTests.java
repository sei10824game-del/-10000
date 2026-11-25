package com.rlclones.gametest;

import com.rlclones.ai.*;
import com.rlclones.ai.strategy.Option;
import com.rlclones.ai.civilization.*;
import com.rlclones.ai.observe.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.*;

@GameTestHolder("rlclones_development")
@PrefixGameTestTemplate(false)
public final class DevelopmentTests {
    @AfterBatch(batch="development")public static void cleanup(ServerLevel level){GearRecipeTests.cleanup(level);}
    private static Object field(Object object,String name){try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    private static DevelopmentLearning development(com.rlclones.clone.ClonePlayer clone){return (DevelopmentLearning)field(clone.controller(),"development");}
    private static void floor(GameTestHelper h){for(var p:BlockPos.betweenClosed(h.absolutePos(new BlockPos(4,1,4)),h.absolutePos(new BlockPos(12,1,12))))h.getLevel().setBlock(p,Blocks.STONE.defaultBlockState(),3);}
    /** Make lighting-preparation tests independent of accumulated world time and surface terrain. */
    private static void darkRoom(GameTestHelper h,com.rlclones.clone.ClonePlayer c){
        for(var p:BlockPos.betweenClosed(new BlockPos(4,1,4),new BlockPos(12,5,10))) {
            boolean shell=p.getY()==1||p.getY()==5||p.getX()==4||p.getX()==12||p.getZ()==4||p.getZ()==10;
            h.setBlock(p,shell?Blocks.STONE:Blocks.AIR);
        }
        Vec3 p=h.absoluteVec(new Vec3(7.5,2,7.5));c.teleportTo(p.x,p.y,p.z);
    }
    @GameTest(template="arena",batch="development",timeoutTicks=240)
    public static void lightPreparationAndActualMiningProduceOwnEvidence(GameTestHelper h){
        var c=GearRecipeTests.clone(h);darkRoom(h,c);var d=development(c);c.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));c.getInventory().setItem(1,new ItemStack(Items.TORCH,8));
        var ore=h.absolutePos(new BlockPos(9,2,7));h.getLevel().setBlock(ore,Blocks.IRON_ORE.defaultBlockState(),3);
        for(int s=0;s<8;s++)for(int a=0;a<4;a++)c.getCloneBrain().workflow.record(s,a,a==1?1:-1,false);
        ((java.util.Random)field(d,"random")).setSeed(1);
        h.runAtTickTime(5,()->{c.setOnGround(true);d.start(Option.MINE);});
        h.onEachTick(()->{if(h.getTick()<6)return;d.prepareTick();c.controller().motor().mine(ore);c.controller().motor().tick();if(h.getLevel().getBlockState(ore).isAir())d.finish(false,true);});
        h.succeedWhen(()->{h.assertTrue(c.getCloneBrain().workflow.wins()==1,"completed real mining makes preparation experience");h.assertTrue(c.getInventory().countItem(Items.TORCH)==7,"lighting method really placed a torch before mining");});
    }
    @GameTest(template="arena",batch="development",timeoutTicks=160)
    public static void toolPreparationCraftsWithoutPretendingWorkIsDone(GameTestHelper h){
        var c=GearRecipeTests.clone(h);floor(h);GearRecipeTests.table(h,c);var d=development(c);
        c.getInventory().setItem(0,new ItemStack(Items.WOODEN_PICKAXE));c.getInventory().setItem(1,new ItemStack(Items.COBBLESTONE,3));c.getInventory().setItem(2,new ItemStack(Items.STICK,2));
        for(int s=0;s<8;s++)for(int a=0;a<4;a++)c.getCloneBrain().workflow.record(s,a,a==3?1:-1,false);
        ((java.util.Random)field(d,"random")).setSeed(1);d.start(Option.MINE);
        h.onEachTick(()->{d.prepareTick();c.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(c.getInventory().countItem(Items.STONE_PICKAXE)==1,"tool preparation uses real crafting");d.finish(false,true);h.assertTrue(c.getCloneBrain().workflow.wins()==0,"preparation alone is not mined resources");});
    }
    @GameTest(template="arena",batch="development",timeoutTicks=50)
    public static void optionalRolesRespectMasksAndCancelInterruptions(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var d=development(c);CivicWork.post(c,"minecraft:iron_ingot",4);
        int mask=Option.HUNT.bit()|Option.MINE.bit();var choice=d.roleChoice(mask,h.getLevel().getGameTime());
        h.assertTrue(choice==Option.HUNT||choice==Option.MINE,"only executable, needed roles are selected");d.finish(false,false);
        h.assertTrue(c.getCloneBrain().roles.trials()==0,"interruption does not invent failure or success");
        c.setHealth(2);h.assertTrue(d.roleChoice(mask,h.getLevel().getGameTime())==null,"emergency health disables optional role exploration");h.succeed();
    }
    @GameTest(template="arena",batch="development",timeoutTicks=40)
    public static void nearbyThreatKeepsOptionalRolesOutOfCombatDecisions(GameTestHelper h){
        var c=GearRecipeTests.clone(h);darkRoom(h,c);var hostile=h.spawn(EntityType.HUSK,new Vec3(10,2,7));hostile.setNoAi(true);
        h.runAtTickTime(5,()->{
        c.controller().perception().hear(hostile,hostile.position(),h.getLevel().getGameTime());
        h.assertTrue(!Senses.threats(c.controller().perception(),c,h.getLevel().getGameTime(),24).isEmpty(),"hostile is known to the clone");
        h.assertTrue(development(c).roleChoice(Option.HUNT.bit()|Option.MINE.bit(),h.getLevel().getGameTime())==null,"optional roles must not replace combat decisions");
        c.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));c.getInventory().setItem(1,new ItemStack(Items.TORCH,8));
        for(int s=0;s<8;s++)for(int a=0;a<4;a++)c.getCloneBrain().workflow.record(s,a,a==1?1:-1,false);
        var d=development(c);((java.util.Random)field(d,"random")).setSeed(1);d.start(Option.MINE);
        h.assertTrue(d.interruptPreparation(h.getLevel().getGameTime()),"a new threat interrupts optional preparation immediately");d.finish(false,false);
        h.assertTrue(c.getCloneBrain().workflow.wins()==0,"interrupted preparation has no fabricated success");hostile.discard();h.succeed();
        });
    }
    @GameTest(template="arena",batch="development",timeoutTicks=200)
    public static void watchedSuccessIsAHintUntilSelfVerification(GameTestHelper h){
        var observer=GearRecipeTests.clone(h);var actor=GearRecipeTests.clone(h);floor(h);
        for(var p:BlockPos.betweenClosed(h.absolutePos(new BlockPos(4,2,6)),h.absolutePos(new BlockPos(11,5,8))))h.getLevel().setBlock(p,Blocks.AIR.defaultBlockState(),3);
        observer.setCloneBrain(new com.rlclones.ai.brain.Brain());actor.setCloneBrain(new com.rlclones.ai.brain.Brain());
        observer.teleportTo(h.getLevel(),h.absoluteVec(new Vec3(5.5,2,7.5)).x,h.absoluteVec(new Vec3(5.5,2,7.5)).y,h.absoluteVec(new Vec3(5.5,2,7.5)).z,-90,0);
        actor.teleportTo(h.getLevel(),h.absoluteVec(new Vec3(9.5,2,7.5)).x,h.absoluteVec(new Vec3(9.5,2,7.5)).y,h.absoluteVec(new Vec3(9.5,2,7.5)).z,0,0);
        observer.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,500));
        boolean[] published={false};
        h.onEachTick(()->{
            observer.setYRot(-90);observer.setYHeadRot(-90);observer.setXRot(0);
            if(!published[0]&&h.getTick()>=30&&observer.controller().perception().isVisible(actor)&&observer.controller().watcher().watching()>0){
                observer.controller().watcher().update(h.getLevel().getGameTime()); // deliberately observe BEFORE publication in this same tick
                DevelopmentLearning.publish(actor,1,3,2,1,true);published[0]=true;
            }
        });
        h.succeedWhen(()->{h.assertTrue((observer.getCloneBrain().evidence.suggested(1,3)&4)!=0,"visible teammate's successful method is remembered; published="+published[0]+" visible="+observer.controller().perception().isVisible(actor)+" watching="+observer.controller().watcher().watching());h.assertTrue(observer.getCloneBrain().oreWork.trials()==0&&observer.getCloneBrain().oreWork.wins()==0,"observing does not add own reward");});
    }
    @GameTest(template="arena",batch="development",timeoutTicks=220)
    public static void oneActualFoodConsumptionGetsObservedAndSaved(GameTestHelper h){
        var c=GearRecipeTests.clone(h);floor(h);c.getFoodData().setFoodLevel(12);c.getInventory().setItem(0,new ItemStack(Items.BREAD,8));c.controller().forcedOption=Option.EAT;c.setAiEnabled(true);
        h.succeedWhen(()->{h.assertTrue(c.getCloneBrain().itemTrials.known("food|minecraft:bread"),"actual completed meal has a trial outcome");h.assertTrue(c.getCloneBrain().copy().itemTrials.known("food|minecraft:bread"),"food evidence is saved");h.assertTrue(c.getInventory().countItem(Items.BREAD)+c.getOffhandItem().getCount()==7,"only one bread was consumed during the observation");});
    }
    @GameTest(template="arena",batch="development",timeoutTicks=180)
    public static void equippedArmorIsObservedWithoutSeekingDamage(GameTestHelper h){
        var c=GearRecipeTests.clone(h);floor(h);c.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));c.controller().forcedOption=Option.REST;c.setAiEnabled(true);
        h.succeedWhen(()->{h.assertTrue(c.getCloneBrain().itemTrials.known("gear|minecraft:iron_helmet"),"normal armor use is observed");h.assertTrue(c.getHealth()==c.getMaxHealth(),"armor experiment does not require taking damage");});
    }
    @GameTest(template="arena",batch="development",timeoutTicks=240)
    public static void warehousePreparationMovesActualSurplus(GameTestHelper h){
        var c=GearRecipeTests.clone(h);floor(h);var d=development(c);c.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));for(int slot=10;slot<15;slot++)c.getInventory().setItem(slot,new ItemStack(Items.PAPER,10));
        var pos=h.absolutePos(new BlockPos(9,2,9));h.getLevel().setBlock(pos,Blocks.CHEST.defaultBlockState(),3);var chest=(net.minecraft.world.Container)h.getLevel().getBlockEntity(pos);
        var bases=com.rlclones.clone.Bases.get(h.getLevel().getServer());var base=bases.add(h.getLevel().dimension(),pos,"prep-test");bases.addChest(base,pos);bases.record(h.getLevel().dimension(),pos,chest);
        for(int s=0;s<8;s++)for(int a=0;a<4;a++)c.getCloneBrain().workflow.record(s,a,a==2?1:-1,false);
        ((java.util.Random)field(d,"random")).setSeed(1);d.start(Option.MINE);h.onEachTick(()->{d.prepareTick();c.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(chest.countItem(Items.PAPER)==50&&c.getInventory().countItem(Items.PAPER)==0,"preparation deposits real surplus into a known chest: "+d.report()+" canStore="+c.controller().storage().canStore()+" paper="+c.getInventory().countItem(Items.PAPER));d.finish(false,true);h.assertTrue(c.getCloneBrain().workflow.wins()==0,"depositing is not mining completion");bases.bases.remove(base);});
    }
    @GameTest(template="arena",batch="development",timeoutTicks=300)
    public static void deliveryRoleLearnsOnlyAfterRecipientGetsCargo(GameTestHelper h){
        var courier=GearRecipeTests.clone(h);var recipient=GearRecipeTests.clone(h);floor(h);var d=development(courier);
        recipient.teleportTo(h.getLevel(),courier.getX()+2,courier.getY(),courier.getZ(),0,0);courier.getInventory().add(new ItemStack(Items.BREAD,12));
        CivicWork.post(recipient,WorkBoard.FOOD,4);CivicWork.post(recipient,"minecraft:iron_ingot",2);
        for(int s=0;s<8;s++)for(int a=0;a<4;a++)courier.getCloneBrain().roles.record(s,a,a==3?1:-1,false);
        ((java.util.Random)field(d,"random")).setSeed(1);
        h.assertTrue(d.roleChoice(Option.MINE.bit()|Option.FEED.bit(),h.getLevel().getGameTime())==Option.FEED,"shortage and experience select delivery role");
        h.assertTrue(courier.controller().civicWork().begin(),"real delivery accepts the request");
        h.onEachTick(()->{courier.controller().civicWork().tick();courier.controller().motor().tick();if(courier.controller().civicWork().completed>0)d.finish(false,true);});
        h.succeedWhen(()->{h.assertTrue(courier.getCloneBrain().roles.wins()==1,"recipient-confirmed completion rewards delivery role");h.assertTrue(FoodAid.foodItems(recipient)>=4,"recipient actually received the food");});
    }
    @GameTest(template="arena",batch="development",timeoutTicks=230)
    public static void harmfulChangeAfterMealPreventsAnotherTrial(GameTestHelper h){
        var c=GearRecipeTests.clone(h);floor(h);c.getFoodData().setFoodLevel(12);c.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.BREAD,8));c.getInventory().setItem(0,new ItemStack(Items.COOKED_BEEF,4));c.controller().forcedOption=Option.EAT;c.setAiEnabled(true);
        h.runAtTickTime(70,()->c.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,120)));
        h.succeedWhen(()->{h.assertTrue(c.getCloneBrain().itemTrials.harmful("food|minecraft:bread"),"negative observed outcome is retained for the tested food");h.assertTrue(!((SafeItemTrials)field(c.controller(),"safeTrials")).beforeOffhandFood(),"same harmful food is not retried while not starving");});
    }
    @GameTest(template="arena",batch="development",timeoutTicks=40)
    public static void knownHarmfulFoodAndUnsafeWeaponExperimentsAreAvoided(GameTestHelper h){
        var c=GearRecipeTests.clone(h);c.getInventory().setItem(0,new ItemStack(Items.BREAD,8));c.getInventory().setItem(1,new ItemStack(Items.COOKED_BEEF,4));
        c.getCloneBrain().itemTrials.record("food|minecraft:bread",-1,true);c.getFoodData().setFoodLevel(12);
        h.assertTrue(Equipment.bestFoodSlot(c)==1,"learned harmful food is avoided while safe food exists");
        var zombie=h.spawn(EntityType.ZOMBIE,new Vec3(12,2,7));c.setHealth(2);
        h.assertTrue(!SafeItemTrials.canTryWeapon(c,new ItemStack(Items.IRON_SWORD),zombie,c.controller().perception()),"critical health forbids new right-click experiments");zombie.discard();h.succeed();
    }
}
