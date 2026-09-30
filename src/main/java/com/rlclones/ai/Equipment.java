package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Inventory helpers. The static queries work on any player so clones can reason about others' gear too. */
public final class Equipment {
    private Equipment() {
    }

    // ------------------------------------------------------------------ queries (any player)

    public static double attackDamage(ItemStack stack) {
        double dmg = 1.0;
        double speed = 4.0;
        for (AttributeModifier m : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                dmg += m.getAmount();
            }
        }
        for (AttributeModifier m : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_SPEED)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                speed += m.getAmount();
            }
        }
        return dmg * Math.sqrt(Math.max(0.25, speed) / 4.0) + (stack.isEnchanted() ? 0.5 : 0.0);
    }

    public static int bestWeaponSlot(Player p) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = 1.0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || s.getItem() instanceof BowItem || s.isEdible()) {
                continue;
            }
            double score = attackDamage(s);
            if (score > bestScore + 0.01) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    public static boolean isArmed(Player p) {
        return attackDamage(p.getMainHandItem()) >= 4.0;
    }

    public static boolean hasShield(Player p) {
        if (p.getOffhandItem().getItem() instanceof ShieldItem) {
            return !p.getCooldowns().isOnCooldown(p.getOffhandItem().getItem());
        }
        return false;
    }

    public static int bowSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.getItem() instanceof BowItem && (!p.getProjectile(s).isEmpty() || p.getAbilities().instabuild)) {
                return i;
            }
        }
        return -1;
    }

    public static double foodScore(Player p, ItemStack s) {
        if (!s.isEdible()) {
            return -1;
        }
        FoodProperties food = s.getFoodProperties(p);
        if (food == null) {
            return -1;
        }
        double score = food.getNutrition() + food.getSaturationModifier() * food.getNutrition();
        if (!food.getEffects().isEmpty() && !food.canAlwaysEat()) {
            score -= 6;
        }
        return score;
    }

    public static int bestFoodSlot(Player p) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = p.getFoodData().getFoodLevel() <= 6 ? -100 : 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            double score = foodScore(p, s);
            if (score > bestScore && (p.canEat(false) || foodCanAlwaysEat(p, s))) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static boolean foodCanAlwaysEat(Player p, ItemStack s) {
        FoodProperties food = s.getFoodProperties(p);
        return food != null && food.canAlwaysEat();
    }

    public static boolean isPillarBlock(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) {
            return false;
        }
        Block block = bi.getBlock();
        if (block instanceof EntityBlock || block instanceof FallingBlock) {
            return false;
        }
        BlockState state = block.defaultBlockState();
        return state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && !state.is(net.minecraftforge.common.Tags.Blocks.ORES)
                && !state.is(net.minecraftforge.common.Tags.Blocks.STORAGE_BLOCKS);
    }

    public static int pillarBlockSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (isPillarBlock(inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int bestToolSlot(Player p, BlockState state) {
        Inventory inv = p.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;
        boolean needsTool = state.requiresCorrectToolForDrops();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty()) {
                continue;
            }
            if (needsTool && !s.isCorrectToolForDrops(state)) {
                continue;
            }
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed || (needsTool && best < 0)) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    public static boolean canHarvest(Player p, BlockState state) {
        return !state.requiresCorrectToolForDrops() || bestToolSlot(p, state) >= 0;
    }

    // ------------------------------------------------------------------ actions (the clone itself)

    /** Makes the given inventory slot the selected hotbar slot, swapping it into the hotbar if needed. */
    public static void select(Player p, int slot) {
        if (slot < 0) {
            return;
        }
        Inventory inv = p.getInventory();
        if (slot < Inventory.getSelectionSize()) {
            inv.selected = slot;
            return;
        }
        int target = inv.selected;
        ItemStack held = inv.items.get(target);
        inv.items.set(target, inv.items.get(slot));
        inv.items.set(slot, held);
    }

    /** Wear the best armour, put a shield in the off hand and hold the best weapon. */
    public static void manage(Player p, boolean holdWeapon) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.getItem() instanceof ArmorItem armor) {
                EquipmentSlot slot = armor.getEquipmentSlot();
                ItemStack worn = p.getItemBySlot(slot);
                if (worn.isEmpty() || armorScore(s) > armorScore(worn)) {
                    p.setItemSlot(slot, s.copy());
                    inv.items.set(i, worn.copy());
                }
            }
        }
        if (p.getOffhandItem().isEmpty()) {
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack s = inv.items.get(i);
                if (s.getItem() instanceof ShieldItem) {
                    p.setItemSlot(EquipmentSlot.OFFHAND, s.copy());
                    inv.items.set(i, ItemStack.EMPTY);
                    break;
                }
            }
        }
        if (holdWeapon) {
            int weapon = bestWeaponSlot(p);
            if (weapon >= 0 && attackDamage(inv.items.get(weapon)) > attackDamage(p.getMainHandItem()) + 0.01) {
                select(p, weapon);
            }
        }
    }

    private static double armorScore(ItemStack s) {
        if (s.getItem() instanceof ArmorItem a) {
            return a.getDefense() + a.getToughness() * 0.5 + (s.isEnchanted() ? 0.25 : 0);
        }
        return 0;
    }
}
