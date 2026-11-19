package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import java.util.*;

/** One low-risk, natural-use observation at a time. Never creates/consumes items for a synthetic reward. */
public final class SafeItemTrials {
    private final ClonePlayer self;private final Perception perception;
    private String key;private Brain owner;private long began,finished=-1,next;
    private float health;private int food;private Set<String> effects=Set.of();
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    private EquipmentSlot wornSlot;private long uses,beforeUses;
    public SafeItemTrials(ClonePlayer self,Perception perception){this.self=self;this.perception=perception;}
    public static String key(String kind,ItemStack stack){return kind+"|"+net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());}
    private long now(){return self.level().getGameTime();}
    public boolean safe(){return self.isAlive()&&!self.isCreative()&&self.getHealth()>=self.getMaxHealth()*0.85f&&self.getFoodData().getFoodLevel()>6
            &&Senses.threats(perception,self,now(),24).isEmpty()&&(self.getLastHurtByMob()==null||self.tickCount-self.getLastHurtByMobTimestamp()>100)&&!self.isOnFire()&&!self.isInLava();}
    private Set<String> harmfulEffects(){Set<String> set=new HashSet<>();for(var effect:self.getActiveEffects())if(!effect.getEffect().isBeneficial())set.add(String.valueOf(net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect())));return set;}
    private void begin(String key,EquipmentSlot slot){this.key=key;wornSlot=slot;owner=self.getCloneBrain();dimension=self.level().dimension();began=now();finished=-1;health=self.getHealth();food=self.getFoodData().getFoodLevel();effects=harmfulEffects();beforeUses=uses;next=now()+600;}
    public int foodSlot(int fallback){
        if(key!=null&&key.startsWith("food|")&&self.getFoodData().getFoodLevel()>6){
            if(fallback<0||!key.equals(key("food",self.getInventory().items.get(fallback))))return fallback;
            for(int i=0;i<self.getInventory().items.size();i++){var other=self.getInventory().items.get(i);if(other.isEdible()&&!key.equals(key("food",other))&&Equipment.foodScore(self,other)>0&&!Equipment.keepForFurnace(self,other))return i;}
            return -1;
        }
        if(key!=null||now()<next||!safe()||self.getFoodData().getFoodLevel()>14||FoodAid.foodItems(self)<4)return fallback;
        for(int i=0;i<self.getInventory().items.size();i++){var stack=self.getInventory().items.get(i);var properties=stack.getFoodProperties(self);
            if(!stack.isEdible()||properties==null||properties.getNutrition()<2||stack.getRarity()!=Rarity.COMMON||stack.getItem() instanceof SuspiciousStewItem
                    ||Equipment.keepForFurnace(self,stack)||properties.getEffects().stream().anyMatch(e->!e.getFirst().getEffect().isBeneficial()))continue;
            String id=key("food",stack);if(self.getCloneBrain().itemTrials.known(id)||!self.getCloneBrain().itemTrials.room(id))continue;
            begin(id,null);return i;
        }return fallback;
    }
    public boolean beforeOffhandFood(){
        var stack=self.getOffhandItem();String id=key("food",stack);
        if(self.getFoodData().getFoodLevel()>6&&(self.getCloneBrain().itemTrials.harmful(id)||key!=null&&key.equals(id)))return false;
        var properties=stack.getFoodProperties(self);
        if(key==null&&now()>=next&&safe()&&self.getFoodData().getFoodLevel()<=14&&FoodAid.foodItems(self)>=4
                &&properties!=null&&properties.getNutrition()>=2&&stack.getRarity()==Rarity.COMMON&&!(stack.getItem() instanceof SuspiciousStewItem)
                &&!Equipment.keepForFurnace(self,stack)&&properties.getEffects().stream().noneMatch(e->!e.getFirst().getEffect().isBeneficial())
                &&!self.getCloneBrain().itemTrials.known(id)&&self.getCloneBrain().itemTrials.room(id))begin(id,null);
        return true;
    }
    public void consumed(ItemStack stack){if(key!=null&&key.equals(key("food",stack))&&finished<0)finished=now();}
    public void used(){uses++;}
    public void tick(){
        if(key!=null){
            if(owner!=self.getCloneBrain()||dimension!=self.level().dimension()||!self.isAlive()||!Senses.threats(perception,self,now(),24).isEmpty()){cancel();return;}
            if(wornSlot!=null&&!key.equals(key("gear",self.getItemBySlot(wornSlot)))){cancel();return;}
            if(wornSlot==null&&key.startsWith("gear|")&&!key.equals(key("gear",self.getMainHandItem()))){cancel();return;}
            if(finished<0&&key.startsWith("food|")&&now()-began>200){cancel();return;}
            long start=key.startsWith("food|")?finished:began;
            if(start>=0&&now()-start>=100){var badEffects=harmfulEffects();badEffects.removeAll(effects);boolean bad=!badEffects.isEmpty()||self.getHealth()<health;
                boolean actualUse=key.startsWith("food|")||wornSlot!=null||uses>beforeUses;
                if(actualUse)owner.itemTrials.record(key,bad?-1:key.startsWith("food|")?Math.min(1,0.2f+Math.max(0,self.getFoodData().getFoodLevel()-food)*0.1f+Math.max(0,self.getHealth()-health)*0.1f):1,bad);cancel();}return;
        }
        if(now()<next||!safe()||now()%20!=0)return;
        for(var slot:new EquipmentSlot[]{EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET,EquipmentSlot.MAINHAND}){
            var stack=self.getItemBySlot(slot);if(!(stack.getItem() instanceof ArmorItem||stack.getItem() instanceof TieredItem)||stack.getRarity()!=Rarity.COMMON)continue;
            String id=key("gear",stack);if(self.getCloneBrain().itemTrials.known(id)||!self.getCloneBrain().itemTrials.room(id))continue;
            begin(id,slot==EquipmentSlot.MAINHAND?null:slot);return;
        }
    }
    public void stop(){cancel();}
    private void cancel(){key=null;owner=null;effects=Set.of();}
    public String report(){return self.getCloneBrain().itemTrials.report()+" 観測中="+(key==null?"なし":key);}
    public static boolean canTryWeapon(ClonePlayer self,ItemStack stack,net.minecraft.world.entity.LivingEntity target,Perception perception){
        if(stack.getRarity()!=Rarity.COMMON||self.getHealth()<self.getMaxHealth()*0.85f||self.getFoodData().getFoodLevel()<10
                ||target instanceof net.minecraft.world.entity.player.Player||target.getMaxHealth()>40||!com.rlclones.Config.get(com.rlclones.Config.ALLOW_BLOCK_BREAKING,true)
                ||Senses.threats(perception,self,self.level().getGameTime(),24).size()>1)return false;
        String id=String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()));
        for(String dangerous:new String[]{"bomb","grenade","launcher","cannon","tnt"})if(id.contains(dangerous))return false;
        for(var seen:perception.visible())if(seen.entity!=self&&seen.entity!=target&&seen.entity.distanceToSqr(self)<64&&!Senses.isHostileTo(seen.entity,self))return false;
        return true;
    }
}
