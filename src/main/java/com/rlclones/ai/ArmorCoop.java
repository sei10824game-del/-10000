package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import com.rlclones.ai.strategy.Option;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.CraftingRecipe;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.*;

/** One armor recipe at a time, real inventories and in-person deliveries; bounded ephemeral reservations. */
public final class ArmorCoop {
    private static final Map<UUID,Job> JOBS=new HashMap<>();
    private final ClonePlayer self;private final Motor motor;private long nextPlan;private Item commissioned;
    public void commission(Item item){commissioned=item;}
    public int delivered,completed;public String debug="";
    private static final class Job {
        final ClonePlayer receiver;final CraftingRecipe recipe;final Item result;final long until;final Map<Item,Integer> total;
        final Map<ClonePlayer,Map<Item,Integer>> donors=new LinkedHashMap<>();
        Job(ClonePlayer receiver,CraftingRecipe recipe,long now,Map<Item,Integer> total){this.receiver=receiver;this.recipe=recipe;this.result=recipe.getResultItem(receiver.serverLevel().registryAccess()).getItem();this.until=now+400;this.total=total;}
    }
    public ArmorCoop(ClonePlayer self,Motor motor){this.self=self;this.motor=motor;}
    public static void clear(){JOBS.clear();}
    private static boolean calm(ClonePlayer c){return c.isAlive()&&!c.isCreative()&&c.getHealth()>=c.getMaxHealth()*.5&&c.controller().option()!=Option.FIGHT&&c.controller().option()!=Option.FLEE
            &&c.containerMenu==c.inventoryMenu&&c.controller().crafting().forcedTarget==null&&!c.isInWater()&&!c.isInLava()&&!c.isUsingItem()&&Senses.threats(c.controller().perception(),c,c.level().getGameTime(),24).isEmpty();}
    private static boolean usable(ClonePlayer receiver,ItemStack s){return !s.isEmpty()&&!s.isDamaged()&&!s.isEnchanted()&&!s.hasCustomHoverName()&&Crafting.armorMaterial(receiver,s);}
    private static int count(ClonePlayer holder,ClonePlayer receiver,Item item){int n=0;for(var s:holder.getInventory().items)if(s.is(item)&&usable(receiver,s))n+=s.getCount();return n;}
    private static int protectedSlots(ClonePlayer c){int n=0;for(var s:c.getInventory().armor)if(!s.isEmpty())n++;return n;}
    private static void close(Job j){JOBS.entrySet().removeIf(e->e.getValue()==j);}
    public boolean plan(long now){
        JOBS.values().stream().filter(j->now>=j.until||!j.receiver.isAlive()).distinct().toList().forEach(ArmorCoop::close);
        if(!calm(self)||JOBS.containsKey(self.getUUID())||JOBS.size()>512)return false;
        var group=new ArrayList<ClonePlayer>();group.add(self);
        for(var p:self.level().players())if(p!=self&&p instanceof ClonePlayer c&&c.isAiEnabled()&&Senses.isAllyOf(c,self)&&c.distanceToSqr(self)<=16*16&&!JOBS.containsKey(c.getUUID())&&calm(c)
                &&(self.controller().perception().canSee(c)||c.controller().perception().canSee(self)))group.add(c);
        group.sort(Comparator.comparingDouble((ClonePlayer c)->c.distanceToSqr(self)).thenComparing(c->c.getUUID().toString()));if(group.size()>12)group.subList(12,group.size()).clear();
        if(group.size()<2)return false;
        ClonePlayer leader=group.stream().min(Comparator.comparing(c->c.getUUID().toString())).orElse(self);if(leader!=self)return false;
        var recipients=new ArrayList<>(group);recipients.sort(Comparator.comparingInt(ArmorCoop::protectedSlots).thenComparing(c->c.getUUID().toString()));
        for(ClonePlayer receiver:recipients){
            if(!Crafting.armorWorkshop(receiver)||receiver.controller().crafting().forcedTarget!=null)continue;
            var contents=new StackedContents();for(var member:group)for(var stack:member.getInventory().items)if(usable(receiver,stack))contents.accountSimpleStack(stack);
            for(var recipe:Crafting.armorRecipes(receiver)){
                if(Crafting.canCraft(receiver,recipe))continue;
                var ids=new IntArrayList();if(!contents.canCraft(recipe,ids))continue;
                Map<Item,Integer> total=new LinkedHashMap<>();for(int id:ids){var s=StackedContents.fromStackingIndex(id);if(!s.isEmpty())total.merge(s.getItem(),1,Integer::sum);}
                if(total.isEmpty())continue;Job job=new Job(receiver,recipe,now,total);boolean possible=true;
                for(var need:total.entrySet()){int left=Math.max(0,need.getValue()-count(receiver,receiver,need.getKey()));
                    for(var donor:group)if(donor!=receiver&&left>0){int give=Math.min(left,count(donor,receiver,need.getKey()));if(give>0){job.donors.computeIfAbsent(donor,k->new LinkedHashMap<>()).put(need.getKey(),give);left-=give;}}
                    if(left>0){possible=false;break;}}
                if(!possible||job.donors.isEmpty())continue;
                JOBS.put(receiver.getUUID(),job);for(var donor:job.donors.keySet())JOBS.put(donor.getUUID(),job);debug="pool "+recipe.getId()+" -> "+receiver.getGameProfile().getName();return true;
            }
        }
        return false;
    }
    /** Busy only while delivering. The receiving crafter uses the existing real crafting workflow. */
    public boolean tick(long now){
        if(commissioned!=null){boolean made=self.getInventory().countItem(commissioned)>0;for(var s:self.getInventory().armor)made|=s.is(commissioned);if(made){if(self.controller().crafting().forcedTarget==commissioned)self.controller().crafting().forcedTarget=null;commissioned=null;}else if(self.controller().crafting().forcedTarget!=commissioned)commissioned=null;}
        Job job=JOBS.get(self.getUUID());if(job==null){if(now>=nextPlan){nextPlan=now+100;plan(now);job=JOBS.get(self.getUUID());}if(job==null)return false;}
        if(now>=job.until||!job.receiver.isAlive()||!Senses.isAllyOf(self,job.receiver)||self.level()!=job.receiver.level()||!calm(self)||!calm(job.receiver)) {close(job);return false;}
        if(Crafting.canCraft(job.receiver,job.recipe)){job.receiver.controller().crafting().forcedTarget=job.result;job.receiver.controller().queueArmorCraft(job.result);completed++;close(job);return false;}
        if(self==job.receiver){debug="await armor materials";return false;}
        var needs=job.donors.get(self);if(needs==null||needs.isEmpty())return false;
        boolean clear=self.level().clip(new net.minecraft.world.level.ClipContext(self.getEyePosition(),job.receiver.getEyePosition(),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,self)).getType()==net.minecraft.world.phys.HitResult.Type.MISS;
        if(self.distanceToSqr(job.receiver)>3*3||!clear){if(motor.stuckCount()>3){close(job);return false;}motor.navigate(job.receiver.position(),clear?2:.5,false);return true;}
        for(var need:needs.entrySet()){
            int wanted=Math.min(need.getValue(),Math.max(0,job.total.get(need.getKey())-count(job.receiver,job.receiver,need.getKey())));
            for(var stack:self.getInventory().items)if(wanted>0&&stack.is(need.getKey())&&usable(job.receiver,stack)){
                int n=Math.min(wanted,stack.getCount());ItemStack give=stack.split(n);job.receiver.getInventory().add(give);int moved=n-give.getCount();if(!give.isEmpty())stack.grow(give.getCount());wanted-=moved;need.setValue(need.getValue()-moved);delivered+=moved;
            }
        }
        self.getInventory().setChanged();job.receiver.getInventory().setChanged();needs.values().removeIf(n->n<=0);
        if(Crafting.canCraft(job.receiver,job.recipe)){job.receiver.controller().queueArmorCraft(job.result);completed++;close(job);}return false;
    }
    public boolean reserved(){return JOBS.containsKey(self.getUUID());}
    public String report(){return "防具の共同製作: 配送="+delivered+" 材料完了="+completed+" "+debug;}
}
