package com.rlclones.gametest;
import com.rlclones.ai.*;
import com.rlclones.ai.brain.*;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("rlclones_ground") @PrefixGameTestTemplate(false)
public final class CombatGroundTests {
    private static final Set<UUID> MOBS=new HashSet<>();
    private static void cleanup(ServerLevel l){GearRecipeTests.cleanup(l);for(var id:MOBS){var e=l.getEntity(id);if(e!=null)e.discard();}MOBS.clear();}
    @AfterBatch(batch="ground-safe")public static void cleanSafe(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-high")public static void cleanHigh(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-choke")public static void cleanChoke(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-cover")public static void cleanCover(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-formation")public static void cleanFormation(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-spread")public static void cleanSpread(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-learn")public static void cleanLearn(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-lure")public static void cleanLure(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-friendly")public static void cleanFriendly(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="ground-loss")public static void cleanLoss(ServerLevel l){cleanup(l);}
    private static void arena(GameTestHelper h){
        // Isolate the fixture from natural cave/ocean water; a flooded tactical site must remain invalid.
        for(var p:BlockPos.betweenClosed(new BlockPos(0,1,0),new BlockPos(18,11,18))){boolean shell=p.getY()==1||p.getY()==11||p.getX()==0||p.getX()==18||p.getZ()==0||p.getZ()==18;h.setBlock(p,shell?Blocks.STONE:Blocks.AIR);}
    }

    private static ClonePlayer actor(GameTestHelper h,int x,int z,boolean bow){var c=GearRecipeTests.clone(h);Vec3 p=h.absoluteVec(new Vec3(x+.5,2,z+.5));c.teleportTo(p.x,p.y,p.z);c.setOnGround(true);c.setCloneBrain(new Brain());if(bow){c.getInventory().setItem(0,new ItemStack(Items.BOW));c.getInventory().setItem(1,new ItemStack(Items.ARROW,64));c.getInventory().selected=0;}return c;}
    private static <T extends Mob>T enemy(GameTestHelper h,EntityType<T> type,int x,int z){T e=h.spawn(type,new Vec3(x+.5,2,z+.5));e.setNoAi(true);e.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);e.setHealth(200);MOBS.add(e.getUUID());return e;}
    private static void hear(ClonePlayer c,Entity e){c.controller().perception().hear(e,e.position(),c.level().getGameTime());}
    private static void coverWall(GameTestHelper h){for(int y=2;y<=4;y++)for(int z=6;z<=8;z++)h.setBlock(new BlockPos(8,y,z),Blocks.STONE);}
    @GameTest(template="arena",batch="ground-safe",timeoutTicks=40)
    public static void rejectsCliffLavaAndMissingRetreat(GameTestHelper h){arena(h);var c=actor(h,3,3,false);var p=h.absolutePos(new BlockPos(10,2,10));
        h.assertTrue(c.controller().ground().safe(p),"stable platform initially safe");h.getLevel().setBlock(p.offset(1,-1,1),Blocks.AIR.defaultBlockState(),3);
        h.assertTrue(!c.controller().ground().safe(p),"diagonal knockback edge is rejected");h.getLevel().setBlock(p.offset(1,-1,1),Blocks.STONE.defaultBlockState(),3);h.getLevel().setBlock(p.east().below(),Blocks.LAVA.defaultBlockState(),3);
        h.assertTrue(!c.controller().ground().safe(p),"lava beside the combat position is rejected");h.getLevel().setBlock(p.east().below(),Blocks.STONE.defaultBlockState(),3);h.succeed();}
    @GameTest(template="arena",batch="ground-high",timeoutTicks=40)
    public static void highGroundHasPathAndRejectsRangedClimbingAndFlyingEnemies(GameTestHelper h){arena(h);var c=actor(h,7,6,false);var e=enemy(h,EntityType.ZOMBIE,14,6);hear(c,e);
        for(int x=4;x<=11;x++){h.setBlock(new BlockPos(x,2,8),Blocks.STONE);for(int z=9;z<=16;z++){h.setBlock(new BlockPos(x,2,z),Blocks.STONE);h.setBlock(new BlockPos(x,3,z),Blocks.STONE);}}
        var g=c.controller().ground();h.assertTrue(g.choose(e,h.getLevel().getGameTime())&&g.site().kind()==CombatGround.HIGH,"reachable broad high platform selected");h.assertTrue(c.controller().motor().pathTo(Vec3.atBottomCenterOf(g.site().feet()))!=null,"real ascent route exists");
        c.getCloneBrain().knowledge(Perception.typeId(e)).trait(EnemyKnowledge.CLIMBS);c.getCloneBrain().knowledge(Perception.typeId(e)).trait(EnemyKnowledge.CLIMBS);h.assertTrue(!g.highUseful(e)&&!g.valid(e),"observed climbing removes height advantage");
        var shooter=enemy(h,EntityType.SKELETON,15,10);var flier=enemy(h,EntityType.PHANTOM,15,15);h.assertTrue(!g.highUseful(shooter)&&!g.highUseful(flier),"ranged/flying enemies do not make high ground automatically useful");h.succeed();}
    @GameTest(template="arena",batch="ground-choke",timeoutTicks=40)
    public static void outnumberedMeleeUsesChokeAndAbandonsItAgainstRangedAttack(GameTestHelper h){arena(h);var c=actor(h,7,5,false);var e=enemy(h,EntityType.ZOMBIE,7,15);var other=enemy(h,EntityType.ZOMBIE,9,15);hear(c,e);hear(c,other);
        for(int z=7;z<=12;z++)for(int y=2;y<=4;y++)for(int x:new int[]{6,8})h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        var g=c.controller().ground();h.assertTrue(g.choose(e,h.getLevel().getGameTime())&&g.site().kind()==CombatGround.CHOKE,"opposite corridor walls limit the melee front");
        c.getCloneBrain().knowledge(Perception.typeId(e)).trait(EnemyKnowledge.RANGED);h.assertTrue(!g.valid(e),"a ranged counter makes the chosen choke invalid");
        g.tick(e,h.getLevel().getGameTime()+10);h.assertTrue(g.invalidated>0&&!g.active(),"lost advantage triggers repositioning rather than remaining in the choke");h.succeed();}
    @GameTest(template="arena",batch="ground-formation",timeoutTicks=40)
    public static void rangedAndFrontlineChooseSeparateFiringLanes(GameTestHelper h){arena(h);var rear=actor(h,2,7,true);var front=actor(h,8,7,false);var e=enemy(h,EntityType.ZOMBIE,15,7);hear(rear,e);hear(front,e);
        var back=rear.controller().ground();h.assertTrue(back.choose(e,h.getLevel().getGameTime())&&back.site().kind()==CombatGround.OPEN,"rear fighter flanks its frontline ally");
        h.assertTrue(rear.controller().rangedBow().friendInLine(e,Vec3.atBottomCenterOf(back.site().feet()).add(0,rear.getEyeHeight(),0))==null,"rear position has a clear friendly firing lane");
        back.cancel();var forward=front.controller().ground();h.assertTrue(forward.choose(e,h.getLevel().getGameTime()),"frontline also finds a position off the archer's lane");
        h.assertTrue(new AABB(forward.site().feet()).inflate(.5).clip(rear.getEyePosition(),e.getBoundingBox().getCenter()).isEmpty(),"frontline does not stand in rear ally's shot");h.succeed();}
    @GameTest(template="arena",batch="ground-spread",timeoutTicks=40)
    public static void learnedAreaAttackSpreadsAlliesAndAvoidsNarrowSites(GameTestHelper h){arena(h);var c=actor(h,7,7,false);var ally=actor(h,8,7,false);var e=enemy(h,EntityType.ZOMBIE,15,7);hear(c,e);c.controller().observeEffect(e,"minecraft:poison");
        var g=c.controller().ground();h.assertTrue(g.area(e)&&g.choose(e,h.getLevel().getGameTime()),"known infection/poison source prompts area avoidance");
        h.assertTrue(g.site().kind()==CombatGround.OPEN&&Vec3.atBottomCenterOf(g.site().feet()).distanceTo(ally.position())>=4&&g.spreadMoves>0,"chooses a separated open combat site, not a clustered choke");h.succeed();}
    @GameTest(template="arena",batch="ground-loss",timeoutTicks=40)
    public static void vanishedCoverOrReturnFloorInvalidatesThePosition(GameTestHelper h){arena(h);coverWall(h);var c=actor(h,4,7,true);var e=enemy(h,EntityType.SKELETON,15,7);hear(c,e);var g=c.controller().ground();
        h.assertTrue(g.choose(e,h.getLevel().getGameTime())&&g.site().kind()==CombatGround.COVER,"cover and a firing opening are selected");
        BlockPos exit=g.site().escape();h.getLevel().setBlock(exit.below(),Blocks.AIR.defaultBlockState(),3);h.assertTrue(!g.valid(e),"return path damage invalidates site");h.getLevel().setBlock(exit.below(),Blocks.STONE.defaultBlockState(),3);
        for(int y=2;y<=4;y++)for(int z=6;z<=8;z++)h.setBlock(new BlockPos(8,y,z),Blocks.AIR);g.tick(e,h.getLevel().getGameTime()+10);
        h.assertTrue(g.invalidated>0&&!g.active(),"no remaining cover means no fixation on the old position");h.succeed();}
    @GameTest(template="arena",batch="ground-learn",timeoutTicks=40)
    public static void onlyActualCombatResultsAreSuccessAndPersist(GameTestHelper h){arena(h);var c=actor(h,7,7,false);actor(h,8,7,false);var e=enemy(h,EntityType.ZOMBIE,15,7);hear(c,e);c.controller().observeEffect(e,"minecraft:poison");var g=c.controller().ground();
        h.assertTrue(g.choose(e,h.getLevel().getGameTime()),"first tactical trial starts");g.finish(true);h.assertTrue(c.getCloneBrain().ground.trials()==1&&c.getCloneBrain().ground.wins()==0,"taking a position alone is not a success");
        h.assertTrue(g.choose(e,h.getLevel().getGameTime()),"second trial starts");h.assertTrue(e.hurt(h.getLevel().damageSources().mobAttack(c),8),"real melee damage is delivered through game events");g.finish(true);
        h.assertTrue(c.getCloneBrain().ground.wins()==1,"actual damage with a retained exit gives success");Brain copy=c.getCloneBrain().copy();h.assertTrue(copy.ground.wins()==1,"save/load/copy retains evidence");copy.ground.record(0,0,-1,false);h.assertTrue(c.getCloneBrain().ground.trials()==2,"copy does not alias");c.getCloneBrain().absorb(copy);h.assertTrue(c.getCloneBrain().ground.trials()==5,"sharing combines independent tables");
        h.assertTrue(Brain.load(new net.minecraft.nbt.CompoundTag()).ground.trials()==0,"old brain data stays readable");h.succeed();}
    @GameTest(template="arena",batch="ground-cover",timeoutTicks=380)
    public static void actuallyChargesInCoverPeeksShootsAndReturns(GameTestHelper h){arena(h);coverWall(h);var c=actor(h,4,7,true);var e=enemy(h,EntityType.SKELETON,15,7);hear(c,e);var g=c.controller().ground();
        h.assertTrue(g.choose(e,h.getLevel().getGameTime())&&g.site().kind()==CombatGround.COVER,"cover trial starts");
        try{for(String name:new String[]{"target","targetType"}){var f=CloneController.class.getDeclaredField(name);f.setAccessible(true);f.set(c.controller(),name.equals("target")?e:Perception.typeId(e));}}catch(Exception ex){throw new RuntimeException(ex);}c.controller().forcedAction=com.rlclones.ai.combat.CombatAction.SHOOT;
        boolean[] hiddenCharge={false},returned={false};int[] ticks={0};
        h.onEachTick(()->{long now=h.getLevel().getGameTime();boolean busy=g.tick(e,now);
            try{if(g.cancelAction){var m=CloneController.class.getDeclaredMethod("closeCombatStep",boolean.class,boolean.class);m.setAccessible(true);m.invoke(c.controller(),false,false);}if(!busy&&g.exposed()){var m=CloneController.class.getDeclaredMethod("combatTick");m.setAccessible(true);m.invoke(c.controller());}}catch(Exception ex){throw new RuntimeException(ex);}
            c.controller().motor().tick();c.controller().rangedBow().tick(now);ticks[0]++;
            boolean hidden=h.getLevel().clip(new ClipContext(e.getEyePosition(),c.getEyePosition(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,e)).getType()!=HitResult.Type.MISS;
            hiddenCharge[0]|=hidden&&c.isUsingItem();returned[0]|=e.getHealth()<200&&hidden;
        });
        h.succeedWhen(()->{h.assertTrue(hiddenCharge[0]&&returned[0],"charges concealed, fires from an opening, and returns behind cover: "+g.report()+" "+c.controller().shootDebug);h.assertTrue(e.getHealth()<195&&c.getCloneBrain().ground.wins()>0,"real shots damage the enemy and the combat result is learned: hp="+e.getHealth()+" "+g.report()+" "+c.controller().shootDebug);});}
    @GameTest(template="arena",batch="ground-lure",timeoutTicks=260)
    public static void walksOutOfConfinedGroundAndDrawsEnemyIntoTheOpen(GameTestHelper h){
        arena(h);for(int z=6;z<=8;z++)for(int y=2;y<=4;y++)for(int x:new int[]{5,7})h.setBlock(new BlockPos(x,y,z),Blocks.OAK_LOG);
        var c=actor(h,6,7,false);var e=enemy(h,EntityType.ZOMBIE,15,7);hear(c,e);var g=c.controller().ground();h.assertTrue(g.choose(e,h.getLevel().getGameTime())&&g.site().kind()==CombatGround.OPEN,"open site chosen outside confined forest/corridor");
        var goal=g.site().feet();e.setNoAi(false);e.setTarget(c);c.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,500,4));
        h.onEachTick(()->{g.tick(e,h.getLevel().getGameTime());c.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(c.position().distanceToSqr(Vec3.atBottomCenterOf(goal))<1,"clone really walks to the open site");h.assertTrue(e.distanceTo(c)<5,"live melee enemy follows into the open rather than being chased into cover");});
    }
    @GameTest(template="arena",batch="ground-friendly",timeoutTicks=140)
    public static void friendlyDamageAndDeathAreNotTacticalSuccessAndBrainSwitchCancels(GameTestHelper h){
        arena(h);var c=actor(h,7,7,false);var ally=actor(h,8,7,false);var e=enemy(h,EntityType.ZOMBIE,15,7);hear(c,e);c.controller().observeEffect(e,"minecraft:poison");var g=c.controller().ground();
        h.assertTrue(g.choose(e,h.getLevel().getGameTime()),"trial starts");int state=4,kind=g.site().kind();
        h.runAtTickTime(80,()->{
            boolean pvp=h.getLevel().getServer().isPvpAllowed();try{h.getLevel().getServer().setPvpAllowed(true);h.assertTrue(ally.hurt(h.getLevel().damageSources().mobAttack(c),4),"actual friendly damage event occurs after spawn protection");}finally{h.getLevel().getServer().setPvpAllowed(pvp);}g.finish(true);
            h.assertTrue(c.getCloneBrain().ground.value(state,kind)==-2&&c.getCloneBrain().ground.wins()==0,"friendly fire has the worst evaluation");
            h.assertTrue(g.choose(e,h.getLevel().getGameTime()),"death trial starts");g.end(true);h.assertTrue(c.getCloneBrain().ground.wins()==0,"death never gives a tactical win");
            h.assertTrue(g.choose(e,h.getLevel().getGameTime()),"brain switch trial starts");var old=c.getCloneBrain();long count=old.ground.trials();c.setCloneBrain(new Brain());g.tick(e,h.getLevel().getGameTime()+1);
            h.assertTrue(old.ground.trials()==count&&c.getCloneBrain().ground.trials()==0,"switching brain cannot manufacture an outcome");
            c.setCloneBrain(old);h.assertTrue(g.choose(e,h.getLevel().getGameTime()),"dimension switch trial starts");try{var f=CombatGround.class.getDeclaredField("dimension");f.setAccessible(true);f.set(g,Level.NETHER);}catch(Exception ex){throw new RuntimeException(ex);}g.end(true);h.assertTrue(old.ground.trials()==count,"dimension change before death also cancels the old trial");h.succeed();
        });
    }

}
