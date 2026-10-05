package com.rlclones.clone;

import java.util.UUID;

/** Stable, identity-bound reward preferences. The same event can therefore feel more costly to one clone than another. */
public final class ClonePersonality {
    public final float injuryPenalty;
    public final float hungerPenalty;
    public final float deathPenalty;
    public final float failurePenalty;
    public final float curiosityReward;
    public final float cooperationReward;
    public final float homeReward;

    private ClonePersonality(float injuryPenalty, float hungerPenalty, float deathPenalty, float failurePenalty,
                             float curiosityReward, float cooperationReward, float homeReward) {
        this.injuryPenalty = injuryPenalty;
        this.hungerPenalty = hungerPenalty;
        this.deathPenalty = deathPenalty;
        this.failurePenalty = failurePenalty;
        this.curiosityReward = curiosityReward;
        this.cooperationReward = cooperationReward;
        this.homeReward = homeReward;
    }

    /**
     * Generate repeatable traits from the clone's UUID. A respawn or world reload keeps the personality,
     * while a newly summoned/bred clone gets its own reward profile even when its brain is linked to others.
     */
    public static ClonePersonality forId(UUID id) {
        long seed = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 19);
        return new ClonePersonality(
                range(seed, 0x19a4d2f3L, 0.75f, 1.55f),
                range(seed, 0x6e2b79c1L, 0.70f, 1.50f),
                range(seed, 0x1f83d9abL, 0.80f, 1.60f),
                range(seed, 0x5be0cd19L, 0.70f, 1.50f),
                range(seed, 0xcbbb9d5dL, 0.70f, 1.40f),
                range(seed, 0x629a292aL, 0.70f, 1.40f),
                range(seed, 0x9159015aL, 0.70f, 1.40f));
    }

    /** Neutral values for non-clone agents observed by the imitation learner. */
    public static ClonePersonality neutral() {
        return new ClonePersonality(1f, 1f, 1f, 1f, 1f, 1f, 1f);
    }

    private static float range(long seed, long salt, float min, float max) {
        long z = seed + salt;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        z ^= z >>> 31;
        double unit = (z >>> 11) * 0x1.0p-53;
        return (float) (min + (max - min) * unit);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "injury=%.2f hunger=%.2f death=%.2f failure=%.2f curiosity=%.2f cooperation=%.2f home=%.2f",
                injuryPenalty, hungerPenalty, deathPenalty, failurePenalty, curiosityReward, cooperationReward, homeReward);
    }
}
