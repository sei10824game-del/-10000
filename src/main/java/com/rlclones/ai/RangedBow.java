package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/** Physics and safety constrain every shot; learning only chooses target-motion lead. */
public final class RangedBow {
    private final ClonePlayer self;private final Motor motor;
    private Entity target;private int method=2,state;private long selectedAt;
    private BowPhysics.Aim cachedAim;private Vec3 cachedOrigin,cachedTarget,cachedMotion,cachedInherited;private int cachedDraw;private boolean cachedHigh;private long cachedAt;
    private final Random random=new Random();
    private UUID positionEnemy;
    private Vec3 positionGoal;private long positionUntil,nextSearch;
    private final Map<UUID,Flight> flights=new HashMap<>();
    private record Flight(Brain brain,net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,int state,int method,UUID target,long start,AbstractArrow arrow){}
    private final Map<UUID,Float> hits=new HashMap<>();private final Set<UUID> friendly=new HashSet<>();private final Map<UUID,Long> impacts=new HashMap<>();
    public int repositioned,blocked;public boolean friendBlocked;private Entity pending;private long pendingAt;
    public RangedBow(ClonePlayer self,Motor motor){this.self=self;this.motor=motor;}
    public Vec3 origin(){return self.getEyePosition().add(0,-.1,0);}
    public Vec3 inherited(){Vec3 v=self.getDeltaMovement();return new Vec3(v.x,self.onGround()?0:v.y,v.z);}
    private List<? extends net.minecraft.world.entity.player.Player> friends(){return self.level().players().stream().filter(p->p!=self&&p.isAlive()&&Senses.isAllyOf(p,self)).toList();}
    public Entity friendInLine(Entity enemy,Vec3 eye){
        Vec3 end=enemy.getBoundingBox().getCenter();
        for(var p:friends())if(p!=enemy&&p.getBoundingBox().expandTowards(velocity(p).scale(8)).inflate(.6).clip(eye,end).isPresent())return p;
        return null;
    }
    private boolean positionSafe(Vec3 spot,Entity enemy){
        BlockPos p=BlockPos.containing(spot);var level=self.serverLevel();
        if(!level.hasChunkAt(p)||!level.getFluidState(p).isEmpty()||!level.getFluidState(p.below()).isEmpty()
                ||!level.getBlockState(p).getCollisionShape(level,p).isEmpty()||!level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()
                ||!level.getBlockState(p.below()).isFaceSturdy(level,p.below(),net.minecraft.core.Direction.UP))return false;
        var floor=level.getBlockState(p.below());var block=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(floor.getBlock());
        if(floor.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)||floor.is(net.minecraft.tags.BlockTags.FIRE)||floor.is(net.minecraft.world.level.block.Blocks.CACTUS)||block!=null&&self.getCloneBrain().isHarmful(block.toString()))return false;
        Vec3 eye=spot.add(0,self.getEyeHeight()-.1,0);
        if(spot.distanceTo(enemy.position())<Math.min(4,self.distanceTo(enemy))||friendInLine(enemy,eye)!=null)return false;
        return level.clip(new ClipContext(eye,enemy.getBoundingBox().getCenter(),ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,self)).getType()==HitResult.Type.MISS&&motor.pathTo(spot)!=null;
    }
    /** Prefer a clear low shot from the side. Searches are throttled and paths are reused. */
    public boolean reposition(Entity enemy,long now){return reposition(enemy,now,false);}
    public boolean reposition(Entity enemy,long now,boolean force){
        if(!enemy.getUUID().equals(positionEnemy)){positionGoal=null;positionEnemy=enemy.getUUID();nextSearch=0;}
        if(!force&&positionGoal==null&&friendInLine(enemy,origin())==null)return false;

        if(positionGoal!=null&&(now>=positionUntil||self.position().distanceToSqr(positionGoal)<.5))positionGoal=null;
        if(!force&&positionGoal==null&&friendInLine(enemy,origin())==null)return false;
        if(positionGoal==null&&now>=nextSearch){
            nextSearch=now+20;Vec3 forward=enemy.position().subtract(self.position()).multiply(1,0,1).normalize();Vec3 side=new Vec3(-forward.z,0,forward.x);
            outer:for(double distance:new double[]{2,4})for(int sign:new int[]{1,-1})for(int dy:new int[]{0,1,-1}){
                Vec3 candidate=self.position().add(side.scale(distance*sign)).add(0,dy,0);candidate=new Vec3(Math.floor(candidate.x)+.5,Math.floor(candidate.y),Math.floor(candidate.z)+.5);
                if(positionSafe(candidate,enemy)){positionGoal=candidate;positionUntil=now+60;repositioned++;break outer;}
            }
        }
        if(positionGoal!=null){self.stopUsingItem();pending=null;motor.navigate(positionGoal,.4,false);return true;}
        return false;
    }
    private Vec3 velocity(Entity entity){Vec3 v=entity.getDeltaMovement();return new Vec3(v.x,entity.onGround()?0:v.y,v.z);}
    private Vec3 motion(Entity enemy){Vec3 v=velocity(enemy);if(v.length()>.65)v=v.normalize().scale(.65);double[] lead={0,.6,1,1.25};return v.scale(lead[method]);}
    public BowPhysics.Aim aim(Entity enemy,int draw,boolean high,long now){
        if(target!=enemy||now-selectedAt>100||((state&4)!=0)!=high){cachedAim=null;cachedOrigin=null;target=enemy;selectedAt=now;double range=self.distanceTo(enemy);state=(range>16?1:0)|(enemy.getDeltaMovement().horizontalDistance()>.15?2:0)|(high?4:0);
            method=velocity(enemy).lengthSqr()<.001?2:self.getCloneBrain().bowAim.choose(state,15,random);}
        Vec3 origin=origin(),at=enemy.getBoundingBox().getCenter(),motion=motion(enemy),inertia=inherited();
        if(cachedOrigin!=null&&now-cachedAt<3&&cachedDraw==draw&&cachedHigh==high&&origin.distanceToSqr(cachedOrigin)<.0025&&at.distanceToSqr(cachedTarget)<.25&&motion.distanceToSqr(cachedMotion)<.0001&&inertia.distanceToSqr(cachedInherited)<.0001)return cachedAim;
        cachedAt=now;cachedDraw=draw;cachedHigh=high;cachedOrigin=origin;cachedTarget=at;cachedMotion=motion;cachedInherited=inertia;
        return cachedAim=BowPhysics.solve(origin,at,motion,inertia,BowPhysics.speed(draw),high);
    }
    public int lobDraw(Entity enemy,long now){
        for(int draw=4;draw<=20;draw+=2){var a=aim(enemy,draw,true,now);if(a!=null&&a.flight()<=65&&a.pitch()<=-30&&a.pitch()>=-75)return draw;}return 0;
    }
    /** Validate the actual release orientation, spread envelope, moving friends, blocks and target intersection. */
    public boolean safe(Entity enemy,int draw){friendBlocked=false;return trace(enemy,BowPhysics.speed(draw),0,true,false);}
    public BowPhysics.Aim boltAim(Entity enemy){return BowPhysics.solve(origin(),enemy.getBoundingBox().getCenter(),motion(enemy),inherited(),3.15,false);}
    public boolean safeBolt(Entity enemy,boolean multi,boolean rocket){
        friendBlocked=false;
        if(rocket)for(var p:friends())if(p.distanceTo(enemy)<5){friendBlocked=true;return false;}
        return trace(enemy,3.15,0,!rocket,rocket)&&(!multi||trace(enemy,3.15,10,false,rocket)&&trace(enemy,3.15,-10,false,rocket));
    }
    private boolean trace(Entity enemy,double speed,double angle,boolean requireHit,boolean rocket){
        Vec3 pos=origin(),velocity=self.getViewVector(1).yRot((float)Math.toRadians(angle)).scale(speed).add(inherited());
        var allies=friends();boolean reaches=false;double travelled=0;
        for(int t=0;t<120;t++){
            Vec3 next=pos.add(velocity);travelled+=velocity.length();
            HitResult wall=self.level().clip(new ClipContext(pos,next,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,self));
            Vec3 end=wall.getType()==HitResult.Type.MISS?next:wall.getLocation();
            double margin=.35+Math.min(2.5,travelled*.025)+Math.min(1,t*.025);
            for(var ally:allies){AABB box=ally.getBoundingBox().move(velocity(ally).scale(Math.min(t,10))).inflate(margin);
                if(box.contains(pos)||box.clip(pos,end).isPresent()){friendBlocked=true;blocked++;return false;}}
            AABB predicted=enemy.getBoundingBox().move(motion(enemy).scale(t)).inflate(.15);
            if(predicted.contains(pos)||predicted.clip(pos,end).isPresent())reaches=true;
            if(wall.getType()!=HitResult.Type.MISS){
                if(rocket)for(var ally:allies)if(ally.position().distanceTo(end)<5){friendBlocked=true;return false;}
                return !requireHit||reaches;
            }
            pos=next;velocity=rocket?velocity:BowPhysics.stepVelocity(velocity);
        }
        return !requireHit||reaches;
    }
    public void releasing(Entity enemy,long now){pending=enemy;pendingAt=now;}
    public void launched(AbstractArrow arrow,long now){
        if(pending==null||now-pendingAt>1||flights.size()>=16)return;
        selectedAt=now-101;
        flights.put(arrow.getUUID(),new Flight(self.getCloneBrain(),self.level().dimension(),state,method,pending.getUUID(),now,arrow));pending=null;
    }
    public void impact(AbstractArrow arrow,long now,HitResult result){if(flights.containsKey(arrow.getUUID())){impacts.put(arrow.getUUID(),now);if(result instanceof EntityHitResult hit&&Senses.isAllyOf(hit.getEntity(),self))friendly.add(arrow.getUUID());}}
    public void damage(AbstractArrow arrow,LivingEntity victim,float amount){
        Flight f=flights.get(arrow.getUUID());if(f==null||amount<=0)return;
        if(Senses.isAllyOf(victim,self))friendly.add(arrow.getUUID());else if(victim.getUUID().equals(f.target))hits.merge(arrow.getUUID(),amount,Float::sum);
    }
    public void tick(long now){
        for(var it=flights.entrySet().iterator();it.hasNext();){var e=it.next();Flight f=e.getValue();UUID id=e.getKey();
            boolean cancel=f.brain!=self.getCloneBrain()||f.dimension!=self.level().dimension();
            if(!cancel&&now-f.start<160&&!(impacts.containsKey(id)&&now-impacts.get(id)>=2)&&!(f.arrow.isRemoved()||f.arrow.onGround()))continue;
            if(!cancel){boolean bad=friendly.contains(id),hit=hits.getOrDefault(id,0f)>0;f.brain.bowAim.record(f.state,f.method,bad?-2:hit?1:-.4f,hit&&!bad);}
            hits.remove(id);friendly.remove(id);impacts.remove(id);it.remove();
        }
        if(pending!=null&&now-pendingAt>1)pending=null;
    }
}
