package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.world.entity.Entity;

/** One bounded tactical trial. Records actual outgoing damage, rather than mere attack attempts.
 * Uses existing saved/merged EnemyKnowledge traits; no new brain format or policy dimensions.
 */
public final class TacticTrial {
    private final ClonePlayer self;
    private Entity enemy;
    private String method;
    private double dealtBefore;
    private float healthBefore;
    private long started;
    public TacticTrial(ClonePlayer self){this.self=self;}
    public boolean allowed(Entity e,String name,long now){
        var k=self.getCloneBrain().knowledgeIfPresent(Perception.typeId(e));
        // Occasional retries let a clone adapt when equipment or terrain changes.
        return !AttackLearning.useless(k,"tactic:"+name)||(now+self.getId())%2400<60;
    }
    public void begin(Entity e,String name,long now){
        cancel();enemy=e;method=name;started=now;
        dealtBefore=self.controller().attacks().dealtTo(e);
        healthBefore=self.getHealth()+self.getAbsorptionAmount();
    }
    public void finish(long now,boolean engaged){
        if(enemy==null)return;
        if(engaged&&now-started>=10&&enemy.level()==self.level()){
            double dealt=self.controller().attacks().dealtTo(enemy)-dealtBefore;
            double hurt=Math.max(0,healthBefore-self.getHealth()-self.getAbsorptionAmount());
            boolean success=dealt>.1&&dealt>hurt;
            // No conclusion if nothing happened during a brief interruption.
            if(dealt>.1||hurt>.1||now-started>=120){
                self.getCloneBrain().knowledge(Perception.typeId(enemy)).trait((success?"effect:":"noeffect:")+"tactic:"+method);
            }
        }
        cancel();
    }
    public void cancel(){enemy=null;method=null;}
}
