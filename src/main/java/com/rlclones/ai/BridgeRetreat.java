package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** While fleeing along a narrow bridge, erect a two-high wall behind us, then continue along the deck. */
public final class BridgeRetreat {
    private final ClonePlayer self;
    private final Motor motor;
    private BlockPos wall,stand,exit;
    private BlockState lowerPlaced;
    private Direction toward;
    private Entity enemy;
    private long deadline,retryAt;
    private boolean retreating;
    private float health;
    public int wallsBuilt, blocksPlaced, retreats, aborted;

    public BridgeRetreat(ClonePlayer self,Motor motor){this.self=self;this.motor=motor;}
    private ServerLevel level(){return self.serverLevel();}
    private boolean solid(BlockPos p){
        return level().hasChunkAt(p)&&level().getFluidState(p).isEmpty()
                &&level().getBlockState(p).isFaceSturdy(level(),p,Direction.UP);
    }
    private boolean open(BlockPos p){
        return level().hasChunkAt(p)&&level().getFluidState(p).isEmpty()
                &&level().getBlockState(p).getCollisionShape(level(),p).isEmpty();
    }
    private boolean deck(BlockPos p){
        return solid(p.below())&&open(p)&&open(p.above());
    }
    private boolean gap(BlockPos p){
        return open(p)&&open(p.above())&&!solid(p.below())&&!solid(p.below(2));
    }
    private boolean narrow(BlockPos p,Direction d){
        Direction side=d.getClockWise();
        return gap(p.relative(side))&&gap(p.relative(side.getOpposite()));
    }
    private double along(Vec3 p,BlockPos base,Direction d){
        return (p.x-base.getX()-.5)*d.getStepX()+(p.z-base.getZ()-.5)*d.getStepZ();
    }
    private boolean useful(Entity e){
        if(e==null||!e.isAlive()||e.level()!=level()||!Senses.isHostileTo(e,self)
                ||e instanceof Creeper||e instanceof Spider||e instanceof EnderMan
                ||e instanceof net.minecraft.world.entity.FlyingMob||self.controller().ground().area(e))return false;
        var k=self.getCloneBrain().knowledgeIfPresent(Perception.typeId(e));
        return k==null||!k.has(com.rlclones.ai.brain.EnemyKnowledge.FLIES)
                &&!k.has(com.rlclones.ai.brain.EnemyKnowledge.CLIMBS)
                &&!k.has(com.rlclones.ai.brain.EnemyKnowledge.TELEPORTS);
    }
    private boolean alliesBehind(){
        // Use live entities, not remembered sightings. Never wall off an ally on the pursuer's side.
        for(LivingEntity e:level().getEntitiesOfClass(LivingEntity.class,new AABB(wall).inflate(12,3,12),e->e!=self&&e.isAlive())){
            if((Senses.isAllyOf(e,self)||e instanceof TamableAnimal t&&t.isTame()
                    ||e instanceof net.minecraft.world.entity.npc.AbstractVillager)
                    &&along(e.position(),wall,toward)>=-.6)return true;
        }
        return false;
    }
    private int materials(){
        int n=0;for(var s:self.getInventory().items)if(Equipment.isBuildingBlock(self,s))n+=s.getCount();return n;
    }
    private boolean occupied(BlockPos p){
        return !level().getEntitiesOfClass(LivingEntity.class,new AABB(p).deflate(.01),LivingEntity::isAlive).isEmpty();
    }
    private boolean retreatPath(){
        if(stand==null||toward==null)return false;
        for(int i=0;i<=3;i++)if(!deck(stand.relative(toward.getOpposite(),i)))return false;
        return true;
    }
    private void finish(long now){
        wall=stand=exit=null;enemy=null;toward=null;lowerPlaced=null;retreating=false;retryAt=now+160;
    }
    public void reset(){finish(self.level().getGameTime());}
    private boolean start(Entity e,long now){
        if(now<retryAt||!self.onGround()||self.isPassenger()||self.isInWater()||self.phasing||self.isCreative()
                ||self.getBbWidth()>=1||self.getBbHeight()>2||!useful(e)
                ||!Config.get(Config.ALLOW_BLOCK_PLACING,true)||!com.rlclones.clone.CloneMobs.canUseHands(self))return false;
        double d=Senses.gap(self,e);
        if(d<2.4||d>8||Math.abs(e.getY()-self.getY())>.7)return false;
        BlockPos p=self.blockPosition();
        Direction dir=Direction.getNearest(e.getX()-self.getX(),0,e.getZ()-self.getZ());
        BlockPos w=p.relative(dir);
        if(!deck(w)||!deck(w.relative(dir))||!narrow(w,dir)||!narrow(p,dir)||!narrow(w.relative(dir),dir)
                ||along(e.position(),w,dir)<1||Math.abs(along(e.position(),w,dir.getClockWise()))>.8)return false;
        for(int i=0;i<=3;i++)if(!deck(p.relative(dir.getOpposite(),i)))return false;
        if(materials()<2||occupied(w)||occupied(w.above()))return false;
        stand=p;wall=w;exit=p.relative(dir.getOpposite(),3);toward=dir;enemy=e;health=self.getHealth();
        if(alliesBehind()){finish(now);return false;}
        deadline=now+16;return true;
    }
    /** True only while placing a wall or taking the verified first steps away from it. */
    public boolean tick(Entity e,long now){
        if(wall==null&&!start(e,now))return false;
        if(retreating){
            if(now>=deadline||!deck(self.blockPosition())||!deck(exit)||self.position().distanceToSqr(Vec3.atBottomCenterOf(exit))<.3){
                finish(now);return false;
            }
            motor.cancelJump();motor.movePrecisely(Vec3.atBottomCenterOf(exit));return true;
        }
        if(now>=deadline||e!=enemy||!useful(e)||!retreatPath()||alliesBehind()
                ||self.position().distanceToSqr(Vec3.atBottomCenterOf(stand))>.65
                ||Senses.gap(self,e)<1.7||self.getHealth()<health-2
                ||!Config.get(Config.ALLOW_BLOCK_PLACING,true)){
            aborted++;finish(now);return false;
        }
        motor.stop();motor.cancelJump();motor.sneak(true);
        BlockPos cell=lowerPlaced==null?wall:wall.above();
        if(lowerPlaced!=null&&level().getBlockState(wall)!=lowerPlaced){aborted++;finish(now);return false;}
        if(!level().getBlockState(cell).canBeReplaced()||occupied(cell)){aborted++;finish(now);return false;}
        int slot=Equipment.pillarBlockSlot(self);
        if(slot<0){aborted++;finish(now);return false;}
        Equipment.select(self,slot);
        if(motor.placeBlockAt(cell)&&level().getBlockState(cell).isCollisionShapeFullBlock(level(),cell)){
            blocksPlaced++;
            Motor.PLUGS.put(cell.immutable(),now);
            if(lowerPlaced==null)lowerPlaced=level().getBlockState(wall);
            else{
                wallsBuilt++;retreats++;retreating=true;deadline=now+50;
                motor.sneak(false);motor.movePrecisely(Vec3.atBottomCenterOf(exit));
            }
        }
        return true;
    }
}
