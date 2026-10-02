package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * N key breeding: with breeding switched on, a clone with a full food gauge and food to spare looks for another
 * such clone, the two meet, and each gives most of its food gauge to make a new clone (CloneManager.breed) that
 * inherits everything both parents knew.
 */
public final class Breeding {
    public enum Status {WORKING, DONE, FAILED}

    /** Food items to spare (besides a full food gauge) before a clone (or player) can be a parent. */
    public static final int FOOD_NEEDED = 4;

    private final ClonePlayer self;
    private final Motor motor;
    @Nullable
    private Player partner;
    private int ticks;
    public int children;
    public String debug = "";

    public Breeding(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    /** Full stomach and food in the bag. */
    public static boolean eligible(Player p) {
        return p.isAlive() && !p.isSpectator() && !(p instanceof ClonePlayer c && c.isCreative())
                && p.getFoodData().getFoodLevel() >= 20 && FoodAid.foodItems(p) >= FOOD_NEEDED;
    }

    /** Another clone of ours that could be the other parent, nearest first. */
    @Nullable
    private Player findPartner() {
        CloneManager m = CloneManager.peek();
        if (m == null) {
            return null;
        }
        Player best = null;
        for (ClonePlayer c : m.clones()) {
            if (c != self && c.level() == self.level() && c.distanceTo(self) < 48 && eligible(c) && Senses.isAllyOf(c, self)
                    && (best == null || c.distanceTo(self) < best.distanceTo(self))) {
                best = c;
            }
        }
        return best;
    }

    public boolean canStart() {
        CloneManager m = CloneManager.peek();
        return m != null && m.isBreeding() && eligible(self) && m.clones().size() < Config.cloneLimit() && findPartner() != null;
    }

    public void begin() {
        partner = findPartner();
        ticks = 0;
        motor.resetStuck();
    }

    public Status tick() {
        Player p = partner;
        CloneManager m = CloneManager.peek();
        if (p == null || m == null || !m.isBreeding() || !eligible(self) || !eligible(p) || ++ticks > 1200) {
            return Status.FAILED;
        }
        if (p.distanceTo(self) > 2.5) {
            motor.navigate(p.position(), 1.5, p.distanceTo(self) > 12);
            return motor.stuckCount() > 8 ? Status.FAILED : Status.WORKING;
        }
        motor.stop();
        motor.lookAt(p);
        ClonePlayer child = m.breed(self, p);
        if (child == null) {
            return Status.FAILED;
        }
        children++;
        debug = "child " + child.getGameProfile().getName() + " with " + p.getGameProfile().getName();
        return Status.DONE;
    }
}
