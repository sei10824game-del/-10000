package com.rlclones.gametest;
import com.rlclones.ai.*;
import com.rlclones.ai.strategy.Option;
import com.rlclones.ai.combat.CombatAction;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;
@GameTestHolder("rlclones_combat_safety") @PrefixGameTestTemplate(false)
public final class CombatSafetyTests {
    private static final Set<UUID> MOBS=new HashSet<>();private static final List<Object> LISTENERS=new ArrayList<>();
    private static void clean(ServerLevel l){for(var o:LISTENERS)MinecraftForge.EVENT_BUS.unregister(o);LISTENERS.clear();GearRecipeTests.cleanup(l);for(var id:MOBS){var e=l.getEntity(id);if(e!=null)e.discard();}MOBS.clear();TeamDefense.clear();}
    @AfterBatch(batch="pillar-static")public static void staticCleanup(ServerLevel l){clean(l);}
    @AfterBatch(batch="pillar-open")public static void openCleanup(ServerLevel l){clean(l);}
    @AfterBatch(batch="pillar-changing")public static void changingCleanup(ServerLevel l){clean(l);}
    @AfterBatch(batch="pillar-protected")public static void protectedCleanup(ServerLevel l){clean(l);}
    @AfterBatch(batch="defense-visible")public static void visibleCleanup(ServerLevel l){clean(l);}
    @AfterBatch(batch="defense-damage")public static void damageCleanup(ServerLevel l){clean(l);}
    @AfterBatch(batch="defense-rally")public static void rallyCleanup(ServerLevel l){clean(l);}
    @AfterBatch(batch="defense-limits")public static void limitsCleanup(ServerLevel l){clean(l);}
    private static void arena(GameTestHelper h){for(var p:BlockPos.betweenClosed(new BlockPos(0,1,0),new BlockPos(18,11,18))){boolean shell=p.getY()==1||p.getY()==11||p.getX()==0||p.getX()==18||p.getZ()==0||p.getZ()==18;h.setBlock(p,shell?Blocks.STONE:Blocks.AIR);}}
    private static ClonePlayer actor(GameTestHelper h,int x,int z){var c=GearRecipeTests.clone(h);Vec3 p=h.absoluteVec(new Vec3(x+.5,2,z+.5));c.teleportTo(p.x,p.y,p.z);c.setYRot(-90);c.setYHeadRot(-90);c.setXRot(0);c.setOnGround(true);c.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,500));return c;}
    private static <T extends Mob>T mob(GameTestHelper h,EntityType<T> type,int x,int z){T e=h.spawn(type,new Vec3(x+.5,2,z+.5));e.setNoAi(true);MOBS.add(e.getUUID());return e;}
    private static void blocks(ClonePlayer c){c.getInventory().setItem(0,new ItemStack(Items.COBBLESTONE,16));c.getInventory().selected=0;}
    private static void hear(ClonePlayer c,Entity e){c.controller().perception().hear(e,e.position(),c.level().getGameTime());}
    private static void field(Object o,String n,Object value){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,value);}catch(Exception e){throw new RuntimeException(e);}}
    private static Object call(Object o,String n,Class<?>[] types,Object...args){try{var m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new RuntimeException(e);}}
    @GameTest(template="arena",batch="pillar-static",timeoutTicks=40)
    public static void lowLeavesAndSideOverhangRemovePillarAndPerch(GameTestHelper h){arena(h);var c=actor(h,7,7);blocks(c);var e=mob(h,EntityType.ZOMBIE,11,7);hear(c,e);var feet=c.blockPosition();h.getLevel().setBlock(feet.above(2),Blocks.OAK_LEAVES.defaultBlockState(),3);
        h.assertTrue(!c.controller().perch().canStart(h.getLevel().getGameTime())&&(Senses.combatMask(c)&CombatAction.PILLAR.bit())==0,"low tree canopy excludes both building paths");
        h.getLevel().setBlock(feet.above(2),Blocks.AIR.defaultBlockState(),3);c.teleportTo(c.getX()+.3,c.getY(),c.getZ());h.getLevel().setBlock(feet.east().above(2),Blocks.STONE.defaultBlockState(),3);
        h.assertTrue(!PillarSpace.clear(c,2.25),"overhang touching the body edge also blocks building");h.succeed();}
    @GameTest(template="arena",batch="pillar-static",timeoutTicks=40)
    public static void combatPillarAbortsImmediatelyAndHasLocalCooldown(GameTestHelper h){arena(h);var c=actor(h,7,7);blocks(c);var feet=c.blockPosition();h.getLevel().setBlock(feet.above(2),Blocks.STONE.defaultBlockState(),3);field(c.controller(),"pillarBase",feet);field(c.controller(),"pillarPlaced",false);
        call(c.controller(),"pillar",new Class[]{});h.assertTrue(c.controller().perch().blocked(h.getLevel().getGameTime())&&(Senses.combatMask(c)&CombatAction.PILLAR.bit())==0,"blocked attempt cannot be chosen again on next decision");
        h.getLevel().setBlock(feet.above(2),Blocks.AIR.defaultBlockState(),3);h.assertTrue(c.controller().perch().blocked(h.getLevel().getGameTime()),"brief cooldown prevents repeated placement attempts");c.teleportTo(c.getX()+4,c.getY(),c.getZ());h.assertTrue(!c.controller().perch().blocked(h.getLevel().getGameTime()),"moving to another location releases the local failure");h.succeed();}
    @GameTest(template="arena",batch="pillar-open",timeoutTicks=260)
    public static void openAirStillBuildsTwoRealStepsAndRecoversThem(GameTestHelper h){arena(h);var c=actor(h,7,7);blocks(c);var e=mob(h,EntityType.ZOMBIE,11,7);hear(c,e);c.getInventory().setItem(1,new ItemStack(Items.WOODEN_PICKAXE));double start=c.getY();var perch=c.controller().perch();h.assertTrue(perch.canStart(h.getLevel().getGameTime()),"open sky permits perch");perch.start();
        h.onEachTick(()->{perch.tick(h.getLevel().getGameTime());c.controller().motor().tick();if(c.getY()>=start+1.95)e.discard();});h.succeedWhen(()->{h.assertTrue(perch.highest>=start+1.95,"two real steps were climbed: "+perch.debug);h.assertTrue(!perch.busy()&&perch.blocksRecovered>=2,"normal descent and recovery preserved: "+perch.debug);});}
    @GameTest(template="arena",batch="pillar-changing",timeoutTicks=130)
    public static void ceilingAddedAfterFirstStepYieldsControlWithoutJumpLoop(GameTestHelper h){arena(h);var c=actor(h,7,7);blocks(c);var e=mob(h,EntityType.ZOMBIE,11,7);hear(c,e);var base=c.blockPosition();var perch=c.controller().perch();perch.start();boolean[] roof={false};
        h.onEachTick(()->{if(!roof[0]&&h.getLevel().getBlockState(base).is(Blocks.COBBLESTONE)){h.getLevel().setBlock(base.above(3),Blocks.OAK_LEAVES.defaultBlockState(),3);roof[0]=true;}perch.tick(h.getLevel().getGameTime());c.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(roof[0]&&!perch.busy()&&perch.aborted>0,"new obstruction aborts the climb promptly: "+perch.debug);h.assertTrue(h.getLevel().getBlockState(base).is(Blocks.COBBLESTONE)&&c.getInventory().countItem(Items.COBBLESTONE)==15,"existing real step remains with no duplicated materials");});}
    public static final class DenyPlace {private final UUID id;DenyPlace(UUID id){this.id=id;}@SubscribeEvent public void deny(BlockEvent.EntityPlaceEvent e){if(e.getEntity()!=null&&e.getEntity().getUUID().equals(id))e.setCanceled(true);}}
    @GameTest(template="arena",batch="pillar-protected",timeoutTicks=130)
    public static void deniedPlacementDoesNotKeepJumping(GameTestHelper h){arena(h);var c=actor(h,7,7);blocks(c);var e=mob(h,EntityType.ZOMBIE,11,7);hear(c,e);var listener=new DenyPlace(c.getUUID());LISTENERS.add(listener);MinecraftForge.EVENT_BUS.register(listener);var perch=c.controller().perch();perch.start();
        h.onEachTick(()->{perch.tick(h.getLevel().getGameTime());c.controller().motor().tick();});h.succeedWhen(()->{h.assertTrue(!perch.busy()&&perch.aborted>0,"protected placement gives control back: "+perch.debug);h.assertTrue(c.getInventory().countItem(Items.COBBLESTONE)==16&&perch.blocked(h.getLevel().getGameTime()),"no fake building success and no immediate repeat");});}
    @GameTest(template="arena",batch="defense-visible",timeoutTicks=40)
    public static void visibleEnemyInterruptsWorkAndOverridesMiningPriority(GameTestHelper h){arena(h);var c=actor(h,7,7);var e=mob(h,EntityType.ZOMBIE,11,7);c.controller().perception().update(h.getLevel().getGameTime());h.assertTrue(c.controller().defense().visible(h.getLevel().getGameTime())==e,"hostile is genuinely visible");c.controller().forcedOption=Option.MINE;field(c.controller(),"option",Option.GATHER_WOOD);c.setAiEnabled(true);c.controller().tick();
        h.assertTrue(c.controller().option()==Option.FIGHT,"visible threat takes precedence over ordinary work: "+c.controller().option());h.succeed();}
    @GameTest(template="arena",batch="defense-damage",timeoutTicks=130)
    public static void actualAttackCallsNearbyAlliesAndTheyEnterFight(GameTestHelper h){arena(h);var victim=actor(h,8,7);var helper=actor(h,6,7);var other=actor(h,5,9);var e=mob(h,EntityType.ZOMBIE,12,7);
        h.runAtTickTime(80,()->{helper.setAiEnabled(true);other.setAiEnabled(true);h.assertTrue(victim.hurt(h.getLevel().damageSources().mobAttack(e),9),"real attack occurs");
            h.assertTrue(helper.controller().defense().alerts>0&&other.controller().defense().alerts>0,"both nearby witnesses receive local threat");h.assertTrue(helper.controller().defense().required(e)>=2,"reported actual damage informs group strength");helper.controller().forcedOption=Option.MINE;call(helper.controller(),"startOption",new Class[]{long.class},h.getLevel().getGameTime());h.assertTrue(helper.controller().option()==Option.FIGHT,"helper joins instead of continuing its work");h.succeed();});}
    @GameTest(template="arena",batch="defense-rally",timeoutTicks=40)
    public static void dangerousEnemyNeedsAlliesButRangedSupportStillFires(GameTestHelper h){arena(h);var c=actor(h,7,7);var a=actor(h,3,6);var b=actor(h,4,9);var e=mob(h,EntityType.ZOMBIE,13,7);e.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);e.setHealth(200);c.setAiEnabled(true);hear(c,e);long now=h.getLevel().getGameTime();
        h.assertTrue(c.controller().defense().regroup(e,now),"solo melee clone assembles rather than charging boss");a.setAiEnabled(true);b.setAiEnabled(true);TeamDefense.attacked(c,e,now);h.assertTrue(!c.controller().defense().regroup(e,now+6),"three ready allies can advance together");
        a.setAiEnabled(false);b.setAiEnabled(false);c.getInventory().setItem(0,new ItemStack(Items.BOW));c.getInventory().setItem(1,new ItemStack(Items.ARROW,16));c.teleportTo(c.getX()-4,c.getY(),c.getZ());h.assertTrue(!c.controller().defense().regroup(e,now+12),"ranged support does not stand idle while melee gathers");h.succeed();}
    @GameTest(template="arena",batch="defense-limits",timeoutTicks=40)
    public static void alertsAreLocalTeamScopedAndThrottled(GameTestHelper h){arena(h);var victim=actor(h,8,7);var helper=actor(h,6,7);var rival=actor(h,6,9);helper.setAiEnabled(true);rival.setAiEnabled(true);rival.setCloneTeam("other");var e=mob(h,EntityType.WOLF,12,7);long now=h.getLevel().getGameTime();helper.setYHeadRot(90);h.assertTrue(!helper.controller().perception().canSee(e),"helper was looking away");TeamDefense.attacked(victim,e,now);int count=helper.controller().defense().alerts;TeamDefense.attacked(victim,e,now+1);
        h.assertTrue(count==1&&helper.controller().defense().alerts==1&&rival.controller().defense().alerts==0,"same enemy burst is throttled and other teams are excluded");h.assertTrue(Senses.isHostileTo(e,helper)&&helper.controller().defense().engage(now),"nearby attack noise turns a helper toward a specific neutral/mod attacker");h.succeed();}
}
