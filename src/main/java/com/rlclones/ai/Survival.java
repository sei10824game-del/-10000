package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

/** Per-clone, bounded combat window and terminal retreat trials. No world-wide scans. */
public final class Survival {
    private final ClonePlayer self;
    private final float[] hurt=new float[5], dealt=new float[5];
    private final long[] buckets={Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE};
    private long holdUntil;
    private float recoverHealth;
    private Brain trialBrain;
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> trialDimension;
    private long started;
    private int state,method=-1,quiet;
    private float startHealth,trialDamage;
    private Vec3 destination,startPosition;
    private String lastOutcome="";
    private final Random random=new Random();
    public Survival(ClonePlayer self){this.self=self;}
    public void damage(LivingEntity attacker,float amount,long now){
        if(!Float.isFinite(amount)||amount<=0)return;
        add(now,amount,false);trialDamage+=method>=0?amount:0;
        if(attacker!=null&&Senses.isHostileTo(attacker,self))self.getCloneBrain().knowledge(Perception.typeId(attacker)).receivedDamage.add(amount);
    }
    public void dealt(float amount,long now){if(Float.isFinite(amount)&&amount>0)add(now,amount,true);}
    private void add(long now,float amount,boolean outgoing){long b=now/20;int i=(int)Math.floorMod(b,5);if(buckets[i]!=b){buckets[i]=b;hurt[i]=dealt[i]=0;}if(outgoing)dealt[i]+=amount;else hurt[i]+=amount;}
    private float sum(long now,boolean outgoing){float n=0;for(int i=0;i<5;i++)if(now/20-buckets[i]>=0&&now/20-buckets[i]<5)n+=(outgoing?dealt:hurt)[i];return n;}
    public static boolean losing(float hp,float max,float received,float outgoing,int enemies,int armor,boolean outmatched){
        return hp<=max*.35f || received>=Math.max(3,hp*.3f)&&received>outgoing*1.4f
                || enemies>=3&&armor<10&&received>0 || outmatched&&hp<max*.7f&&received>0;
    }
    public boolean retreat(long now,int enemies,boolean outmatched){
        if(self.isCreative()||enemies==0)return false;
        if(losing(self.getHealth(),self.getMaxHealth(),sum(now,false),sum(now,true),enemies,self.getArmorValue(),outmatched)){
            holdUntil=now+200;recoverHealth=Math.max(recoverHealth,Math.min(self.getMaxHealth()*.8f,self.getHealth()+4));return true;
        }
        return now<holdUntil&&self.getHealth()<recoverHealth;
    }
    public void tick(long now,boolean threatened){
        if(method<0)return;
        if(self.getCloneBrain()!=trialBrain||self.level().dimension()!=trialDimension){cancel();return;}
        quiet=threatened?0:quiet+1;
        if(!self.isAlive()||quiet>=40||now-started>=120)finish(!threatened&&quiet>=40&&self.isAlive());
    }
    private void finish(boolean safe){
        if(method<0)return;
        if(self.getCloneBrain()!=trialBrain||self.level().dimension()!=trialDimension){cancel();return;}
        boolean success=safe&&startPosition!=null&&self.position().distanceToSqr(startPosition)>=1
                &&trialDamage<Math.max(2,startHealth*.2f);
        float reward=success?1f:-.4f;
        reward-=Math.min(1.5f,trialDamage/Math.max(2,startHealth));
        if(!self.isAlive())reward=-2;
        lastOutcome="safe="+safe+" quiet="+quiet+" damage="+trialDamage+" moved="+self.position().distanceTo(startPosition);
        trialBrain.retreat.record(state,method,reward,success);cancel();
    }
    public void died(){
        if(method>=0&&self.getCloneBrain()==trialBrain&&self.level().dimension()==trialDimension)trialBrain.retreat.record(state,method,-2,false);
        cancel();
    }
    public void blocked(){finish(false);}
    public void cancel(){method=-1;destination=null;trialBrain=null;quiet=0;trialDamage=0;}
    public boolean active(){return method>=0;}
    public Vec3 currentGoal(long now){return method>=0&&now-started<120?destination:null;}
    public Vec3 choose(Vec3[] candidates,int context,long now){
        if(method>=0&&destination!=null&&now-started<120)return destination;
        int mask=0;for(int i=0;i<4;i++)if(candidates[i]!=null)mask|=1<<i;
        if(mask==0)return null;
        // At critical health use a validated direct/cover route, never an exploratory detour.
        int selected=self.getHealth()<=self.getMaxHealth()*.35f?((mask&2)!=0?1:Integer.numberOfTrailingZeros(mask))
                :self.getCloneBrain().retreat.choose(context,mask,random);
        method=selected;state=context;trialBrain=self.getCloneBrain();trialDimension=self.level().dimension();started=now;startPosition=self.position();startHealth=self.getHealth();trialDamage=0;quiet=0;
        return destination=candidates[selected];
    }
    public String report(){return self.getCloneBrain().retreat.report("撤退の生還学習",new String[]{"離脱","遮蔽","合流","高所"})+" 実行="+method+" "+lastOutcome;}
}
