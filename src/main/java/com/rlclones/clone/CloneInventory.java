package com.rlclones.clone;

import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Exposes a clone's 36 main inventory slots as a chest so players can hand over gear (sneak + right click). */
public class CloneInventory implements Container {
    private final ClonePlayer clone;

    public CloneInventory(ClonePlayer clone) {
        this.clone = clone;
    }

    @Override
    public int getContainerSize() {
        return clone.getInventory().items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : clone.getInventory().items) {
            if (!s.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return clone.getInventory().items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ContainerHelper.removeItem(clone.getInventory().items, slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(clone.getInventory().items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        clone.getInventory().items.set(slot, stack);
    }

    @Override
    public void setChanged() {
        clone.getInventory().setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return clone.isAlive() && !clone.isRemoved() && player.distanceToSqr(clone) < 64.0;
    }

    @Override
    public void clearContent() {
        clone.getInventory().items.clear();
    }
}
