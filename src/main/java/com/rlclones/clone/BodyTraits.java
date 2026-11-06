package com.rlclones.clone;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.player.Player;

import java.util.Set;

/**
 * What a clone mob's body is born knowing (plan.md 73.1): fire / water / drowning / fall / sun. A plain clone gets {@link #PLAYER}
 * (nothing changes for it). Read when asked, never written into the (shared, saved) Brain.
 * ponytail: no datapack JSON overrides yet (husk / fall-immune are a short list below); add the JSON loader when a mod mob needs it.
 */
public record BodyTraits(boolean sun, boolean water, boolean fire, int safeDrop, boolean breathes, boolean undead, boolean flies, boolean phases) {
    public static final BodyTraits PLAYER = new BodyTraits(false, false, false, 3, false, false, false, false);
    private static final int NEVER = Integer.MAX_VALUE;

    /** Types that take no fall damage. */
    private static final Set<EntityType<?>> FALL_IMMUNE = Set.of(EntityType.BLAZE, EntityType.IRON_GOLEM, EntityType.SNOW_GOLEM, EntityType.MAGMA_CUBE,
            EntityType.SLIME, EntityType.CHICKEN, EntityType.CAT, EntityType.OCELOT, EntityType.SHULKER, EntityType.BAT, EntityType.BEE, EntityType.PARROT,
            EntityType.ALLAY, EntityType.VEX, EntityType.GHAST, EntityType.PHANTOM, EntityType.WITHER, EntityType.ENDER_DRAGON);
    /** Undead that do not burn in daylight. */
    private static final Set<EntityType<?>> SUN_PROOF = Set.of(EntityType.HUSK, EntityType.WITHER_SKELETON, EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN);

    public static BodyTraits of(Player p) {
        var bodies = CloneMobs.bodiesOf(p);
        if (bodies.isEmpty()) {
            return PLAYER;
        }
        BodyTraits t = of(bodies.get(0));
        for (int i = 1; i < bodies.size(); i++) { // a chimera: every strength of every body, a weakness only when all have it
            BodyTraits o = of(bodies.get(i));
            t = new BodyTraits(t.sun() && o.sun(), t.water() && o.water(), t.fire() || o.fire(), Math.max(t.safeDrop(), o.safeDrop()), t.breathes() || o.breathes(),
                    t.undead() || o.undead(), t.flies() || o.flies(), t.phases() || o.phases());
        }
        return t;
    }

    public static BodyTraits of(Mob b) {
        boolean fire = b.fireImmune();
        boolean undead = b.getMobType() == MobType.UNDEAD;
        boolean flies = b instanceof net.minecraft.world.entity.FlyingMob || b instanceof net.minecraft.world.entity.animal.FlyingAnimal || b instanceof net.minecraft.world.entity.ambient.Bat
                || b instanceof net.minecraft.world.entity.monster.Vex || b instanceof net.minecraft.world.entity.monster.Blaze || b instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                || b instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon || b instanceof net.minecraft.world.entity.animal.allay.Allay
                || b.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.FlyingPathNavigation; // (other mods' flyers too)
        return new BodyTraits(undead && !fire && !SUN_PROOF.contains(b.getType()), b.isSensitiveToWater(), fire,
                flies || FALL_IMMUNE.contains(b.getType()) ? NEVER : 3, b.canBreatheUnderwater(), undead, flies, b instanceof net.minecraft.world.entity.monster.Vex || b.noPhysics);
    }

    public boolean fallImmune() {
        return safeDrop == NEVER;
    }
}
