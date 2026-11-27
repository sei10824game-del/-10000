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

    @AfterBatch(batch="cornered-stand") public static void corneredCleanup(ServerLevel level){cleanup(level);Motor.clearSharedPaths();}
    private static ClonePlayer corneredActor(GameTestHelper h,boolean closed){
        for(var p:BlockPos.betweenClosed(new BlockPos(0,1,0),new BlockPos(16,5,16)))h.setBlock(p,p.getY()==1?Blocks.BEDROCK:Blocks.AIR);
        if(closed)for(var p:BlockPos.betweenClosed(new BlockPos(5,2,5),new BlockPos(10,4,9)))h.setBlock(p,p.getX()==5||p.getX()==10||p.getZ()==5||p.getZ()==9||p.getY()==4?Blocks.BEDROCK:Blocks.AIR);
        var c=GearRecipeTests.clone(h);var pos=h.absoluteVec(new Vec3(7.5,2,7.5));c.teleportTo(pos.x,pos.y,pos.z);c.setYRot(-90);c.setYHeadRot(-90);c.setXRot(0);c.setOnGround(true);c.setHealth(6);
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));c.controller().forcedOption=Option.FLEE;c.controller().forcedAction=com.rlclones.ai.combat.CombatAction.ATTACK;Motor.clearSharedPaths();return c;
    }
    @GameTest(template="arena",batch="cornered-stand",timeoutTicks=260)
    public static void enclosedCloneOverridesFleeAndActuallyCounterattacks(GameTestHelper h){
        var c=corneredActor(h,true);var z=zombie(h,new BlockPos(9,2,7));z.setNoAi(true);z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(80);z.setHealth(80);
        c.controller().perception().hear(z,z.position(),h.getLevel().getGameTime());
        ((long[])field(c.controller(),"optionCooldown"))[Option.FIGHT.ordinal()]=h.getLevel().getGameTime()+1000;c.setAiEnabled(true);
        h.succeedWhen(()->{h.assertTrue(c.controller().corneredFights>0,"repeated unavailable exits detected");h.assertTrue(c.controller().option()==Option.FIGHT,"cornered defense overrides forced flee and fight cooldown");h.assertTrue(z.getHealth()<80,"actual counterattack instead of regrouping away from oversized threat");h.assertTrue(c.isAlive(),"clone survives the test");});
    }
    @GameTest(template="arena",batch="cornered-stand",timeoutTicks=60)
    public static void oneBlockedDirectionWithSideExitDoesNotForceCombat(GameTestHelper h){
        var c=corneredActor(h,false);c.setHealth(20);var z=zombie(h,new BlockPos(10,2,7));z.setNoAi(true);z.setInvulnerable(true);
        for(var p:BlockPos.betweenClosed(new BlockPos(3,2,4),new BlockPos(3,4,10)))h.setBlock(p,Blocks.BEDROCK);
        long now=h.getLevel().getGameTime();c.controller().perception().hear(z,z.position(),now);
        var threats=Senses.threats(c.controller().perception(),c,now,24);
        h.assertTrue(call(c.controller(),"alternateRetreat",new Class[]{java.util.List.class},threats)!=null,"reachable alternative route found around first blocked direction");
        for(int t=0;t<=40;t+=20)h.assertTrue(!(boolean)call(c.controller(),"runFlee",new Class[]{long.class},now+t),"one wall does not immediately force a last stand");
        h.assertTrue(c.controller().corneredFights==0,"safe alternatives remain fleeing");h.succeed();
    }
    @GameTest(template="arena",batch="cornered-stand",timeoutTicks=60)
    public static void openingExitReleasesCombatCommitmentBeforeTimeout(GameTestHelper h){
        var c=corneredActor(h,true);var z=zombie(h,new BlockPos(9,2,7));z.setNoAi(true);long now=h.getLevel().getGameTime();c.controller().perception().hear(z,z.position(),now);
        call(c.controller(),"standAndFight",new Class[]{long.class,Entity.class},now,z);set(c.controller(),"option",Option.FIGHT);set(c.controller(),"optionTicks",30);
        h.assertTrue(!(boolean)call(c.controller(),"shouldInterrupt",new Class[]{long.class},now+20),"closed exit does not immediately oscillate back to flee");
        h.setBlock(new BlockPos(5,2,7),Blocks.AIR);h.setBlock(new BlockPos(5,3,7),Blocks.AIR);
        h.assertTrue((boolean)call(c.controller(),"shouldInterrupt",new Class[]{long.class},now+40),"new safe exit permits retreat before ten-second commitment ends");
        call(c.controller(),"startOption",new Class[]{long.class},now+40);h.assertTrue(c.controller().option()==Option.FLEE,"escape is selected once a route opens");h.succeed();
    }

    @AfterBatch(batch="practical-combat") public static void practicalCleanup(ServerLevel level){cleanup(level);}
    @GameTest(template="arena",batch="practical-combat",timeoutTicks=60)
    public static void closestPursuerReplacesCurrentAttackerBeforeTakingDamage(GameTestHelper h){
        var c=corneredActor(h,false);c.setHealth(20);c.controller().forcedOption=Option.FIGHT;
        var far=zombie(h,new BlockPos(12,2,7));var near=zombie(h,new BlockPos(9,2,7));far.setNoAi(true);near.setNoAi(true);far.setTarget(c);near.setTarget(c);long now=h.getLevel().getGameTime();
        c.controller().perception().hear(far,far.position(),now);c.controller().perception().hear(near,near.position(),now);
        set(c.controller(),"target",far);set(c.controller(),"targetType",Perception.typeId(far));set(c.controller(),"option",Option.FIGHT);
        call(c.controller(),"runFight",new Class[]{long.class,boolean.class},now,false);
        h.assertTrue(field(c.controller(),"target")==near&&c.getLastHurtByMob()==null,"near pursuer selected without waiting to be hit");
        var closest=zombie(h,new BlockPos(8,2,7));closest.setNoAi(true);closest.setTarget(c);c.controller().perception().hear(closest,closest.position(),now+1);
        call(c.controller(),"runFight",new Class[]{long.class,boolean.class},now+1,false);h.assertTrue(field(c.controller(),"target")==closest,"even current attacker in melee range yields to closer pursuer");h.succeed();
    }
    @GameTest(template="arena",batch="practical-combat",timeoutTicks=60)
    public static void sealedSoundThreatIsIgnoredUntilItsWallOpens(GameTestHelper h){
        var c=corneredActor(h,false);c.setHealth(20);
        for(var p:BlockPos.betweenClosed(new BlockPos(8,1,5),new BlockPos(12,5,9)))h.setBlock(p,p.getX()==8||p.getX()==12||p.getZ()==5||p.getZ()==9||p.getY()==1||p.getY()==5?Blocks.BEDROCK:Blocks.AIR);
        var z=zombie(h,new BlockPos(10,2,7));z.setNoAi(true);long now=h.getLevel().getGameTime();long probe=now+Math.floorMod(-(now+c.getId()),20);
        var seen=c.controller().perception().hear(z,z.position(),now);
        h.assertTrue(c.controller().ignoreBlockedSound(seen,probe),"sealed unreachable sound is deliberately ignored");
        h.assertTrue(Senses.threats(c.controller().perception(),c,probe+1,24).isEmpty(),"repeated sound cannot keep interrupting work");
        set(c.controller().defense(),"alerted",z);set(c.controller().defense(),"alertUntil",probe+100);h.assertTrue(!c.controller().defense().engage(probe+1),"ally alerts do not recreate the ignored threat loop");
        h.setBlock(new BlockPos(8,2,7),Blocks.AIR);h.setBlock(new BlockPos(8,3,7),Blocks.AIR);
        h.runAfterDelay(5,()->{h.assertTrue(!c.controller().unreachable(z,probe+6),"visible emerging enemy reactivates on the next normal sight refresh");h.succeed();});
    }

    @GameTest(template="arena",batch="practical-combat",timeoutTicks=300)
    public static void heardEnemyBehindStoneIsReachedAndActuallyAttacked(GameTestHelper h){
        var c=corneredActor(h,false);c.setHealth(20);c.getInventory().add(new ItemStack(Items.IRON_PICKAXE));c.controller().forcedOption=Option.FIGHT;
        for(int x=4;x<=15;x++){h.setBlock(new BlockPos(x,4,7),Blocks.BEDROCK);for(int y=2;y<=4;y++){h.setBlock(new BlockPos(x,y,6),Blocks.BEDROCK);h.setBlock(new BlockPos(x,y,8),Blocks.BEDROCK);}}
        h.setBlock(new BlockPos(9,2,7),Blocks.STONE);h.setBlock(new BlockPos(9,3,7),Blocks.STONE);var z=zombie(h,new BlockPos(13,2,7));z.setNoAi(true);z.setTarget(c);
        c.controller().perception().hear(z,z.position(),h.getLevel().getGameTime());c.setAiEnabled(true);
        h.succeedWhen(()->{h.assertTrue(h.getBlockState(new BlockPos(9,2,7)).isAir()&&h.getBlockState(new BlockPos(9,3,7)).isAir(),"real controller opens body-height route through obstruction: "+c.position()+" "+c.controller().fightDebug()+" "+c.controller().motor().mineDebug());h.assertTrue(z.getHealth()<20,"heard target actually attacked after breaching");h.assertTrue(c.isAlive(),"no unsafe forced jump under roof");});
    }

}
