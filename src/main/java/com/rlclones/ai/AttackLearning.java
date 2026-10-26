package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.brain.EnemyKnowledge;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Learns from what attacks really do. (1) Which way of using an item hurts an enemy - swing it, right click it,
 * hold right click a little or a long time and let go - is tried out and valued by the damage that follows (works
 * for modded weapons the same as for vanilla ones). (2) A hit that lands but takes no health ("the snowball does
 * nothing to a zombie", "arrows never touch an enderman") teaches that this attack is useless against that kind
 * of enemy, so the combat policy stops choosing it.
 */
public final class AttackLearning {
    public static final int SWING = 0;
    public static final int TAP = 1;
    public static final int HOLD_SHORT = 2;
    public static final int HOLD_LONG = 3;
    public static final String[] MODE_NAMES = {"swing", "tap", "hold_short", "hold_long"};
    public static final int[] HOLD_TICKS = {0, 1, 12, 25};

    private final Supplier<Brain> brain;
    /** Damage this clone dealt to each entity (by id), all causes together. */
    private final Int2DoubleOpenHashMap dealt = new Int2DoubleOpenHashMap();

    private record Attempt(LivingEntity target, String type, String method, float health, double dealtBefore, long at) {
    }

    private record Trial(String item, int mode, LivingEntity target, double dealtBefore, long evalAt) {
    }

    private final List<Attempt> attempts = new ArrayList<>();
    private final List<Trial> trials = new ArrayList<>();

    /** Diagnostics / tests. */
    public int uselessLearned;
    public int trialsJudged;
    public String debug = "";

    public AttackLearning(Supplier<Brain> brain) {
        this.brain = brain;
    }

    public void onDealt(LivingEntity victim, float amount) {
        if (dealt.size() > 4000) {
            dealt.clear();
        }
        dealt.addTo(victim.getId(), amount);
    }

    public double dealtTo(Entity e) {
        return dealt.get(e.getId());
    }

    /** False when a hit that does nothing says nothing about the kind: a protected individual, a raised shield... */
    private static boolean fair(LivingEntity t) {
        return t.isAlive() && t.invulnerableTime <= 10 && !t.hasEffect(MobEffects.DAMAGE_RESISTANCE) && !t.isBlocking() && !t.isInvulnerable()
                && !(t instanceof Player p && (p.isCreative() || p.isSpectator()));
    }

    /** A hit that should hurt (a swing that connected, a projectile that struck): judged a few ticks later. */
    public void attempt(LivingEntity target, String method, long now) {
        if (fair(target) && attempts.size() < 64) {
            attempts.add(new Attempt(target, Perception.typeId(target), method, target.getHealth() + target.getAbsorptionAmount(), dealtTo(target), now));
        }
    }

    /** One try of using {@code item} in {@code mode} on {@code target}; valued by the damage dealt until {@code evalAt}. */
    public void trial(String item, int mode, LivingEntity target, double dealtBefore, long evalAt) {
        if (trials.size() < 32) {
            trials.add(new Trial(item, mode, target, dealtBefore, evalAt));
        }
    }

    public void tick(long now) {
        Brain b = brain.get();
        for (Iterator<Attempt> it = attempts.iterator(); it.hasNext(); ) {
            Attempt a = it.next();
            if (now - a.at < 3) {
                continue;
            }
            it.remove();
            boolean hurt = dealtTo(a.target) - a.dealtBefore > 0.01 || !a.target.isAlive()
                    || a.target.getHealth() + a.target.getAbsorptionAmount() < a.health - 0.01f;
            EnemyKnowledge k = b.knowledge(a.type);
            boolean before = useless(k, a.method);
            k.traits.mergeInt((hurt ? "effect:" : "noeffect:") + a.method, 1, Integer::sum);
            if (!before && useless(k, a.method)) {
                uselessLearned++;
                debug = a.method + " does nothing to " + a.type;
            }
        }
        for (Iterator<Trial> it = trials.iterator(); it.hasNext(); ) {
            Trial t = it.next();
            if (now < t.evalAt) {
                continue;
            }
            it.remove();
            float reward = (float) Math.min(40.0, Math.max(0.0, dealtTo(t.target) - t.dealtBefore));
            b.learnItemUse(t.item, t.mode, reward);
            trialsJudged++;
            debug = t.item + " " + MODE_NAMES[t.mode] + " -> " + String.format(java.util.Locale.ROOT, "%.1f", reward);
        }
    }

    /** Hits with this method have landed on this kind of enemy again and again without taking any health. */
    public static boolean useless(EnemyKnowledge k, String method) {
        if (k == null) {
            return false;
        }
        int no = k.traits.getInt("noeffect:" + method);
        return no >= 3 && no > 2 * k.traits.getInt("effect:" + method);
    }

    public static String itemId(ItemStack s) {
        var id = ForgeRegistries.ITEMS.getKey(s.getItem());
        return id == null ? "?" : id.toString();
    }

    /** The attack method a projectile stands for: "arrow", "trident", "thrown:minecraft:snowball"... */
    public static String projectileMethod(Entity projectile) {
        if (projectile instanceof ThrownTrident) {
            return "trident";
        }
        if (projectile instanceof AbstractArrow) {
            return "arrow";
        }
        if (projectile instanceof ThrowableItemProjectile t) {
            return "thrown:" + itemId(t.getItem());
        }
        return "projectile:" + Perception.typeId(projectile);
    }

    /** The method an item fires / throws when used from range. */
    public static String rangedMethod(ItemStack s) {
        return switch (Equipment.rangedKind(s)) {
            case BOW, CROSSBOW -> "arrow";
            case THROWN -> s.getItem() instanceof net.minecraft.world.item.TridentItem ? "trident" : "thrown:" + itemId(s);
            default -> "thrown:" + itemId(s);
        };
    }
}
