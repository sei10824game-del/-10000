package com.rlclones.gametest;
import com.rlclones.ai.*;
import com.rlclones.ai.brain.Brain;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.*;
@GameTestHolder("rlclones_bow")
@PrefixGameTestTemplate(false)
public final class RangedBowTests {
    private static final java.util.Set<java.util.UUID> MOBS=new java.util.HashSet<>();
    @AfterBatch(batch="bow-static") public static void cleanup(ServerLevel l){GearRecipeTests.cleanup(l);for(var id:MOBS){var e=l.getEntity(id);if(e!=null)e.discard();}MOBS.clear();}
    @AfterBatch(batch="bow-live") public static void cleanupLive(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="bow-side") public static void cleanupSide(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="bolt-side") public static void cleanupBolt(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="bow-lob") public static void cleanupLob(ServerLevel l){cleanup(l);}
    private static com.rlclones.clone.ClonePlayer actor(GameTestHelper h,int x,int z){var c=GearRecipeTests.clone(h);Vec3 p=h.absoluteVec(new Vec3(x+.5,2,z+.5));c.teleportTo(p.x,p.y,p.z);c.setYRot(-90);c.setXRot(0);return c;}
    private static net.minecraft.world.entity.monster.Husk enemy(GameTestHelper h,int x,int z){var e=h.spawn(EntityType.HUSK,new BlockPos(x,2,z));e.setNoAi(true);e.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);e.setHealth(200);MOBS.add(e.getUUID());return e;}
    private static void arena(GameTestHelper h){for(var p:BlockPos.betweenClosed(new BlockPos(0,1,0),new BlockPos(16,1,16)))h.setBlock(p,Blocks.STONE);for(var p:BlockPos.betweenClosed(new BlockPos(0,2,0),new BlockPos(16,12,16)))h.setBlock(p,Blocks.AIR);}
    @GameTest(template="arena",batch="bow-static",timeoutTicks=40)
    public static void unseenFriendInArcAndBehindTargetPreventsRelease(GameTestHelper h){
        arena(h);var c=actor(h,2,7);var e=enemy(h,13,7);var friend=actor(h,6,7);
        var aim=c.controller().rangedBow().aim(e,20,false,h.getLevel().getGameTime());c.setYRot(aim.yaw());c.setXRot(aim.pitch());
        h.assertTrue(c.controller().rangedBow().friendInLine(e,c.getEyePosition())==friend,"current unseen friend is detected without visual memory");
        h.assertTrue(!c.controller().rangedBow().safe(e,20),"actual flight through friend is blocked");
        friend.teleportTo(e.getX()+2,e.getY(),e.getZ());h.assertTrue(!c.controller().rangedBow().safe(e,20),"a missed arrow continuing behind the target is checked");h.succeed();
    }
    @GameTest(template="arena",batch="bow-static",timeoutTicks=40)
    public static void movingFriendAndMultiShotSideArrowAreProtected(GameTestHelper h){
        arena(h);var c=actor(h,2,7);var e=enemy(h,13,7);var friend=actor(h,7,10);friend.setDeltaMovement(0,0,-.4);
        var a=c.controller().rangedBow().aim(e,20,false,h.getLevel().getGameTime());c.setYRot(a.yaw());c.setXRot(a.pitch());
        h.assertTrue(!c.controller().rangedBow().safe(e,20),"friend moving into future trajectory blocks shot");
        friend.setDeltaMovement(Vec3.ZERO);Vec3 side=c.getViewVector(1).yRot((float)Math.toRadians(10)).scale(8);friend.teleportTo(c.getX()+side.x,c.getY(),c.getZ()+side.z);
        h.assertTrue(!c.controller().rangedBow().safeBolt(e,true,false),"side bolt of multishot is checked: origin="+c.getEyePosition()+" view="+c.getViewVector(1)+" friend="+friend.getBoundingBox());h.succeed();
    }
    @GameTest(template="arena",batch="bow-static",timeoutTicks=40)
    public static void safeSidePositionIsPreferred(GameTestHelper h){
        arena(h);var c=actor(h,2,7);var e=enemy(h,13,7);actor(h,6,7);
        h.assertTrue(c.controller().rangedBow().reposition(e,h.getLevel().getGameTime()),"clear reachable side position selected before lobbing");
        h.assertTrue(c.controller().rangedBow().repositioned>0&&!c.isUsingItem(),"weapon is lowered during repositioning");h.succeed();
    }
    private static void shoot(com.rlclones.clone.ClonePlayer c,Entity e,int tick){try{
        var f=CloneController.class.getDeclaredField("actionTicks");f.setAccessible(true);f.setInt(c.controller(),tick%45);
        var m=CloneController.class.getDeclaredMethod("shoot",Entity.class,double.class);m.setAccessible(true);m.invoke(c.controller(),e,c.distanceTo(e));
        c.controller().motor().tick();c.controller().rangedBow().tick(c.level().getGameTime());
    }catch(Exception ex){throw new RuntimeException(ex);}}
    @GameTest(template="arena",batch="bow-live",timeoutTicks=300)
    public static void actualBowArrowsHitAndTeachTheLeadTable(GameTestHelper h){
        arena(h);var c=actor(h,2,7);var e=enemy(h,13,7);c.getInventory().setItem(0,new ItemStack(Items.BOW));c.getInventory().setItem(1,new ItemStack(Items.ARROW,64));c.getInventory().selected=0;int[] tick={0};
        h.onEachTick(()->shoot(c,e,tick[0]++));
        h.succeedWhen(()->{h.assertTrue(c.getCloneBrain().bowAim.wins()>=3,"three real arrows damage the intended target and are learned: "+c.controller().shootDebug+" "+c.getCloneBrain().bowAim.trials());h.assertTrue(e.getHealth()<185,"actual vanilla arrow damage, not aiming reward");
            var copy=Brain.load(c.getCloneBrain().save());h.assertTrue(copy.bowAim.wins()==c.getCloneBrain().bowAim.wins(),"aim evidence persists");});
    }
    @GameTest(template="arena",batch="bow-lob",timeoutTicks=380)
    public static void actualHighArcClearsFriendAndHitsTheEnemy(GameTestHelper h){
        arena(h);var c=actor(h,2,7);var e=enemy(h,13,7);var friend=actor(h,6,7);c.getInventory().setItem(0,new ItemStack(Items.BOW));c.getInventory().setItem(1,new ItemStack(Items.ARROW,64));c.getInventory().selected=0;
        // Block both lateral routes while leaving overhead space for a validated lob.
        for(int x=0;x<=16;x++)for(int y=2;y<=4;y++)for(int z=2;z<=12;z++)if(z!=7)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        int[] tick={0};float[] lowest={20};h.onEachTick(()->{shoot(c,e,tick[0]++);lowest[0]=Math.min(lowest[0],friend.getHealth());});
        h.succeedWhen(()->{h.assertTrue(c.controller().arcShots>=2&&c.getCloneBrain().bowAim.wins()>=2,"actual lobs hit the intended enemy: "+c.controller().shootDebug+" blocked="+c.controller().rangedBow().blocked);h.assertTrue(lowest[0]>=20,"friend was never damaged");});
    }
    @GameTest(template="arena",batch="bow-side",timeoutTicks=340)
    public static void movesAsideAndActuallyShootsWithoutHittingFriend(GameTestHelper h){
        arena(h);var c=actor(h,2,7);var e=enemy(h,13,7);var friend=actor(h,6,7);Vec3 start=c.position();
        c.getInventory().setItem(0,new ItemStack(Items.BOW));c.getInventory().setItem(1,new ItemStack(Items.ARROW,64));c.getInventory().selected=0;int[] tick={0};float[] lowest={20};
        h.onEachTick(()->{shoot(c,e,tick[0]++);lowest[0]=Math.min(lowest[0],friend.getHealth());});
        h.succeedWhen(()->{h.assertTrue(c.controller().rangedBow().repositioned>0&&Math.abs(c.getZ()-start.z)>1,"actually moves laterally to a clear firing lane");h.assertTrue(c.getCloneBrain().bowAim.wins()>0&&e.getHealth()<195,"fires a real damaging shot after moving: "+c.controller().shootDebug);h.assertTrue(lowest[0]>=20,"friend remains unharmed");});
    }

    @GameTest(template="arena",batch="bolt-side",timeoutTicks=360)
    public static void crossbowMovesToClearLaneAndHitsEnemy(GameTestHelper h){
        arena(h);var c=actor(h,2,7);var e=enemy(h,13,7);var friend=actor(h,6,7);Vec3 start=c.position();
        c.getInventory().setItem(0,new ItemStack(Items.CROSSBOW));c.getInventory().setItem(1,new ItemStack(Items.ARROW,64));c.getInventory().selected=0;int[] tick={0};float[] lowest={20};
        h.onEachTick(()->{shoot(c,e,tick[0]++);lowest[0]=Math.min(lowest[0],friend.getHealth());});
        h.succeedWhen(()->{h.assertTrue(c.controller().rangedBow().repositioned>0&&Math.abs(c.getZ()-start.z)>1,"crossbow actually moves to a clear lane");h.assertTrue(e.getHealth()<195,"crossbow fires real damaging bolts after repositioning: "+c.controller().shootDebug);h.assertTrue(lowest[0]>=20,"friend is never damaged");});
    }

    @GameTest(template="arena",batch="bow-static",timeoutTicks=40)
    public static void interruptedThrowIsLoweredInsteadOfAutomaticallyReleased(GameTestHelper h){
        arena(h);var c=actor(h,2,7);c.getInventory().setItem(0,new ItemStack(Items.TRIDENT));c.getInventory().selected=0;c.controller().motor().useHeldItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        h.runAtTickTime(12,()->{try{
            h.assertTrue(c.isUsingItem()&&c.getTicksUsingItem()>=10,"real trident is charged");
            var f=CloneController.class.getDeclaredField("action");f.setAccessible(true);f.set(c.controller(),com.rlclones.ai.combat.CombatAction.SHOOT);
            var m=CloneController.class.getDeclaredMethod("closeCombatStep",boolean.class,boolean.class);m.setAccessible(true);m.invoke(c.controller(),false,false);
            h.assertTrue(!c.isUsingItem()&&h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.ThrownTrident.class,c.getBoundingBox().inflate(6)).isEmpty(),"interrupting a ranged action does not launch an unchecked projectile");h.succeed();
        }catch(ReflectiveOperationException ex){throw new RuntimeException(ex);}});
    }

}
