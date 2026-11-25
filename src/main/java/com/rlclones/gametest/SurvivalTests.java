package com.rlclones.gametest;

import com.rlclones.ai.*;
import com.rlclones.ai.brain.*;
import com.rlclones.ai.strategy.Option;
import com.rlclones.clone.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.*;

@GameTestHolder("rlclones_survival")
@PrefixGameTestTemplate(false)
public final class SurvivalTests {
    private static final java.util.Set<java.util.UUID> MOBS=new java.util.HashSet<>();
    @AfterBatch(batch="survival") public static void cleanup(ServerLevel level){GearRecipeTests.cleanup(level);for(var id:MOBS){var e=level.getEntity(id);if(e!=null)e.discard();}MOBS.clear();}
    @AfterBatch(batch="survival-live") public static void liveCleanup(ServerLevel level){cleanup(level);}
    private static net.minecraft.world.entity.monster.Zombie zombie(GameTestHelper h,BlockPos p){var z=h.spawn(EntityType.ZOMBIE,p);MOBS.add(z.getUUID());return z;}
    private static void set(Object o,String name,Object v){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new RuntimeException(e);}}
    private static Object call(Object o,String name,Class<?>[] types,Object... args){try{var m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new RuntimeException(e);}}
    @GameTest(template="arena",batch="survival",timeoutTicks=140)
    public static void actualDamageDrivesRetreatAndDoesNotImmediatelyReengage(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var z=zombie(h,new BlockPos(10,2,7));z.setNoAi(true);c.setNoGravity(true);
        h.runAtTickTime(80,()->{
        h.assertTrue(c.hurt(h.getLevel().damageSources().mobAttack(z),8),"real damage applies");
        long now=h.getLevel().getGameTime();var s=c.controller().survival();
        h.assertTrue(s.retreat(now,1,false),"losing actual exchange initiates retreat before critical health");
        h.assertTrue(s.retreat(now+101,1,false),"hold remains after attack window expires");
        c.setHealth(c.getMaxHealth());h.assertTrue(!s.retreat(now+102,1,false),"recovery permits work/combat again");
        h.assertTrue(!s.retreat(now+102,0,true),"no enemy does not indefinitely force fleeing");h.succeed();
        });
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=40)
    public static void winningExchangeDoesNotForcePassiveBehavior(GameTestHelper h){
        h.assertTrue(!Survival.losing(18,20,2,12,1,8,false),"healthy winning combat continues");
        h.assertTrue(Survival.losing(12,20,5,1,1,8,false),"high incoming damage triggers withdrawal");
        h.assertTrue(Survival.losing(16,20,1,3,3,0,false),"unarmored crowd pressure matters");h.succeed();
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=140)
    public static void fightInterruptSelectsFleeFromActualThreat(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var z=zombie(h,new BlockPos(10,2,7));z.setNoAi(true);c.setNoGravity(true);
        h.runAtTickTime(80,()->{
        c.hurt(h.getLevel().damageSources().mobAttack(z),8);c.controller().perception().hear(z,z.position(),h.getLevel().getGameTime());
        set(c.controller(),"option",Option.FIGHT);set(c.controller(),"optionTicks",20);
        h.assertTrue((boolean)call(c.controller(),"shouldInterrupt",new Class[]{long.class},h.getLevel().getGameTime()),"fight interrupted while damage exchange is losing");
        call(c.controller(),"startOption",new Class[]{long.class},h.getLevel().getGameTime());
        h.assertTrue(c.controller().option()==Option.FLEE,"safety choice enters FLEE rather than another attack");h.succeed();
        });
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=140)
    public static void threatLearnsRealDamageAndSourceAttributedHarmfulEffect(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var z=zombie(h,new BlockPos(9,2,7));z.setNoAi(true);c.setNoGravity(true);
        h.runAtTickTime(80,()->{
        c.controller().perception().hear(z,z.position(),h.getLevel().getGameTime());
        c.hurt(h.getLevel().damageSources().mobAttack(z),7);
        var k=c.getCloneBrain().knowledge(Perception.typeId(z));double before=k.dps();
        c.addEffect(new MobEffectInstance(MobEffects.POISON,100),z);
        h.assertTrue(k.receivedDamage.known()&&k.receivedDamage.get()>0,"actual post-armor damage learned");
        h.assertTrue(k.effects.containsKey("minecraft:poison")&&k.dps()>before,"actual source-attributed poison increases threat");
        var copy=Brain.load(c.getCloneBrain().save());h.assertTrue(copy.knowledge(Perception.typeId(z)).receivedDamage.known(),"threat evidence persists");h.succeed();
        });
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=40)
    public static void retreatLearnsSurvivalNotDistanceAndCancelsInterruptions(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var s=c.controller().survival();var b=c.getCloneBrain();long now=h.getLevel().getGameTime();
        Vec3[] routes={c.position().add(8,0,0),null,null,null};s.choose(routes,0,now);
        for(int i=1;i<=120;i++)s.tick(now+i,true);
        h.assertTrue(b.retreat.trials()==1&&b.retreat.wins()==0,"distance/timeout with enemies is not a win");
        s.choose(routes,0,now+121);c.teleportTo(c.getX()+2,c.getY(),c.getZ());for(int i=1;i<=40;i++)s.tick(now+121+i,false);
        h.assertTrue(b.retreat.trials()==2&&b.retreat.wins()==1,"40 safe ticks without injury confirms survival");
        s.choose(routes,0,now+162);s.cancel();s.tick(now+400,false);
        h.assertTrue(b.retreat.trials()==2,"interruption awards no fabricated outcome");
        var copy=b.copy();h.assertTrue(copy.retreat.trials()==2,"copy/save retains independent retreat table");copy.retreat.record(0,0,-1,false);
        h.assertTrue(b.retreat.trials()==2,"copy does not alias");b.absorb(copy);h.assertTrue(b.retreat.trials()==5,"sharing combines observations");h.succeed();
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=40)
    public static void criticalHealthDoesNotExploreAndDeathIsFailure(GameTestHelper h){
        var c=GearRecipeTests.clone(h);c.setHealth(5);var s=c.controller().survival();long now=h.getLevel().getGameTime();
        Vec3 cover=c.position().add(0,0,6);s.choose(new Vec3[]{c.position().add(6,0,0),cover,null,null},1,now);
        s.died();h.assertTrue(c.getCloneBrain().retreat.samples(1,1)==1&&c.getCloneBrain().retreat.wins()==0&&c.getCloneBrain().retreat.value(1,1)==-2,"critical state selects validated cover; death is not success");h.succeed();
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=40)
    public static void dangerousDestinationIsExcluded(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var spot=c.position().add(5,0,0);var p=BlockPos.containing(spot);
        h.getLevel().setBlock(p,Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(p.below(),Blocks.LAVA.defaultBlockState(),3);
        h.assertTrue(!(boolean)call(c.controller(),"retreatGoalSafe",new Class[]{Vec3.class,java.util.List.class},spot,java.util.List.of()),"lava route excluded before learning");h.getLevel().setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);h.succeed();
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=40)
    public static void deepMineRequiresFoodLightAndRepairReserves(GameTestHelper h){
        var c=GearRecipeTests.clone(h);c.getFoodData().setFoodLevel(14);
        h.assertTrue(MiningSafety.returnReason(c,20).contains("food"),"return before exhausting food");c.getInventory().setItem(0,new ItemStack(Items.BREAD,4));
        h.assertTrue(MiningSafety.returnReason(c,20).contains("material"),"deep return repair material required");c.getInventory().setItem(1,new ItemStack(Items.COBBLESTONE,16));
        h.assertTrue(MiningSafety.returnReason(c,20).contains("lighting"),"dark deep mines need lighting");c.getInventory().setItem(2,new ItemStack(Items.TORCH,8));
        h.assertTrue(MiningSafety.returnReason(c,20).isEmpty(),"prepared miners continue rather than all becoming idle");h.succeed();
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=40)
    public static void injuredShaftDiggerClimbsWithoutOpeningFloor(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var feet=c.blockPosition();var bases=Bases.get(h.getLevel().getServer());var record=bases.addShaft(h.getLevel().dimension(),feet.above(8),Direction.EAST);record.end=feet;
        for(int y=0;y<8;y++){var cell=feet.above(y);h.getLevel().setBlock(cell.east(),Blocks.STONE.defaultBlockState(),3);h.getLevel().setBlock(cell,Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING,Direction.WEST),3);}
        h.getLevel().setBlock(feet.below(),Blocks.STONE.defaultBlockState(),3);c.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));c.getInventory().setItem(1,new ItemStack(Items.LADDER,16));c.getInventory().setItem(2,new ItemStack(Items.COBBLESTONE,16));c.setHealth(8);
        var mining=c.controller().shafts();set(mining,"shaft",record);set(mining,"stage",3);mining.targetY=feet.getY()-20;mining.tick();
        h.assertTrue(h.getLevel().getBlockState(feet.below()).is(Blocks.STONE)&&mining.debug.contains("injured"),"injured digger returns on existing route before mining floor");bases.shafts.remove(record);h.succeed();
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=40)
    public static void idleAndInjuredEscapeDoNotFabricateSurvivalSuccess(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var s=c.controller().survival();long now=h.getLevel().getGameTime();Vec3[] routes={c.position().add(8,0,0),null,null,null};
        s.choose(routes,0,now);for(int i=1;i<=40;i++)s.tick(now+i,false);
        h.assertTrue(c.getCloneBrain().retreat.wins()==0,"standing still in safety is not a successful retreat");
        s.choose(routes,0,now+41);c.teleportTo(c.getX()+2,c.getY(),c.getZ());s.damage(null,8,now+42);for(int i=1;i<=40;i++)s.tick(now+41+i,false);
        h.assertTrue(c.getCloneBrain().retreat.wins()==0,"large injury despite movement is not success");
        h.assertTrue(Brain.load(new net.minecraft.nbt.CompoundTag()).retreat.trials()==0,"old brain without new table remains readable");
        var old=c.getCloneBrain();long trials=old.retreat.trials();s.choose(routes,0,now+90);c.setCloneBrain(new Brain());s.died();
        h.assertTrue(old.retreat.trials()==trials&&c.getCloneBrain().retreat.trials()==0,"brain switch cancels even if followed immediately by death");h.succeed();
    }
    @GameTest(template="arena",batch="survival",timeoutTicks=40)
    public static void injuredStairDiggerReturnsBeforeExtending(GameTestHelper h){
        var c=GearRecipeTests.clone(h);var feet=c.blockPosition();var bases=Bases.get(h.getLevel().getServer());var record=bases.addStaircase(h.getLevel().dimension(),feet.above(8).west(8),Direction.EAST);record.end=feet;
        var mining=c.controller().stairs();set(mining,"stairs",record);set(mining,"stage",2);c.setHealth(8);mining.tick();
        h.assertTrue((boolean)field(mining,"ascending"),"injured stair miner returns on recorded stairs");bases.staircases.remove(record);h.succeed();
    }
    private static Object field(Object o,String name){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}

    @GameTest(template="arena",batch="survival-live",timeoutTicks=220)
    public static void normalControllerEscapesAndRecordsActualSafeOutcome(GameTestHelper h){
        for(var p:BlockPos.betweenClosed(new BlockPos(1,1,1),new BlockPos(15,1,15)))h.setBlock(p,Blocks.STONE);
        for(var p:BlockPos.betweenClosed(new BlockPos(1,2,1),new BlockPos(15,4,15)))h.setBlock(p,Blocks.AIR);
        var c=GearRecipeTests.clone(h);var start=h.absoluteVec(new Vec3(11.5,2,7.5));c.teleportTo(start.x,start.y,start.z);c.setHealth(20);
        var z=zombie(h,new BlockPos(14,2,7));z.setNoAi(true);z.setInvulnerable(true);c.controller().perception().hear(z,z.position(),h.getLevel().getGameTime());
        c.controller().forcedOption=Option.FLEE;c.setAiEnabled(true);
        h.runAtTickTime(30,()->z.discard());
        h.succeedWhen(()->{h.assertTrue(c.getCloneBrain().retreat.wins()>0,"normal FLEE and motor movement create real survival evidence: pos="+c.position()+" "+c.controller().survival().report());h.assertTrue(c.isAlive()&&c.position().distanceToSqr(start)>=1,"clone moved and survived rather than being frozen");});
    }

}
