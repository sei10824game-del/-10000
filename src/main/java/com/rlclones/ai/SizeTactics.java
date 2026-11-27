package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Close-range tactics using a real low roof or a two-block-deep fighting hole. */
public final class SizeTactics {
    private final ClonePlayer self;
    private final Motor motor;
    private final TacticTrial trial;
    private BlockPos shelter, origin, hop;
    private Entity enemy;
    private long until, retryAt;
    private boolean leaving;
    public int shelters, pits, hits;

    public SizeTactics(ClonePlayer self, Motor motor) { this.self=self; this.motor=motor; this.trial=new TacticTrial(self); }
    private ServerLevel level(){return self.serverLevel();}
    private boolean fits(BlockPos p,double w,double h){
        double r=w*.5;
        return !level().getBlockCollisions(self,new AABB(p.getX()+.5-r,p.getY()+.01,p.getZ()+.5-r,
                p.getX()+.5+r,p.getY()+h,p.getZ()+.5+r)).iterator().hasNext();
    }
    private boolean dry(BlockPos p){return level().hasChunkAt(p)&&level().getFluidState(p).isEmpty();}
    private boolean floor(BlockPos p){return dry(p)&&level().getBlockState(p).isFaceSturdy(level(),p,Direction.UP);}
    private boolean chased(Entity e){return e instanceof Mob m&&m.getTarget()==self&&Senses.isHostileTo(e,self);}
    private boolean unsuitable(Entity e){
        var k=self.getCloneBrain().knowledgeIfPresent(Perception.typeId(e));
        return e instanceof Creeper||e instanceof RangedAttackMob||e instanceof net.minecraft.world.entity.FlyingMob||e instanceof Spider
                ||self.controller().ground().area(e)||k!=null&&k.isRanged();
    }
    private boolean roof(BlockPos p,Entity e){
        return dry(p)&&dry(p.above())&&floor(p.below())&&fits(p,self.getBbWidth(),self.getBbHeight())
                &&!level().getBlockState(p.above(2)).getCollisionShape(level(),p.above(2)).isEmpty()
                &&!fits(p,self.getBbWidth(),e.getBbHeight());
    }
    private BlockPos findRoof(Entity e){
        BlockPos base=self.blockPosition(),best=null;double score=Double.MAX_VALUE;
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos p=base.offset(dx,0,dz);
            double d=self.position().distanceToSqr(Vec3.atBottomCenterOf(p));
            if(d>=score||d>12||!roof(p,e)||Vec3.atBottomCenterOf(p).distanceTo(e.position())>6)continue;
            if(d>.5&&motor.pathTo(Vec3.atBottomCenterOf(p))==null)continue;
            best=p;score=d;
        }
        return best;
    }
    private boolean diggable(BlockPos p){
        BlockState s=level().getBlockState(p);
        if(!dry(p)||s.isAir()||s.hasBlockEntity()||s.getBlock() instanceof FallingBlock
                ||s.getDestroySpeed(level(),p)<0||s.getDestroySpeed(level(),p)>4
                ||s.is(net.minecraftforge.common.Tags.Blocks.ORES)||!self.mayInteract(level(),p))return false;
        if(s.requiresCorrectToolForDrops()&&Equipment.bestToolSlot(self,s)<0)return false;
        for(Direction d:Direction.values())if(!dry(p.relative(d)))return false;
        return true;
    }
    private boolean canDig(Entity e){
        BlockPos p=self.blockPosition();
        if(e.getBbWidth()<=1||self.getBbWidth()>=1||self.getBbHeight()>2
                ||!Config.get(Config.ALLOW_BLOCK_BREAKING,true)||!Config.get(Config.ALLOW_BLOCK_PLACING,true)
                ||!diggable(p.below())||!diggable(p.below(2))||!floor(p.below(3)))return false;
        if(com.rlclones.clone.Bases.get(self.getServer()).nearest(level().dimension(),self.position(),16)!=null)return false;
        for(int n=1;n<=2;n++)for(Direction d:Direction.Plane.HORIZONTAL)
            if(!floor(p.below(n).relative(d)))return false;
        int blocks=0;for(var s:self.getInventory().items)if(Equipment.isBuildingBlock(self,s))blocks+=s.getCount();
        return blocks>=2;
    }
    private void hit(Entity e){
        motor.stop();motor.lookAt(e);
        if(motor.canHit(e)&&self.getAttackStrengthScale(.5f)>=.95f){Equipment.manage(self,true);motor.attack(e);hits++;}
    }
    public boolean tick(Entity e,long now){
        if(self.isPassenger()||self.isInWater()||self.phasing||self.isCreative()){reset();return false;}
        if(origin!=null){
            if(leaving||e==null||e!=enemy||!e.isAlive()||now>=until||!chased(e)||self.getHealth()<self.getMaxHealth()*.45f){trial.finish(now,true);leaving=true;return exit(now);}
            if(!self.onGround()){motor.stop();return true;}
            int depth=origin.getY()-self.blockPosition().getY();
            if(depth<0||self.blockPosition().getX()!=origin.getX()||self.blockPosition().getZ()!=origin.getZ()){reset();return false;}
            if(depth>=2){hit(e);return true;}
            BlockPos p=origin.below(depth+1);
            if(!diggable(p)||!self.controller().safeToDig(p)){leaving=true;return exit(now);}
            motor.stop();motor.cancelJump();
            Equipment.select(self,Equipment.bestToolSlot(self,level().getBlockState(p)));
            motor.mine(p);return true;
        }
        if(shelter!=null){
            if(e==null||e!=enemy||!e.isAlive()||!chased(e)||now>=until||!roof(shelter,e)||unsuitable(e)){trial.finish(now,true);reset();retryAt=now+60;return false;}
            Vec3 goal=Vec3.atBottomCenterOf(shelter);
            if(self.position().distanceToSqr(goal)>.16){motor.navigate(goal,.25,false);return true;}
            hit(e);return true;
        }
        if(e==null||now<retryAt||!self.onGround()||!chased(e)||unsuitable(e)||e.distanceTo(self)>7
                ||self.getHealth()<self.getMaxHealth()*.65f)return false;
        retryAt=now+60;
        if(e.getBbHeight()>2.1&&self.getBbHeight()<2&&trial.allowed(e,"low_roof",now)){
            BlockPos p=findRoof(e);
            if(p!=null){shelter=p;enemy=e;until=now+200;shelters++;trial.begin(e,"low_roof",now);return true;}
        }
        if(trial.allowed(e,"fighting_pit",now)&&canDig(e)){
            origin=self.blockPosition().immutable();enemy=e;until=now+180;pits++;trial.begin(e,"fighting_pit",now);return true;
        }
        return false;
    }
    /** Finish climbing out even when the target dies or the combat option changes. */
    public boolean recover(long now){
        if(shelter!=null){trial.finish(now,true);reset();return false;}
        if(origin==null)return false;
        trial.finish(now,true);leaving=true;return exit(now);
    }
    private boolean exit(long now){
        if(self.getY()>=origin.getY()-.05&&self.onGround()){reset();retryAt=now+80;return false;}
        if(now>until+160||!Config.get(Config.ALLOW_BLOCK_PLACING,true)||Equipment.pillarBlockSlot(self)<0){reset();return false;}
        motor.stop();
        if(self.onGround()){hop=self.blockPosition();motor.jump();}
        else if(hop!=null&&self.getY()>=hop.getY()+1&&level().getBlockState(hop).canBeReplaced()){
            Equipment.select(self,Equipment.pillarBlockSlot(self));motor.useOnTopFace(hop.below());hop=null;
        }
        return true;
    }
    public void reset(){trial.cancel();origin=shelter=hop=null;enemy=null;leaving=false;motor.resetMining();}
}
