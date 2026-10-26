package com.rlclones.ai;

import java.util.Map;

/**
 * What the clones die of (CloneController.DEATHS, all clones, since the server started) turns the matching care up for every clone: when a kind of death is
 * a fifth of the deaths (level 1) or two fifths (level 2), at least six deaths in all, the reflexes against it start sooner. ponytail: four causes
 * (drowning, lava, hunger, fire), counted by the damage-type name; thresholds are plain numbers; add a cause by adding a line to {@link #group}.
 */
public final class Caution {
    private Caution() {
    }

    private static long cachedAt = Long.MIN_VALUE;
    private static final int[] LEVELS = new int[4];
    public static final int DROWN = 0, LAVA = 1, HUNGER = 2, FIRE = 3;

    private static int group(String msgId) {
        return switch (msgId) {
            case "drown" -> DROWN;
            case "lava" -> LAVA;
            case "starve" -> HUNGER;
            case "onFire", "inFire", "hotFloor" -> FIRE;
            default -> -1;
        };
    }

    private static void refresh(long now) {
        if (now - cachedAt < 400) {
            return;
        }
        cachedAt = now;
        int total = 0;
        int[] n = new int[4];
        for (Map.Entry<String, Integer> e : CloneController.DEATHS.entrySet()) {
            total += e.getValue();
            int g = group(e.getKey().split(" ")[0]);
            if (g >= 0) {
                n[g] += e.getValue();
            }
        }
        for (int g = 0; g < 4; g++) {
            LEVELS[g] = total < 6 ? 0 : n[g] * 5 >= total * 2 ? 2 : n[g] * 5 >= total ? 1 : 0;
        }
    }

    /** 0, 1 or 2: how much care this kind of death calls for right now. */
    public static int level(int cause, long now) {
        refresh(now);
        return LEVELS[cause];
    }

    /** The share of the air bar at which a swimmer heads for the surface: 0.3 normally, higher after many drownings. */
    public static double airUp(long now) {
        return 0.3 + 0.1 * level(DROWN, now);
    }
}
