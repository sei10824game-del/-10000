package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Local witnessed combat interrupts work; dangerous enemies are met together rather than one at a time. */
public final class TeamDefense {
    private static final Map<UUID,Long> CALLS=new LinkedHashMap<>();
    private record Notice(UUID victim,UUID attacker){}
    private static final Map<Notice,Long> NOTICES=new LinkedHashMap<>();
    private final ClonePlayer self;private final Perception perception;private final Motor motor;
    private ClonePlayer rescueVictim;
    private Vec3 rescuePosition;
    private final RescueRoute rescueRoute;
    private Entity alerted,stageEnemy,intent;private long alertUntil,stageCheck,intentAt=Long.MIN_VALUE;private int ready,required;private Vec3 rallyGoal;
    public int alerts,joins,regroups;public String debug="";
    public TeamDefense(ClonePlayer self,Perception perception,Motor motor){this.self=self;this.perception=perception;this.motor=motor;this.rescueRoute=new RescueRoute(self,motor);}
    public static void clear(){CALLS.clear();NOTICES.clear();}
    public boolean rescuePending(long now){return rescueVictim!=null&&rescueVictim!=self&&rescueVictim.isAlive()&&!rescueVictim.isRemoved()&&rescueVictim.level()==self.level()&&rescuePosition!=null&&now<alertUntil&&alerted!=null&&alerted.isAlive()&&alerted.level()==self.level()&&self.position().distanceToSqr(rescuePosition)<32*32&&(rescuePosition.y-self.getY()>2.5||rescueRoute.active());}
    public boolean rescueFor(Entity e,long now){return e==alerted&&rescuePending(now);}
    public boolean rescueTick(long now){if(!rescuePending(now)){rescueRoute.reset();return false;}return rescueRoute.tick(rescuePosition,now);}
    public boolean navigateHelp(Vec3 position,long now){return rescueRoute.tick(position,now);}
    public boolean marked(Entity e,long now){return e!=null&&e==alerted&&now<alertUntil&&e.isAlive()&&e.level()==self.level()&&(rescueFor(e,now)||!self.controller().unreachable(e,now));}
    public Entity visible(long now){
        if(intentAt==now)return intent!=null&&self.controller().unreachable(intent,now)?null:intent;intentAt=now;
        Entity best=null;double distance=24*24;
        for(var seen:perception.visible())if(seen.alive()&&now-seen.lastSeen<=10&&Senses.isHostileTo(seen.entity,self)){
            double d=seen.entity.distanceToSqr(self);if(d<distance){distance=d;best=seen.entity;}}
        if(marked(alerted,now)&&alerted.distanceToSqr(self)<24*24&&perception.canSee(alerted))best=alerted;
        return intent=best;
    }
    public boolean engage(long now){return rescuePending(now)||visible(now)!=null||marked(alerted,now)&&alerted.distanceToSqr(self)<24*24;}
    public static void attacked(ClonePlayer victim,Entity attacker,long now){attacked(victim,attacker,now,0);}
    public static void attacked(ClonePlayer victim,Entity attacker,long now,float amount){
        if(attacker==null||attacker==victim||!attacker.isAlive()||Senses.isAllyOf(attacker,victim)||com.rlclones.clone.CloneMobs.playerSide(attacker))return;
        if(CALLS.containsKey(attacker.getUUID())&&now-CALLS.get(attacker.getUUID())<10)return;
        if(CALLS.size()>=256)CALLS.remove(CALLS.keySet().iterator().next());CALLS.put(attacker.getUUID(),now);
        boolean published=false;
        for(Player p:victim.level().players())if(p instanceof ClonePlayer c&&c.isAiEnabled()&&Senses.isAllyOf(c,victim)&&c.distanceToSqr(victim)<24*24){
            if(c!=victim&&c.distanceToSqr(victim)>8*8&&!c.controller().perception().canSee(attacker)&&!(c.distanceToSqr(victim)<16*16&&c.controller().perception().canSee(victim))&&!(victim.getY()-c.getY()>2.5&&c.distanceToSqr(victim)<16*16))continue;
            if(c!=victim&&!published){
                published=true;
                Notice key=new Notice(victim.getUUID(),attacker.getUUID());Long previous=NOTICES.get(key);
                if(previous==null||now<previous||now-previous>=100){
                    var pos=victim.blockPosition();
                    Chat.publish(victim,net.minecraft.network.chat.Component.literal("敵と交戦中！ "+pos.toShortString()+" に応援を頼む。敵: ").append(attacker.getType().getDescription()),"DEFENSE "+pos.getX()+" "+pos.getY()+" "+pos.getZ()+" "+Perception.typeId(attacker));
                    if(NOTICES.size()>=256&&!NOTICES.containsKey(key))NOTICES.remove(NOTICES.keySet().iterator().next());
                    NOTICES.put(key,now);
                }
            }
            if(c!=victim&&amount>0)c.getCloneBrain().knowledge(Perception.typeId(attacker)).receivedDamage.add(amount);
            var defense=c.controller().defense();defense.intentAt=Long.MIN_VALUE;defense.alerted=attacker;defense.alertUntil=now+(victim.getY()-c.getY()>2.5?400:100);defense.rescueVictim=victim;defense.rescuePosition=victim.position();defense.alerts++;
            c.controller().perception().hear(attacker,attacker.position(),now);
        }
    }
    private boolean line(Vec3 from,Vec3 to){return self.level().clip(new net.minecraft.world.level.ClipContext(from,to,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,self)).getType()==net.minecraft.world.phys.HitResult.Type.MISS;}
    private boolean supporting(ClonePlayer c,Entity enemy){return c==self||c.distanceToSqr(self)<=36&&line(self.getEyePosition(),c.getEyePosition())||Equipment.rangedSlot(c)>=0&&line(c.getEyePosition(),enemy.getEyePosition());}
    public int required(Entity enemy){
        var k=self.getCloneBrain().knowledgeIfPresent(Perception.typeId(enemy));
        if(enemy instanceof LivingEntity e&&e.getMaxHealth()>self.getMaxHealth()*3)return 3;
        if(k!=null&&(k.agentsKilled>=3||k.receivedDamage.known()&&k.receivedDamage.get()>=self.getMaxHealth()*.4))return 3;
        if(k!=null&&(k.agentsKilled>0||k.receivedDamage.known()&&k.receivedDamage.get()>=self.getMaxHealth()*.25))return 2;
        return 1;
    }
    public boolean regroup(Entity enemy,long now){
        if(now>=stageCheck||stageEnemy!=enemy){stageEnemy=enemy;stageCheck=now+5;required=required(enemy);ready=0;rallyGoal=null;
            if(required<=1)return false;
            for(Player p:self.level().players())if(p instanceof ClonePlayer c&&c.isAiEnabled()&&Senses.isAllyOf(c,self)&&c.getHealth()>=c.getMaxHealth()*.5&&c.distanceToSqr(enemy)<16*16&&c.controller().option()!=com.rlclones.ai.strategy.Option.FLEE&&c.controller().forcedOption!=com.rlclones.ai.strategy.Option.FLEE&&supporting(c,enemy)
                    &&(c==self||c.controller().option()==com.rlclones.ai.strategy.Option.FIGHT||c.controller().defense().marked(enemy,now)||c.controller().defense().visible(now)==enemy)){if(++ready>=required)break;}
        }
        if(required<=1||ready>=required)return false;
        if(motor.stuckCount()>=2){debug="rally blocked: defend in place";return false;}
        if(motor.stuckCount()>0)motor.holdJumps();
        double gap=Senses.gap(self,enemy);
        // Ranged allies still contribute fire from a safe distance while the melee line assembles.
        if(Equipment.rangedSlot(self)>=0&&gap>=6)return false;
        if(rallyGoal!=null){motor.navigate(rallyGoal,1,true);regroups++;return true;}
        Vec3 away=self.position().subtract(enemy.position()).multiply(1,0,1).normalize();
        if(away.lengthSqr()<.01)away=self.getLookAngle().multiply(1,0,1).normalize().scale(-1);
        Player ally=null;double nearest=24*24;
        for(Player p:self.level().players())if(p!=self&&p instanceof ClonePlayer c&&c.isAiEnabled()&&Senses.isAllyOf(p,self)&&p.getHealth()>=p.getMaxHealth()*.5&&p.distanceToSqr(self)<nearest){nearest=p.distanceToSqr(self);ally=p;}
        Vec3 goal=ally!=null&&ally.distanceTo(enemy)>self.distanceTo(enemy)+1?ally.position():self.position().add(away.scale(6));
        rallyGoal=goal;motor.navigate(goal,1,true);regroups++;debug="rally "+ready+"/"+required;return true;
    }
    public void joined(){joins++;}
    public String report(){return "近隣防衛: 通知="+alerts+" 参戦="+joins+" 合流="+regroups+" "+debug+" "+rescueRoute.report();}
}
