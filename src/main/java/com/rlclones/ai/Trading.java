package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.trading.MerchantOffer;

import javax.annotation.Nullable;

/**
 * R-50: a villager near while resting: trade. The offers are settled directly (no trading screen): sell what we have a lot of for
 * emeralds, buy food and iron / diamond tools and armour with them. ponytail: first fitting offer wins, no price comparison, no
 * restocking wait; add a ranking if clones should shop around.
 */
public final class Trading {
    private final ClonePlayer self;
    private final Motor motor;
    private Villager shop;
    private long cooldownUntil;
    /** Deals made (tests). */
    public int deals;

    public Trading(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    /** Called while resting and idle. True while walking to the villager. */
    public boolean tick(long now) {
        if (now < cooldownUntil) {
            return false;
        }
        if (shop == null || !shop.isAlive()) {
            shop = null;
            if ((now + self.getId()) % 100 != 0) {
                return false;
            }
            for (Villager v : self.level().getEntitiesOfClass(Villager.class, self.getBoundingBox().inflate(24), AbstractVillager::isAlive)) {
                if (!v.isBaby() && !v.getOffers().isEmpty() && (shop == null || v.distanceToSqr(self) < shop.distanceToSqr(self))) {
                    shop = v;
                }
            }
            if (shop == null) {
                return false;
            }
        }
        if (shop.distanceTo(self) > 2.5) {
            motor.navigate(shop.position(), 2.0, false);
            return true;
        }
        motor.stop();
        motor.lookAt(shop.getEyePosition());
        for (MerchantOffer o : shop.getOffers()) {
            if (!o.isOutOfStock() && affordable(o) && wanted(o)) {
                trade(o);
                return true; // one deal a tick: the next tick looks again
            }
        }
        shop = null;
        cooldownUntil = now + 2400;
        return false;
    }

    private int have(ItemStack like) {
        int n = 0;
        for (ItemStack s : self.getInventory().items) {
            if (ItemStack.isSameItemSameTags(s, like)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private boolean affordable(MerchantOffer o) {
        ItemStack a = o.getCostA();
        ItemStack b = o.getCostB();
        return have(a) >= a.getCount() && (b.isEmpty() || have(b) >= b.getCount());
    }

    /** Selling: the price is a crop / mineral we hold plenty of. Buying: food, or iron / diamond gear we do not have yet. */
    private boolean wanted(MerchantOffer o) {
        ItemStack r = o.getResult();
        ItemStack a = o.getCostA();
        if (r.is(Items.EMERALD)) {
            return o.getCostB().isEmpty() && have(a) >= 2 * a.getCount() + 8 && (a.is(Items.WHEAT) || a.is(Items.CARROT) || a.is(Items.POTATO) || a.is(Items.COAL)
                    || a.is(Items.PAPER) || a.is(Items.ROTTEN_FLESH) || a.is(Items.STRING));
        }
        if (!a.is(Items.EMERALD)) {
            return false;
        }
        if (r.isEdible()) {
            return self.getFoodData().getFoodLevel() < 20 && FoodAid.foodItems(self) < 8;
        }
        var item = r.getItem();
        boolean gear = item instanceof PickaxeItem || item instanceof SwordItem || item instanceof ArmorItem;
        boolean good = r.getDescriptionId().contains("iron") || r.getDescriptionId().contains("diamond");
        return gear && good && !hasKind(item);
    }

    private boolean hasKind(net.minecraft.world.item.Item item) {
        for (ItemStack s : self.getInventory().items) {
            if (s.is(item)) {
                return true;
            }
        }
        for (ItemStack s : self.getInventory().armor) {
            if (s.is(item)) {
                return true;
            }
        }
        return false;
    }

    private void take(ItemStack cost) {
        int left = cost.getCount();
        for (ItemStack s : self.getInventory().items) {
            if (left > 0 && ItemStack.isSameItemSameTags(s, cost)) {
                int n = Math.min(left, s.getCount());
                s.shrink(n);
                left -= n;
            }
        }
    }

    private void trade(MerchantOffer o) {
        take(o.getCostA());
        if (!o.getCostB().isEmpty()) {
            take(o.getCostB());
        }
        ItemStack out = o.getResult();
        if (!self.getInventory().add(out)) {
            self.drop(out, false);
        }
        o.increaseUses();
        shop.notifyTrade(o);
        deals++;
    }

    @Nullable
    public Villager shop() {
        return shop;
    }
}
