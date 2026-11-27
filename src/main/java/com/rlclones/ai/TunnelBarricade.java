package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

/** A one-block-high barricade across a two-high tunnel. The upper opening remains available for melee attacks. */
public final class TunnelBarricade {
    private final ClonePlayer self;
    private final Motor motor;
    private final TacticTrial trial;
    private BlockPos block, stand;
    private Direction facing;
    private BlockState placedState;
    private Entity enemy;
    private long started, retryAt, quietSince, cleanupUntil;
    private float initialHealth;
    public int placed, hits, removed;
    public TunnelBarricade(ClonePlayer self, Motor motor){this.self=self;this.motor=motor;this.trial=new TacticTrial(self);}
    private ServerLevel level(){return self.serverLevel();}
    private boolean solid(BlockPos p){
        return level().hasChunkAt(p)&&level().getFluidState(p).isEmpty()
                &&level().getBlockState(p).isCollisionShapeFullBlock(level(),p);
    }
    private boolean air(BlockPos p){
        return level().hasChunkAt(p)&&level().getFluidState(p).isEmpty()
                &&level().getBlockState(p).getCollisionShape(level(),p).isEmpty();
    }
    private boolean corridor(BlockPos p,Direction d,boolean barricaded){
        Direction side=d.getClockWise();
        return solid(p.below())&&solid(p.above(2))&&air(p.above())&&(barricaded?solid(p):air(p))
                &&solid(p.relative(side))&&solid(p.relative(side).above())
                &&solid(p.relative(side.getOpposite()))&&solid(p.relative(side.getOpposite()).above());
    }
    private boolean appropriate(Entity e){
        if(!(e instanceof Mob m)||!e.isAlive()||m.getTarget()!=self||!Senses.isHostileTo(e,self)
                ||e.getBbHeight()<=1.05||e instanceof Creeper||e instanceof Spider||e instanceof RangedAttackMob
                ||e instanceof net.minecraft.world.entity.FlyingMob||e instanceof EnderMan)return false;
        var k=self.getCloneBrain().knowledgeIfPresent(Perception.typeId(e));
        return !self.controller().ground().area(e)&&(k==null||!k.isRanged()&&!k.has(com.rlclones.ai.brain.EnemyKnowledge.CLIMBS)
                &&!k.has(com.rlclones.ai.brain.EnemyKnowledge.FLIES)&&!k.has(com.rlclones.ai.brain.EnemyKnowledge.TELEPORTS));
    }
    private boolean friendlyBehind(){
        for(var p:level().players())if(p!=self&&p.isAlive()&&Senses.isAllyOf(p,self)&&p.distanceToSqr(self)<100
                &&(p.getX()-block.getX()-.5)*facing.getStepX()+(p.getZ()-block.getZ()-.5)*facing.getStepZ()>-.5)return true;
        return false;
    }
    private boolean owned(){return block!=null&&placedState!=null&&level().getBlockState(block)==placedState;}
    private void abandon(long now){trial.finish(now,placedState!=null);enemy=null;retryAt=now+100;quietSince=now;cleanupUntil=now+600;}
    public boolean tick(Entity e,long now){
        if(self.isPassenger()||self.isInWater()||self.phasing||self.isCreative()||self.getBbWidth()>=1||self.getBbHeight()>2
                ||!com.rlclones.clone.CloneMobs.canUseHands(self))return false;
        if(block!=null&&enemy==null)return false;
        if(block!=null){
            if(e!=enemy||!appropriate(e)||now-started>240||self.getHealth()<initialHealth-3
                    ||!corridor(stand,facing,false)||!corridor(stand.relative(facing),facing,false)
                    ||!corridor(block,facing,placedState!=null)||friendlyBehind()
                    ||!Config.get(Config.ALLOW_BLOCK_PLACING,true)){
                abandon(now);return false;
            }
            if(placedState!=null&&!owned()){reset();retryAt=now+100;return false;}
        }else{
            if(now<retryAt||!self.onGround()||!appropriate(e)||!trial.allowed(e,"tunnel_barricade",now)||Math.abs(e.getY()-self.getY())>.45
                    ||e.distanceTo(self)>6||e.distanceTo(self)<2.8||self.getHealth()<self.getMaxHealth()*.6
                    ||!Config.get(Config.ALLOW_BLOCK_PLACING,true)||!Config.get(Config.ALLOW_BLOCK_BREAKING,true)
                    ||Equipment.pillarBlockSlot(self)<0)return false;
            retryAt=now+60;
            Direction d=Direction.getNearest(e.getX()-self.getX(),0,e.getZ()-self.getZ());
            BlockPos p=self.blockPosition(),b=p.relative(d,2),back=p.relative(d.getOpposite());
            // Require a genuine one-wide tunnel, an intact low roof and a free retreat cell.
            if(!corridor(p,d,false)||!corridor(p.relative(d),d,false)||!corridor(b,d,false)
                    ||!corridor(back,d,false)||!corridor(b.relative(d),d,false))return false;
            double along=(e.getX()-b.getX()-.5)*d.getStepX()+(e.getZ()-b.getZ()-.5)*d.getStepZ();
            double across=Math.abs((e.getX()-b.getX()-.5)*d.getStepZ()-(e.getZ()-b.getZ()-.5)*d.getStepX());
            if(along<.65||across>.6||!level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(b).inflate(.02),x->x.isAlive()).isEmpty())return false;
            block=b;stand=p;facing=d;enemy=e;started=now;initialHealth=self.getHealth();placedState=null;
            if(friendlyBehind()){reset();return false;}
        }
        motor.stop();motor.cancelJump();
        // Build from the original cell, then move one cell closer to hit through the gap.
        BlockPos fightingCell=placedState==null?stand:stand.relative(facing);
        if(self.position().distanceToSqr(Vec3.atBottomCenterOf(fightingCell))>.12){
            motor.movePrecisely(Vec3.atBottomCenterOf(fightingCell));return true;
        }
        if(placedState==null){
            int slot=Equipment.pillarBlockSlot(self);
            if(slot<0||!level().getBlockState(block).canBeReplaced()
                    ||!level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(block),x->x.isAlive()).isEmpty()){
                reset();retryAt=now+100;return false;
            }
            Equipment.select(self,slot);
            if(motor.placeBlockAt(block)&&solid(block)){placedState=level().getBlockState(block);placed++;trial.begin(e,"tunnel_barricade",now);}
            return true;
        }
        // Aim through the upper gap; still require the normal crosshair ray and attack cooldown.
        Vec3 eye=self.getEyePosition(), aim=Motor.closestPoint(e.getBoundingBox(),eye);
        aim=new Vec3(aim.x,Math.max(e.getBoundingBox().minY+.1,Math.min(e.getBoundingBox().maxY-.05,stand.getY()+1.55)),aim.z);
        motor.lookAt(aim);
        if(motor.canHit(e)&&self.getAttackStrengthScale(.5f)>=.95f){
            Equipment.manage(self,true);motor.attack(e);hits++;
        }
        return true;
    }
    /** Remove only the block we placed, after the fighting area is quiet. */
    public boolean recover(long now){
        if(block==null)return false;
        if(enemy!=null)abandon(now);
        if(!owned()||now>cleanupUntil||self.blockPosition().distSqr(block)>36){reset();return false;}
        if(!level().getEntitiesOfClass(Mob.class,new AABB(block).inflate(6),e->e.isAlive()&&Senses.isHostileTo(e,self)).isEmpty()){quietSince=now;return false;}
        if(now-quietSince<60||!Config.get(Config.ALLOW_BLOCK_BREAKING,true))return false;
        if(self.getEyePosition().distanceTo(Vec3.atCenterOf(block))>Motor.BLOCK_REACH)return false;
        motor.stop();Equipment.select(self,Equipment.bestToolSlot(self,placedState));
        if(motor.mine(block)){removed++;reset();}
        return true;
    }
    public void reset(){trial.cancel();block=stand=null;facing=null;enemy=null;placedState=null;}
}
