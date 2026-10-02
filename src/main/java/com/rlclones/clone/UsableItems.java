package com.rlclones.clone;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DebugStickItem;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.KnowledgeBookItem;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Items a clone can actually do something with - weapons, tools, armour, ender pearls, potions, buckets, mod
 * items with a use of their own - as opposed to blocks and crafting materials. Used by {@code /rlclone giveitems}.
 */
public final class UsableItems {
    private static final Map<Class<?>, Boolean> OVERRIDES = new ConcurrentHashMap<>();

    private UsableItems() {
    }

    public static boolean usable(Item item) {
        if (item == Items.AIR || item instanceof BlockItem || item instanceof SpawnEggItem || item instanceof DebugStickItem
                || item instanceof KnowledgeBookItem || item instanceof DyeItem || item instanceof HangingEntityItem
                || item instanceof ArmorStandItem || item instanceof RecordItem || item instanceof WritableBookItem
                || item instanceof WrittenBookItem || item instanceof MapItem || item instanceof com.rlclones.item.CloneSpawnEggItem
                || item == Items.COMMAND_BLOCK_MINECART || item == Items.BUNDLE) {
            return false;
        }
        if (item == Items.TOTEM_OF_UNDYING || item.canBeDepleted() || item instanceof Equipable) {
            return true; // weapons, tools, armour, shields, elytra, rods...
        }
        FoodProperties food = item.getFoodProperties();
        if (food != null) {
            return !food.getEffects().isEmpty() || item.getClass() != Item.class; // golden apples, chorus fruit, honey - not plain food
        }
        return overridesSomething(item.getClass());
    }

    /** Has the item a behaviour of its own (right click, use on a block / a mob, a held use)? Mapping independent. */
    private static boolean overridesSomething(Class<?> c) {
        return OVERRIDES.computeIfAbsent(c, k -> {
            for (Class<?> x = k; x != null && x != Item.class; x = x.getSuperclass()) {
                for (Method m : x.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    Class<?> r = m.getReturnType();
                    if (r == InteractionResultHolder.class && p.length == 3 && p[0] == Level.class && p[1] == Player.class && p[2] == InteractionHand.class) {
                        return true; // use
                    }
                    if (r == InteractionResult.class && p.length == 1 && p[0] == UseOnContext.class) {
                        return true; // useOn
                    }
                    if (r == InteractionResult.class && p.length == 4 && p[0] == ItemStack.class && p[1] == Player.class
                            && p[2] == LivingEntity.class && p[3] == InteractionHand.class) {
                        return true; // interactLivingEntity
                    }
                }
            }
            return false;
        });
    }

    /** Every usable item in the registry (vanilla and mods), in registry order. */
    public static List<Item> all() {
        List<Item> out = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (usable(item)) {
                out.add(item);
            }
        }
        return out;
    }

    public static String id(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id == null ? "?" : id.toString();
    }

    /** A ready-to-use stack of {@code item}: a real potion in a potion bottle, a handful of throwables. */
    public static ItemStack stackOf(Item item, RandomSource rnd) {
        ItemStack s = new ItemStack(item);
        if (item instanceof PotionItem) {
            List<Potion> potions = new ArrayList<>();
            for (Potion p : BuiltInRegistries.POTION) {
                if (p != Potions.EMPTY && p != Potions.WATER && p != Potions.AWKWARD && p != Potions.MUNDANE && p != Potions.THICK) {
                    potions.add(p);
                }
            }
            if (!potions.isEmpty()) {
                PotionUtils.setPotion(s, potions.get(rnd.nextInt(potions.size())));
            }
        }
        if (s.getMaxStackSize() > 1) {
            s.setCount(Math.min(s.getMaxStackSize(), 16));
        }
        return s;
    }

    /**
     * One random usable item for every clone, no item handed to two clones - and none a clone already carries.
     * Returns the stacks given, in the order of {@code clones}.
     */
    public static List<ItemStack> giveEach(Collection<? extends Player> clones, RandomSource rnd) {
        List<Item> pool = all();
        Set<Item> held = new HashSet<>();
        for (Player p : clones) {
            for (ItemStack s : p.getInventory().items) {
                held.add(s.getItem());
            }
            for (ItemStack s : p.getInventory().armor) {
                held.add(s.getItem());
            }
            held.add(p.getOffhandItem().getItem());
        }
        List<Item> fresh = new ArrayList<>(pool);
        fresh.removeAll(held);
        if (fresh.size() < clones.size()) {
            fresh = new ArrayList<>(pool);
        }
        List<ItemStack> given = new ArrayList<>();
        for (Player p : clones) {
            if (fresh.isEmpty()) {
                fresh = new ArrayList<>(pool);
                for (ItemStack g : given) {
                    fresh.remove(g.getItem());
                }
                if (fresh.isEmpty()) {
                    break;
                }
            }
            Item item = fresh.remove(rnd.nextInt(fresh.size()));
            ItemStack s = stackOf(item, rnd);
            given.add(s.copy());
            if (!p.getInventory().add(s) && !s.isEmpty()) {
                p.drop(s, false);
            }
        }
        return given;
    }
}
