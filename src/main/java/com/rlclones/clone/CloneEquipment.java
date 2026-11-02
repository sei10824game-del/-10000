package com.rlclones.clone;

import com.rlclones.RLClones;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Clone equipment: a clone armor stand (creative only) holds armour and items like any armor stand; hit, the stand is gone and a
 * hidden clone wears and holds them, floating about. It has no hunger and no health of its own: damage is spread over every
 * armour piece and tool it has, and when the last one breaks it is gone. Better armour it gets replaces what it wears (the clone's
 * usual equipping); a broken piece is covered by another from its bag. Everything it has gets Mending. It does what a clone does
 * (mining, building, crafting...).
 * ponytail: only a player's blow breaks the stand (the armor stand's own hurt() skips Forge's attack event); explosions and arrows leave it standing.
 */
@Mod.EventBusSubscriber(modid = RLClones.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CloneEquipment {
    /** Persistent-data key on a placed clone armor stand. */
    public static final String STAND = "rlclones_equipment_stand";

    private CloneEquipment() {
    }

    public static boolean is(Player p) {
        return p instanceof ClonePlayer && CloneManager.peek() != null && CloneManager.peek().isEquipment(p.getUUID());
    }

    /** The stand turns into the equipment: its armour and held items go to a new hidden clone; the stand is removed. */
    public static ClonePlayer convert(ArmorStand stand, ServerLevel level) {
        CloneManager m = CloneManager.get(level.getServer());
        ClonePlayer soul = m.summon(null, level, stand.position(), stand.getYRot());
        if (soul == null) {
            return null;
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            soul.setItemSlot(slot, stand.getItemBySlot(slot).copy());
        }
        soul.setItemSlot(EquipmentSlot.MAINHAND, stand.getItemBySlot(EquipmentSlot.MAINHAND).copy());
        soul.setItemSlot(EquipmentSlot.OFFHAND, stand.getItemBySlot(EquipmentSlot.OFFHAND).copy());
        m.markEquipment(soul.getUUID());
        soul.setInvisible(true);
        mend(soul);
        stand.discard();
        return soul;
    }

    @SubscribeEvent
    public static void onStandHit(AttackEntityEvent event) {
        if (event.getTarget() instanceof ArmorStand stand && stand.getPersistentData().getBoolean(STAND) && stand.level() instanceof ServerLevel level) {
            event.setCanceled(true);
            convert(stand, level);
        }
    }

    private static List<ItemStack> parts(ClonePlayer p) {
        List<ItemStack> out = new ArrayList<>();
        for (List<ItemStack> list : List.of(p.getInventory().armor, p.getInventory().items, p.getInventory().offhand)) {
            for (ItemStack s : list) {
                if (!s.isEmpty() && s.isDamageableItem()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Every part gets Mending (what it wears, holds or later picks up): xp it picks up mends it. */
    public static void mend(ClonePlayer p) {
        for (ItemStack s : parts(p)) {
            if (net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(net.minecraft.world.item.enchantment.Enchantments.MENDING, s) == 0) {
                s.enchant(net.minecraft.world.item.enchantment.Enchantments.MENDING, 1);
            }
        }
    }

    /** Damage is spread over all its armour and tools; gone when none is left. */
    @SubscribeEvent
    public static void onEquipmentHurt(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ClonePlayer p) || !is(p) || event.isCanceled() || event.getSource().is(DamageTypes.GENERIC_KILL)
                || event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return;
        }
        event.setCanceled(true);
        wear(p, event.getAmount());
    }

    public static void wear(ClonePlayer p, float amount) {
        List<ItemStack> parts = parts(p);
        if (parts.isEmpty()) {
            return;
        }
        int each = Math.max(1, Math.round(amount / parts.size()));
        for (ItemStack s : parts) {
            s.hurtAndBreak(each, p, x -> {
            });
        }
        if (parts(p).isEmpty()) {
            CloneManager.get(p.getServer()).remove(p, true, Component.literal("the equipment is used up"));
        }
    }

    /** No hunger, no hurt by itself, out of sight: kept up every second. */
    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 0 || CloneManager.peek() == null) {
            return;
        }
        for (ClonePlayer c : new ArrayList<>(CloneManager.peek().clones())) {
            if (is(c)) {
                c.getFoodData().setFoodLevel(20);
                c.setHealth(c.getMaxHealth());
                mend(c);
                if (!c.isInvisible()) {
                    c.setInvisible(true);
                }
                if (parts(c).isEmpty()) {
                    CloneManager.peek().remove(c, true, Component.literal("the equipment is used up"));
                }
            }
        }
    }

    /** /rlclone equipfaction peace: mobs leave the clone equipment alone. */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (CloneManager.equipPeace() && event.getNewTarget() instanceof Player p && is(p)) {
            event.setCanceled(true);
        }
    }
}
