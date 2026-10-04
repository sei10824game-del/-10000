package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the effects on us do, learned from experience - buffs and debuffs alike, modded ones included. While an effect
 * is on, the clone notes how its health and hunger fare (against how they fare with nothing on) and how its body
 * feels (faster, stronger, tougher, luckier... read off its own attributes). The value is kept per effect in the
 * brain: milk cures what proved bad. Lingering clouds on the ground are seen like any other thing (by their colour)
 * and valued by what happened while standing in them: bad ones are left and kept away from, good ones walked into.
 */
public final class EffectSense {
    /** Attributes where more is better (what a player would call a buff). */
    private static final List<Attribute> GOOD = List.of(Attributes.MOVEMENT_SPEED, Attributes.ATTACK_DAMAGE, Attributes.ATTACK_SPEED,
            Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.MAX_HEALTH, Attributes.LUCK, Attributes.KNOCKBACK_RESISTANCE);
    private static final int PERIOD = 10;

    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;
    private float lastHealth = -1;
    private int lastFood;
    private float baseline;
    private final Set<String> lastEffects = new HashSet<>();
    @Nullable
    private AreaEffectCloud fleeing;
    @Nullable
    private AreaEffectCloud seeking;

    public int samples;
    public int cloudsLeft;
    public int cloudsSought;
    public String debug = "";

    public EffectSense(ClonePlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    public static String effectId(MobEffect e) {
        ResourceLocation k = ForgeRegistries.MOB_EFFECTS.getKey(e);
        return k == null ? "unknown" : k.toString();
    }

    /** What a clone can tell a lingering cloud by: its colour. */
    public static String cloudKey(AreaEffectCloud c) {
        return "cloud:" + Integer.toHexString(c.getColor() & 0xFFFFFF);
    }

    /** Some effect on {@code p} has proved bad (learned, not from a fixed list). */
    public static boolean learnedBadOn(Player p) {
        if (!(p instanceof ClonePlayer c) || c.getCloneBrain() == null) {
            return false;
        }
        Brain b = c.getCloneBrain();
        for (MobEffectInstance e : p.getActiveEffects()) {
            String id = effectId(e.getEffect());
            if (b.knowsEffect(id) && b.effectValue(id) < -0.3f) {
                return true;
            }
        }
        return false;
    }

    /** How the effect changes the body (its attribute modifiers, as felt): + better, - worse. */
    private float bodyChange(MobEffectInstance inst) {
        float sum = 0;
        for (Map.Entry<Attribute, AttributeModifier> m : inst.getEffect().getAttributeModifiers().entrySet()) {
            if (!GOOD.contains(m.getKey())) {
                continue;
            }
            AttributeInstance ai = self.getAttribute(m.getKey());
            double base = ai == null ? 1 : Math.max(1, Math.abs(ai.getBaseValue()));
            double amount = inst.getEffect().getAttributeModifierValue(inst.getAmplifier(), m.getValue());
            sum += (float) (m.getValue().getOperation() == AttributeModifier.Operation.ADDITION ? amount / base : amount);
        }
        return sum * 4f;
    }

    private boolean inside(AreaEffectCloud c) {
        return c.isAlive() && self.getBoundingBox().intersects(c.getBoundingBox().inflate(0, 0.5, 0))
                && Motor.horizontalDistance(self.position(), c.position()) <= c.getRadius();
    }

    @Nullable
    private AreaEffectCloud cloudHere() {
        for (Perception.Seen s : perception.remembered()) {
            if (s.entity instanceof AreaEffectCloud c && inside(c)) {
                return c;
            }
        }
        for (AreaEffectCloud c : self.level().getEntitiesOfClass(AreaEffectCloud.class, self.getBoundingBox().inflate(1))) {
            if (inside(c)) {
                return c; // standing in it: felt even if not looked at
            }
        }
        return null;
    }

    /** Every tick. Returns true while it has the clone busy (stepping out of a bad cloud / into a good one). */
    public boolean tick(long now, boolean threatened) {
        if (((now + self.getId()) % PERIOD) == 0) {
            sample();
        }
        return clouds(now, threatened);
    }

    private void sample() {
        Brain b = self.getCloneBrain();
        float health = self.getHealth() + self.getAbsorptionAmount();
        int food = self.getFoodData().getFoodLevel();
        if (b == null || lastHealth < 0 || self.isDeadOrDying()) {
            lastHealth = health;
            lastFood = food;
            return;
        }
        float r = (health - lastHealth) + 0.5f * (food - lastFood);
        lastHealth = health;
        lastFood = food;
        Set<String> now = new HashSet<>();
        var effects = self.getActiveEffects();
        if (effects.isEmpty()) {
            baseline += (r - baseline) * 0.05f; // how things go with nothing on
        } else {
            float share = (r - baseline) / effects.size();
            for (MobEffectInstance e : effects) {
                String id = effectId(e.getEffect());
                now.add(id);
                b.learnEffect(id, share + bodyChange(e));
            }
            samples++;
        }
        AreaEffectCloud c = cloudHere();
        if (c != null) {
            // what standing in it did: the effects it put on us (their learned worth) and what happened meanwhile
            float worth = r - baseline;
            for (String id : now) {
                if (!lastEffects.contains(id) || b.knowsEffect(id)) {
                    worth += b.knowsEffect(id) ? b.effectValue(id) : 0;
                }
            }
            b.learnEffect(cloudKey(c), worth);
            debug = cloudKey(c) + " worth " + String.format(java.util.Locale.ROOT, "%.2f", worth) + " v=" + b.effectValue(cloudKey(c));
        }
        lastEffects.clear();
        lastEffects.addAll(now);
    }

    private float cloudValue(AreaEffectCloud c) {
        Brain b = self.getCloneBrain();
        String k = cloudKey(c);
        return b != null && b.knowsEffect(k) ? b.effectValue(k) : 0;
    }

    private boolean clouds(long now, boolean threatened) {
        if (fleeing != null) {
            AreaEffectCloud c = fleeing;
            if (!c.isAlive() || Motor.horizontalDistance(self.position(), c.position()) > c.getRadius() + 1.5) {
                fleeing = null;
                return false;
            }
            Vec3 away = self.position().subtract(c.position());
            if (away.horizontalDistanceSqr() < 0.01) {
                away = Vec3.directionFromRotation(0, self.getYRot());
            }
            motor.moveDirection(away);
            motor.sprint(true);
            return true;
        }
        if (seeking != null) {
            AreaEffectCloud c = seeking;
            if (!c.isAlive() || threatened || self.getHealth() >= self.getMaxHealth() || cloudValue(c) <= 0.2f) {
                seeking = null;
                return false;
            }
            if (Motor.horizontalDistance(self.position(), c.position()) > Math.max(0.5, c.getRadius() * 0.5)) {
                motor.navigate(c.position(), 0.5, false);
            } else {
                motor.stop();
            }
            return true;
        }
        if (((now + self.getId()) & 3) != 0) {
            return false;
        }
        Set<AreaEffectCloud> known = new HashSet<>();
        Set<AreaEffectCloud> seen = new HashSet<>();
        for (Perception.Seen s : perception.remembered()) {
            if (s.entity instanceof AreaEffectCloud c && c.isAlive()) {
                known.add(c);
                if (s.visible) {
                    seen.add(c);
                }
            }
        }
        known.addAll(self.level().getEntitiesOfClass(AreaEffectCloud.class, self.getBoundingBox().inflate(1.5))); // stood in: felt
        for (AreaEffectCloud c : known) {
            float v = cloudValue(c);
            double d = Motor.horizontalDistance(self.position(), c.position());
            if (v < -0.2f && d <= c.getRadius() + 1.0 && Math.abs(self.getY() - c.getY()) < 2.5) {
                fleeing = c; // in (or right at the edge of) something that proved bad: out
                cloudsLeft++;
                return true;
            }
            if (v > 0.2f && !threatened && self.getHealth() < self.getMaxHealth() * 0.7f && d < 12 && seen.contains(c)) {
                seeking = c;
                cloudsSought++;
                return true;
            }
        }
        return false;
    }
}
