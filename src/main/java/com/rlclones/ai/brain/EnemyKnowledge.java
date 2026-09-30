package com.rlclones.ai.brain;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything a clone has learned about one entity type purely by watching it:
 * attack reach, attack cooldown, damage, projectile range, explosions, speed, aggro range and behaviour traits.
 * All distances are gaps between bounding boxes (0 = touching) so they compare directly with the player reach.
 */
public final class EnemyKnowledge {
    public static final String BURNS_IN_DAYLIGHT = "burns_in_daylight";
    public static final String CLIMBS = "climbs";
    public static final String FLIES = "flies";
    public static final String TELEPORTS = "teleports";
    public static final String SPLITS = "splits_on_death";
    public static final String DISABLES_SHIELD = "disables_shield";
    public static final String RANGED = "ranged";
    public static final String EXPLOSIVE = "explosive";
    public static final String MELEE = "melee";
    public static final String AGGRESSIVE = "attacks_unprovoked";

    public final String typeId;

    public final Estimator meleeRange = Estimator.upper(1.0);
    public final Estimator meleeCooldown = Estimator.lower(20);
    public final Estimator meleeDamage = Estimator.mean(3.0);
    public final Estimator rangedRange = Estimator.upper(15.0);
    public final Estimator rangedCooldown = Estimator.lower(40);
    public final Estimator rangedDamage = Estimator.mean(3.0);
    public final Estimator blastRadius = Estimator.upper(3.0);
    public final Estimator fuseTicks = Estimator.mean(30);
    public final Estimator explosionDamage = Estimator.mean(10);
    public final Estimator speed = Estimator.upper(0.1);
    public final Estimator aggroRange = Estimator.upper(16);
    public final Estimator health = Estimator.mean(20);

    public int sightings;
    public int encounters;
    public int killedByClones;
    public int agentsKilled;
    public final Object2IntMap<String> traits = new Object2IntOpenHashMap<>();
    public final Object2IntMap<String> effects = new Object2IntOpenHashMap<>();

    public EnemyKnowledge(String typeId) {
        this.typeId = typeId;
    }

    public void trait(String trait) {
        traits.mergeInt(trait, 1, Integer::sum);
    }

    public boolean has(String trait) {
        return traits.getInt(trait) >= 2 || (traits.getInt(trait) >= 1 && (trait.equals(RANGED) || trait.equals(EXPLOSIVE) || trait.equals(MELEE) || trait.equals(DISABLES_SHIELD)));
    }

    public boolean isRanged() {
        return has(RANGED) && rangedDamage.count() >= meleeDamage.count();
    }

    public boolean isExplosive() {
        return has(EXPLOSIVE);
    }

    /** Distance (box gap) inside which this enemy can hurt you. */
    public double threatRange() {
        double r = meleeRange.get();
        if (has(RANGED)) {
            r = Math.max(r, rangedRange.get());
        }
        if (isExplosive()) {
            r = Math.max(r, blastRadius.get());
        }
        return r;
    }

    /** Primary attack cooldown in ticks. */
    public double cooldown() {
        if (isRanged()) {
            return rangedCooldown.get();
        }
        return meleeCooldown.get();
    }

    /** Estimated damage per second against an unarmoured player. */
    public double dps() {
        double melee = meleeDamage.get() * 20.0 / Math.max(5.0, meleeCooldown.get());
        double ranged = has(RANGED) ? rangedDamage.get() * 20.0 / Math.max(10.0, rangedCooldown.get()) : 0;
        double boom = isExplosive() ? explosionDamage.get() / 2.0 : 0;
        return Math.max(melee, Math.max(ranged, boom));
    }

    public int observations() {
        return meleeDamage.count() + rangedDamage.count() + blastRadius.count();
    }

    public void merge(EnemyKnowledge o) {
        meleeRange.merge(o.meleeRange);
        meleeCooldown.merge(o.meleeCooldown);
        meleeDamage.merge(o.meleeDamage);
        rangedRange.merge(o.rangedRange);
        rangedCooldown.merge(o.rangedCooldown);
        rangedDamage.merge(o.rangedDamage);
        blastRadius.merge(o.blastRadius);
        fuseTicks.merge(o.fuseTicks);
        explosionDamage.merge(o.explosionDamage);
        speed.merge(o.speed);
        aggroRange.merge(o.aggroRange);
        health.merge(o.health);
        sightings += o.sightings;
        encounters += o.encounters;
        killedByClones += o.killedByClones;
        agentsKilled += o.agentsKilled;
        o.traits.object2IntEntrySet().forEach(e -> traits.mergeInt(e.getKey(), e.getIntValue(), Integer::sum));
        o.effects.object2IntEntrySet().forEach(e -> effects.mergeInt(e.getKey(), e.getIntValue(), Integer::sum));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("meleeRange", meleeRange.save());
        tag.put("meleeCooldown", meleeCooldown.save());
        tag.put("meleeDamage", meleeDamage.save());
        tag.put("rangedRange", rangedRange.save());
        tag.put("rangedCooldown", rangedCooldown.save());
        tag.put("rangedDamage", rangedDamage.save());
        tag.put("blastRadius", blastRadius.save());
        tag.put("fuseTicks", fuseTicks.save());
        tag.put("explosionDamage", explosionDamage.save());
        tag.put("speed", speed.save());
        tag.put("aggroRange", aggroRange.save());
        tag.put("health", health.save());
        tag.putInt("sightings", sightings);
        tag.putInt("encounters", encounters);
        tag.putInt("killedByClones", killedByClones);
        tag.putInt("agentsKilled", agentsKilled);
        CompoundTag t = new CompoundTag();
        traits.object2IntEntrySet().forEach(e -> t.putInt(e.getKey(), e.getIntValue()));
        tag.put("traits", t);
        CompoundTag f = new CompoundTag();
        effects.object2IntEntrySet().forEach(e -> f.putInt(e.getKey(), e.getIntValue()));
        tag.put("effects", f);
        return tag;
    }

    public static EnemyKnowledge load(String typeId, CompoundTag tag) {
        EnemyKnowledge k = new EnemyKnowledge(typeId);
        k.meleeRange.load(tag.getCompound("meleeRange"));
        k.meleeCooldown.load(tag.getCompound("meleeCooldown"));
        k.meleeDamage.load(tag.getCompound("meleeDamage"));
        k.rangedRange.load(tag.getCompound("rangedRange"));
        k.rangedCooldown.load(tag.getCompound("rangedCooldown"));
        k.rangedDamage.load(tag.getCompound("rangedDamage"));
        k.blastRadius.load(tag.getCompound("blastRadius"));
        k.fuseTicks.load(tag.getCompound("fuseTicks"));
        k.explosionDamage.load(tag.getCompound("explosionDamage"));
        k.speed.load(tag.getCompound("speed"));
        k.aggroRange.load(tag.getCompound("aggroRange"));
        k.health.load(tag.getCompound("health"));
        k.sightings = tag.getInt("sightings");
        k.encounters = tag.getInt("encounters");
        k.killedByClones = tag.getInt("killedByClones");
        k.agentsKilled = tag.getInt("agentsKilled");
        CompoundTag t = tag.getCompound("traits");
        for (String key : t.getAllKeys()) {
            k.traits.put(key, t.getInt(key));
        }
        CompoundTag f = tag.getCompound("effects");
        for (String key : f.getAllKeys()) {
            k.effects.put(key, f.getInt(key));
        }
        return k;
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "sightings=%d encounters=%d killed=%d killedAgents=%d", sightings, encounters, killedByClones, agentsKilled));
        lines.add(fmt("melee reach", meleeRange, "blocks") + "  " + fmt("melee cooldown", meleeCooldown, "ticks") + "  " + fmt("melee dmg", meleeDamage, "hp"));
        if (has(RANGED) || rangedDamage.known()) {
            lines.add(fmt("ranged reach", rangedRange, "blocks") + "  " + fmt("ranged cooldown", rangedCooldown, "ticks") + "  " + fmt("ranged dmg", rangedDamage, "hp"));
        }
        if (isExplosive()) {
            lines.add(fmt("blast radius", blastRadius, "blocks") + "  " + fmt("fuse", fuseTicks, "ticks") + "  " + fmt("blast dmg", explosionDamage, "hp"));
        }
        lines.add(fmt("speed", speed, "b/t") + "  " + fmt("aggro range", aggroRange, "blocks") + "  " + fmt("health", health, "hp"));
        if (!traits.isEmpty()) {
            lines.add("traits: " + traits);
        }
        if (!effects.isEmpty()) {
            lines.add("inflicts: " + effects);
        }
        return lines;
    }

    private static String fmt(String name, Estimator e, String unit) {
        return e.known() ? String.format(Locale.ROOT, "%s=%.2f%s(n=%d)", name, e.get(), unit, e.count()) : name + "=?";
    }
}
