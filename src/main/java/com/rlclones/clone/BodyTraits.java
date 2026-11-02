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
public record BodyTraits(boolean sun, boolean water, boolean fire, int safeDrop, boolean breathes, boolean undead) {
    public static final BodyTraits PLAYER = new BodyTraits(false, false, false, 3, false, false);
    private static final int NEVER = Integer.MAX_VALUE;

    /** Types that take no fall damage. */
    private static final Set<EntityType<?>> FALL_IMMUNE = Set.of(EntityType.BLAZE, EntityType.IRON_GOLEM, EntityType.SNOW_GOLEM, EntityType.MAGMA_CUBE,
            EntityType.SLIME, EntityType.CHICKEN, EntityType.CAT, EntityType.OCELOT, EntityType.SHULKER, EntityType.BAT, EntityType.BEE, EntityType.PARROT,
            EntityType.ALLAY, EntityType.VEX, EntityType.GHAST, EntityType.PHANTOM, EntityType.WITHER, EntityType.ENDER_DRAGON);
    /** Undead that do not burn in daylight. */
    private static final Set<EntityType<?>> SUN_PROOF = Set.of(EntityType.HUSK, EntityType.WITHER_SKELETON, EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN);

    public static BodyTraits of(Player p) {
        Mob b = CloneMobs.bodyOf(p);
        return b == null ? PLAYER : of(b);
    }

    public static BodyTraits of(Mob b) {
        boolean fire = b.fireImmune();
        boolean undead = b.getMobType() == MobType.UNDEAD;
        return new BodyTraits(undead && !fire && !SUN_PROOF.contains(b.getType()), b.isSensitiveToWater(), fire,
                FALL_IMMUNE.contains(b.getType()) ? NEVER : 3, b.canBreatheUnderwater(), undead);
    }

    public boolean fallImmune() {
        return safeDrop == NEVER;
    }
}
