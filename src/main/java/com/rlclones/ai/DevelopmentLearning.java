package com.rlclones.ai;

import com.rlclones.ai.brain.*;
import com.rlclones.ai.civilization.*;
import com.rlclones.ai.observe.AgentEvents;
import com.rlclones.ai.strategy.Option;
import com.rlclones.clone.*;
import java.util.Random;

/** Bounded preparation sequences and optional civic roles; critical controller priorities stay in control. */
public final class DevelopmentLearning {
    private final ClonePlayer self;
    private final Perception perception;
    private final Crafting crafting;
    private final Storage storage;
    private final Lighting lighting;
    private final CivicWork civic;
    private final Random random=new Random();
    private final long[] units=new long[4];
    private Trial workflow,role;
    private int prepTicks;
    private boolean preparing;
    private static final class Trial {
        final Brain owner;final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final long tick,before;final float health;final int state,method,domain,deliveries;
        final java.util.List<net.minecraft.world.item.ItemStack> tools=new java.util.ArrayList<>();
        final java.util.List<Integer> remaining=new java.util.ArrayList<>();
        Trial(ClonePlayer p,int domain,int state,int method,long units,int deliveries){owner=p.getCloneBrain();dimension=p.level().dimension();tick=p.level().getGameTime();health=p.getHealth();this.domain=domain;this.state=state;this.method=method;before=units;this.deliveries=deliveries;trackTools(p);}
        void trackTools(ClonePlayer p){for(var stack:p.getInventory().items)if(stack.getItem() instanceof net.minecraft.world.item.TieredItem&&stack.isDamageableItem()){
            boolean tracked=false;for(var old:tools)if(old==stack){tracked=true;break;}
            if(!tracked){tools.add(stack);remaining.add(stack.getMaxDamage()-stack.getDamageValue());}
        }}
    }
    public DevelopmentLearning(ClonePlayer self,Perception perception,Crafting crafting,Storage storage,Lighting lighting,CivicWork civic){this.self=self;this.perception=perception;this.crafting=crafting;this.storage=storage;this.lighting=lighting;this.civic=civic;}
    public void resource(){units[1]++;}
    public void food(){units[0]++;}
    public void crafted(){units[2]++;}
    public void start(Option option){
        if(option!=Option.MINE||self.isCreative())return;
        int mask=1;
        boolean dark=lighting.darkSpot();
        if(dark&&Lighting.torchSlot(self)>=0&&com.rlclones.Config.get(com.rlclones.Config.ALLOW_BLOCK_PLACING,true))mask|=2;
        if(Bases.get(self.getServer()).nearest(self.level().dimension(),self.position(),96)!=null&&storage.canStore()&&!Storage.unneededSlots(self).isEmpty())mask|=4;
        if(crafting.toolsPending()&&Crafting.plan(self)!=null)mask|=8;
        if(Integer.bitCount(mask)<2)return;
        int state=(dark?1:0)|(Storage.bagFull(self)?2:0)|(crafting.toolsPending()?4:0);
        int method=self.getCloneBrain().chooseMethod(2,state,mask,random);
        workflow=new Trial(self,2,state,method,units[1],civic.completed);prepTicks=0;preparing=method!=0;
        if(method==2)storage.begin(Storage.Mode.STORE);
    }
    public boolean interruptPreparation(long now){return preparing&&workflow!=null&&(workflow.owner!=self.getCloneBrain()
            ||workflow.dimension!=self.level().dimension()||self.getHealth()<self.getMaxHealth()*0.65f
            ||self.getFoodData().getFoodLevel()<=6||!Senses.threats(perception,self,now,24).isEmpty());}
    public boolean prepareTick(){
        if(!preparing||workflow==null)return false;
        if(++prepTicks>80){stopPreparation();return false;}
        boolean busy=switch(workflow.method){case 1->{lighting.tick();yield false;}case 2->storage.tick()==Storage.Status.WORKING;case 3->crafting.tick()!=Crafting.Status.DONE&&crafting.toolsPending();default->false;};
        if(!busy)stopPreparation();
        return busy;
    }
    private void stopPreparation(){if(workflow!=null){workflow.trackTools(self);if(workflow.method==2)storage.reset();if(workflow.method==3)crafting.reset();}preparing=false;}
    public Option roleChoice(int optionMask,long now){
        role=null;
        if(self.isCreative()||self.getHealth()<self.getMaxHealth()*0.65f||self.getFoodData().getFoodLevel()<=6
                ||!Senses.threats(perception,self,now,24).isEmpty())return null;
        boolean foodNeed=FoodAid.foodItems(self)<8,materialNeed=Progression.need(self)!=Progression.Need.NONE;
        for(var job:CloneManager.get(self.getServer()).workBoard().jobs(now)){
            if(!job.key.dimension().equals(self.level().dimension().location().toString())||!job.key.team().equals(CivicWork.team(self)))continue;
            var requester=self.getServer().getPlayerList().getPlayer(job.key.requester());
            if(requester==null||!requester.isAlive()||requester.level()!=self.level()||requester.distanceToSqr(self)>64*64
                    ||!CivicWork.team(requester).equals(CivicWork.team(self))||!Senses.isAllyOf(requester,self))continue;
            int held=0;for(var stack:requester.getInventory().items){if(WorkBoard.FOOD.equals(job.key.item())?FoodAid.goodFood(stack):ItemAid.matches(stack,job.key.item()))held+=stack.getCount();if(held>=job.count)break;}
            var offhand=requester.getOffhandItem();if(WorkBoard.FOOD.equals(job.key.item())?FoodAid.goodFood(offhand):ItemAid.matches(offhand,job.key.item()))held+=offhand.getCount();
            if(held>=job.count)continue;
            if(WorkBoard.FOOD.equals(job.key.item()))foodNeed=true;else materialNeed=true;
        }
        Option[] choices={pick(optionMask,Option.HUNT,Option.FARM),pick(optionMask,Option.MINE,Option.GATHER_WOOD),pick(optionMask,Option.CRAFT),civic.canHelp()?pick(optionMask,Option.FEED):null};
        int mask=0;
        for(int a=0;a<4;a++)if(choices[a]!=null&&(a!=0||foodNeed)&&(a!=1||materialNeed)){
            int others=0;
            for(var seen:perception.visible())if(seen.entity instanceof ClonePlayer clone&&clone!=self&&CivicWork.team(clone).equals(CivicWork.team(self))&&roleOf(clone.controller().option())==a)others++;
            if(others<2)mask|=1<<a;
        }
        if(Integer.bitCount(mask)<2)return null;
        int state=(foodNeed?1:0)|(materialNeed?2:0)|(Storage.bagFull(self)?4:0);
        int method=self.getCloneBrain().chooseMethod(3,state,mask,random);
        role=new Trial(self,3,state,method,units[method],civic.completed);
        return choices[method];
    }
    private static Option pick(int mask,Option... choices){for(var o:choices)if((mask&o.bit())!=0)return o;return null;}
    public static int roleOf(Option option){if(option==null)return -1;return switch(option){case HUNT,FARM->0;case MINE,GATHER_WOOD,QUARRY,STAIRS,SHAFT->1;case CRAFT->2;case FEED->3;default->-1;};}
    public void finish(boolean died,boolean completed){
        stopPreparation();
        if(workflow!=null)finish(workflow,units[1]-workflow.before,died,completed);
        if(role!=null)finish(role,role.method==3?civic.completed-role.deliveries:units[role.method]-role.before,died,completed);
        workflow=role=null;
    }
    private void finish(Trial trial,long work,boolean died,boolean completed){
        if(trial.owner!=self.getCloneBrain()||trial.dimension!=self.level().dimension()||(!completed&&!died))return;
        long elapsed=self.level().getGameTime()-trial.tick;if(elapsed<0||elapsed>2400)return;
        float damage=Math.max(0,trial.health-self.getHealth());
        boolean success=!died&&work>0;
        int wear=0;for(int i=0;i<trial.tools.size();i++){var tool=trial.tools.get(i);wear+=Math.max(0,trial.remaining.get(i)-(tool.isEmpty()?0:tool.getMaxDamage()-tool.getDamageValue()));}
        float reward=Math.max(-2,outcome(work,(int)elapsed,damage,died)-Math.min(0.4f,wear*0.02f));
        trial.owner.recordMethod(trial.domain,trial.state,trial.method,reward,success);
        publish(self,trial.domain,trial.state,trial.method,reward,success);
    }
    public static float outcome(long work,int ticks,float damage,boolean died){return Math.max(-2,(work>0?Math.min(1,work*0.25f+0.25f):-0.5f)-Math.min(0.6f,Math.max(0,ticks)/1200f*0.6f)-Math.min(1.5f,Math.max(0,damage)*0.15f)-(died?1.5f:0));}
    public static void publish(ClonePlayer actor,int domain,int state,int method,float reward,boolean success){
        if(success&&reward>0&&LearningEvidence.valid(domain,state,method))AgentEvents.record(actor.getUUID(),actor.level().getGameTime(),AgentEvents.Kind.WORK_SUCCESS,reward,state*4+method,domain);
    }
    public String report(){return "手順準備="+preparing+" 方式="+(workflow==null?"なし":workflow.method)+" 準備tick="+prepTicks+" 資源="+units[1]+" 食料="+units[0]+" 製作="+units[2]+" 配送="+civic.completed;}
}
