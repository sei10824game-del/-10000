package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.world.item.Items;

/** Return early enough to use the existing route. Does not conjure supplies or abandon the route record. */
public final class MiningSafety {
    private MiningSafety(){}
    public static String returnReason(ClonePlayer self,int depth) {
        if(self.isCreative())return "";
        if(self.getHealth()<self.getMaxHealth()*.5f)return "injured";
        boolean food=Senses.hasFood(self)||FoodAid.foodItems(self)>0;
        if(self.getFoodData().getFoodLevel()<10&&!food)return "food for return";
        if(depth<12)return ""; // short bootstrap mines may gather the very first supplies
        if(self.getFoodData().getFoodLevel()<16&&!food)return "return food reserve";
        if(Equipment.pillarBlockSlot(self)<0)return "repair material reserve";
        if(self.level().getMaxLocalRawBrightness(self.blockPosition())<7
                &&self.getInventory().countItem(Items.TORCH)==0&&self.getInventory().countItem(Items.SOUL_TORCH)==0)return "lighting reserve";
        return "";
    }
}
