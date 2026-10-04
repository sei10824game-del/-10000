package com.rlclones.ai;

import com.rlclones.Config;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;

/**
 * Where a player stands on the way to full diamond gear, worked out from the bag and the armour worn alone (so it fits
 * a clone and a human alike): which tier, what is missing next, how much iron / diamond is still to be found and how
 * deep to dig for it.
 */
public final class Progression {
    /** What the player lacks most: a pickaxe, stone for the next one, iron for the gear, diamonds for the gear, nothing. */
    public enum Need {NONE, WOOD, STONE, IRON, DIAMOND}

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** Ingots (or diamonds) for helmet, chestplate, leggings, boots. */
    private static final int[] ARMOR_COST = {5, 8, 7, 4};
    private static final int PICK_COST = 3;
    private static final int SWORD_COST = 2;
    /** Where the iron is thickest (1.18+), and where the diamonds are (just above the bedrock). */
    public static final int IRON_Y = 16;
    public static final int DIAMOND_Y = -58;

    private Progression() {
    }

    private static int bestTier(Player p, Class<? extends Item> type) {
        int best = -1;
        for (ItemStack s : p.getInventory().items) {
            if (type.isInstance(s.getItem()) && s.getItem() instanceof TieredItem t) {
                best = Math.max(best, t.getTier().getLevel());
            }
        }
        return best;
    }

    public static int pickLevel(Player p) {
        return bestTier(p, PickaxeItem.class);
    }

    private static int defense(Player p, int slot) {
        EquipmentSlot s = SLOTS[slot];
        int best = p.getItemBySlot(s).getItem() instanceof ArmorItem a ? a.getDefense() : 0;
        for (ItemStack st : p.getInventory().items) {
            if (st.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == s) {
                best = Math.max(best, a.getDefense());
            }
        }
        return best;
    }

    private static ArmorItem.Type type(int slot) {
        return switch (slot) {
            case 0 -> ArmorItem.Type.HELMET;
            case 1 -> ArmorItem.Type.CHESTPLATE;
            case 2 -> ArmorItem.Type.LEGGINGS;
            default -> ArmorItem.Type.BOOTS;
        };
    }

    private static int count(Player p, Item... items) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) {
            for (Item it : items) {
                if (s.is(it)) {
                    n += s.getCount();
                }
            }
        }
        return n;
    }

    /** Ingots / diamonds still needed for the pickaxe, sword and the four armour pieces that are not made yet at that grade. */
    private static int gearNeed(Player p, Tiers tier, ArmorMaterials material) {
        int need = 0;
        if (bestTier(p, PickaxeItem.class) < tier.getLevel()) {
            need += PICK_COST;
        }
        if (bestTier(p, SwordItem.class) < tier.getLevel()) {
            need += SWORD_COST;
        }
        for (int i = 0; i < SLOTS.length; i++) {
            if (defense(p, i) < material.getDefenseForType(type(i))) {
                need += ARMOR_COST[i];
            }
        }
        return need;
    }

    public static int ironNeed(Player p) {
        return gearNeed(p, Tiers.IRON, ArmorMaterials.IRON);
    }

    public static int diamondNeed(Player p) {
        return gearNeed(p, Tiers.DIAMOND, ArmorMaterials.DIAMOND);
    }

    /** Iron still to be found: what the gear needs, less the ingots and raw iron in the bag. */
    public static int ironShort(Player p) {
        return Math.max(0, ironNeed(p) - count(p, Items.IRON_INGOT, Items.RAW_IRON, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE));
    }

    public static int diamondShort(Player p) {
        return Math.max(0, diamondNeed(p) - count(p, Items.DIAMOND));
    }

    /** 0 nothing / 1 wooden pickaxe / 2 stone pickaxe / 3 iron pickaxe / 4 iron gear / 5 diamond pickaxe / 6 all of it diamond. */
    public static int tier(Player p) {
        if (diamondNeed(p) == 0) {
            return 6;
        }
        int pick = pickLevel(p);
        if (pick >= Tiers.DIAMOND.getLevel()) {
            return 5;
        }
        if (StairMining.ironGeared(p)) {
            return 4;
        }
        if (pick >= Tiers.IRON.getLevel()) {
            return 3;
        }
        if (pick >= Tiers.STONE.getLevel()) {
            return 2;
        }
        return pick >= 0 ? 1 : 0;
    }

    public static Need need(Player p) {
        int pick = pickLevel(p);
        if (pick < 0) {
            return Need.WOOD;
        }
        if (pick < Tiers.STONE.getLevel()) {
            return Need.STONE;
        }
        if (ironShort(p) > 0) {
            return Need.IRON;
        }
        return pick >= Tiers.IRON.getLevel() && diamondShort(p) > 0 ? Need.DIAMOND : Need.NONE;
    }

    public static int digTargetY(Need need) {
        return need == Need.DIAMOND ? DIAMOND_Y : IRON_Y;
    }

    /** Digging down is what the next step needs (iron or diamonds) and the player may break blocks at all. */
    public static boolean digWanted(Player p) {
        if (!Config.get(Config.ALLOW_BLOCK_BREAKING, true)) {
            return false;
        }
        Need n = need(p);
        return n == Need.IRON || n == Need.DIAMOND;
    }

    public static String describe(Player p) {
        return "tier=" + tier(p) + " need=" + need(p) + " ironShort=" + ironShort(p) + " diamondShort=" + diamondShort(p);
    }
}
