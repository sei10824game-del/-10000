package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A clone in creative mode does not play for itself any more: it takes whatever it needs out of the creative inventory
 * (the same menu a player uses) and spends its time helping the survival players and clones around it - food for the
 * hungry, a sword for the unarmed, a golden apple for the badly hurt, and a hand against the monsters near them.
 */
public final class CreativeHelper {
    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;
    private long lastGift = Long.MIN_VALUE / 2;

    public int takenFromMenu;
    public int gifts;
    public int hits;
    public String debug = "";

    public CreativeHelper(ClonePlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    /** Take {@code stack} out of the creative menu into a hotbar slot, exactly as the creative inventory screen does. */
    public boolean takeFromMenu(ItemStack stack) {
        Inventory inv = self.getInventory();
        int hotbar = inv.selected;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.items.get(i).isEmpty()) {
                hotbar = i;
                break;
            }
        }
        self.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(36 + hotbar, stack.copy()));
        if (ItemStack.isSameItem(inv.items.get(hotbar), stack)) {
            inv.selected = hotbar;
            takenFromMenu++;
            return true;
        }
        return false;
    }

    private static boolean hasFood(Player p) {
        for (ItemStack s : p.getInventory().items) {
            if (s.isEdible()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWeapon(Player p) {
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem) {
                return true;
            }
        }
        return false;
    }

    /** What this friend lacks most, or empty. */
    private ItemStack need(Player p) {
        if (p.getHealth() < p.getMaxHealth() * 0.4f) {
            return new ItemStack(Items.GOLDEN_APPLE, 2);
        }
        if (p.getFoodData().getFoodLevel() < 14 && !hasFood(p)) {
            return new ItemStack(Items.COOKED_BEEF, 16);
        }
        if (!hasWeapon(p)) {
            return new ItemStack(Items.IRON_SWORD);
        }
        return ItemStack.EMPTY;
    }

    /** The survival player / clone on our side that needs us most (in danger, then in need, then just the nearest). */
    @Nullable
    private Player friend(long now) {
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        for (Perception.Seen s : perception.remembered()) {
            if (now - s.lastSeen > 200 || !(s.entity instanceof Player p) || p == self || p.isCreative() || p.isSpectator() || !p.isAlive()
                    || Senses.rivals(p, self) || p.distanceTo(self) > 48) {
                continue;
            }
            double score = p.distanceTo(self);
            if (!need(p).isEmpty()) {
                score -= 20;
            }
            if (!Senses.threats(perception, p, now, 8).isEmpty()) {
                score -= 40;
            }
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    public void tick(long now) {
        if (!hasWeapon(self)) {
            takeFromMenu(new ItemStack(Items.NETHERITE_SWORD)); // our own tool for the job
        }
        Player friend = friend(now);
        if (friend == null) {
            motor.stop();
            debug = "nobody to help";
            return;
        }
        // monsters around the friend first
        List<Perception.Seen> threats = Senses.threats(perception, friend, now, 8);
        if (!threats.isEmpty()) {
            Entity foe = threats.get(0).entity;
            debug = "defending " + friend.getGameProfile().getName() + " from " + Perception.typeId(foe);
            Equipment.manage(self, true);
            motor.lookAt(foe);
            if (motor.canHit(foe) && self.getAttackStrengthScale(0.5f) >= 0.95f) {
                motor.attack(foe);
                hits++;
            } else {
                motor.navigate(foe.position(), 1.5, true);
            }
            return;
        }
        ItemStack want = need(friend);
        double d = friend.distanceTo(self);
        if (!want.isEmpty() && now - lastGift > 60) {
            debug = "bringing " + want.getHoverName().getString() + " to " + friend.getGameProfile().getName();
            if (d > 2.5) {
                motor.navigate(friend.position(), 2.0, true);
                return;
            }
            motor.stop();
            if (takeFromMenu(want)) {
                Vec3 to = friend.getEyePosition().subtract(self.getEyePosition());
                float yaw = (float) Math.toDegrees(Mth.atan2(to.z, to.x)) - 90.0F;
                self.setYRot(yaw);
                self.setYHeadRot(yaw);
                self.setXRot(15f);
                Inventory inv = self.getInventory();
                ItemStack held = inv.getSelected().copy();
                inv.setItem(inv.selected, ItemStack.EMPTY);
                self.drop(held, false, true); // tossed over to them
                gifts++;
                lastGift = now;
            }
            return;
        }
        debug = "staying with " + friend.getGameProfile().getName();
        if (d > 5) {
            motor.navigate(friend.position(), 3.0, d > 10);
        } else {
            motor.lookAt(friend);
        }
    }
}
