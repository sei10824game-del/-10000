package com.rlclones.ai;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a thing is worth, as the clone knows it from the game's own recipes: a log is four planks' worth, a plank a little more than a quarter of a
 * log, a stick less than a plank... Raw things carry a price by how hard they are to come by (dirt next to nothing, iron dear, a diamond dearer);
 * everything with a crafting recipe is worth what the cheapest recipe costs. Used wherever any of several things would do (which block to build
 * with): the cheapest goes first. ponytail: prices of raw things are fixed here, recipes are read once per run; add a per-clone adjustment by
 * what it has run short of if prices should follow need.
 */
public final class ItemValue {
    private static final Map<Item, Double> CACHE = new HashMap<>();
    private static final Set<Item> BUSY = new HashSet<>();

    private ItemValue() {
    }

    public static void forget() {
        CACHE.clear();
    }

    public static double of(Level level, ItemStack s) {
        return of(level, s.getItem());
    }

    public static double of(Level level, Item item) {
        Double c = CACHE.get(item);
        if (c != null) {
            return c;
        }
        double v = raw(item);
        if (v < 0) {
            if (!BUSY.add(item)) {
                return 2.0; // a recipe that goes round (blocks to ingots to blocks): no price from it
            }
            double best = Double.MAX_VALUE;
            for (Recipe<?> r : level.getRecipeManager().getRecipes()) {
                if (!(r instanceof CraftingRecipe)) {
                    continue;
                }
                ItemStack out = r.getResultItem(level.registryAccess());
                if (out.isEmpty() || out.getItem() != item) {
                    continue;
                }
                double sum = 0;
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) {
                        continue;
                    }
                    double cheapest = Double.MAX_VALUE;
                    for (ItemStack opt : ing.getItems()) {
                        cheapest = Math.min(cheapest, of(level, opt.getItem()));
                    }
                    sum += cheapest == Double.MAX_VALUE ? 2.0 : cheapest;
                }
                best = Math.min(best, sum / Math.max(1, out.getCount()) + 0.2); // (the work of crafting it)
            }
            BUSY.remove(item);
            v = best == Double.MAX_VALUE ? 1.5 : best;
        }
        CACHE.put(item, v);
        return v;
    }

    /** The price of a raw thing, or -1 if it is to be worked out from its recipe. */
    private static double raw(Item item) {
        ItemStack s = new ItemStack(item);
        if (s.is(ItemTags.LOGS)) {
            return 4.0; // a log: four planks, and a tree to find
        }
        if (item == Items.DIRT || item == Items.GRASS_BLOCK || item == Items.COARSE_DIRT || item == Items.NETHERRACK) {
            return 0.3;
        }
        if (item == Items.SAND || item == Items.RED_SAND || item == Items.GRAVEL) {
            return 0.4;
        }
        if (item == Items.COBBLESTONE || item == Items.ANDESITE || item == Items.DIORITE || item == Items.GRANITE || item == Items.TUFF) {
            return 1.0;
        }
        if (item == Items.STONE || item == Items.COBBLED_DEEPSLATE || item == Items.DEEPSLATE || item == Items.BLACKSTONE) {
            return 1.2;
        }
        if (item == Items.COAL || item == Items.CHARCOAL) {
            return 2.0;
        }
        if (item == Items.IRON_INGOT || item == Items.RAW_IRON) {
            return 6.0;
        }
        if (item == Items.GOLD_INGOT || item == Items.RAW_GOLD || item == Items.COPPER_INGOT || item == Items.RAW_COPPER || item == Items.REDSTONE || item == Items.LAPIS_LAZULI) {
            return 5.0;
        }
        if (item == Items.DIAMOND || item == Items.EMERALD) {
            return 30.0;
        }
        return -1;
    }
}
