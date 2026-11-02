package com.rlclones.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.ItemStack;

/** A clone armor stand (creative only): placed like an armor stand; hit, it turns into a clone equipment (plan: CloneEquipment). */
public class CloneArmorStandItem extends ArmorStandItem {
    public CloneArmorStandItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        CompoundTag mark = new CompoundTag();
        mark.putBoolean(com.rlclones.clone.CloneEquipment.STAND, true);
        CompoundTag entity = new CompoundTag();
        entity.put("ForgeData", mark); // (the placed stand keeps it in its persistent data)
        entity.putBoolean("ShowArms", true); // (a stand without arms cannot hold anything in its hands)
        stack.getOrCreateTag().put("EntityTag", entity);
        return stack;
    }
}
