package com.rlclones.ai.strategy;

import java.util.Locale;

/** Discrete high-level situation used to pick an {@link Option}. */
public final class StrategyState {
    public static final int HP = 3;
    public static final int FOOD = 3;
    public static final int THREAT = 3;

    private StrategyState() {
    }

    public static int encode(int hp, int food, int threat, boolean hasFood, boolean items, boolean resource,
                             boolean ally, boolean dark, boolean armed, boolean animals) {
        int k = Math.max(0, Math.min(HP - 1, hp));
        k = k * FOOD + Math.max(0, Math.min(FOOD - 1, food));
        k = k * THREAT + Math.max(0, Math.min(THREAT - 1, threat));
        k = k * 2 + (hasFood ? 1 : 0);
        k = k * 2 + (items ? 1 : 0);
        k = k * 2 + (resource ? 1 : 0);
        k = k * 2 + (ally ? 1 : 0);
        k = k * 2 + (dark ? 1 : 0);
        k = k * 2 + (armed ? 1 : 0);
        k = k * 2 + (animals ? 1 : 0);
        return k;
    }

    /** Returns {hp, food, threat, hasFood, items, resource, ally, dark, armed, animals}. */
    public static int[] decode(int key) {
        int[] v = new int[10];
        for (int i = 9; i >= 3; i--) {
            v[i] = key & 1;
            key >>= 1;
        }
        v[2] = key % THREAT;
        key /= THREAT;
        v[1] = key % FOOD;
        key /= FOOD;
        v[0] = key;
        return v;
    }

    public static String describe(int key) {
        int[] v = decode(key);
        return String.format(Locale.ROOT, "hp=%d food=%d threat=%d hasFood=%d items=%d resource=%d ally=%d dark=%d armed=%d animals=%d",
                v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    }

    public static void prior(int key, float[] q) {
        int[] v = decode(key);
        int hp = v[0];
        int food = v[1];
        int threat = v[2];
        boolean hasFood = v[3] == 1;
        boolean animals = v[9] == 1;

        q[Option.FIGHT.ordinal()] = threat == 1 ? 1.0f : threat == 2 ? (hp == 2 ? 0.3f : -0.5f) : 0f;
        q[Option.FLEE.ordinal()] = threat == 2 ? (hp == 0 ? 1.2f : 0.5f) : (threat == 1 && hp == 0 ? 0.6f : -0.2f);
        q[Option.EAT.ordinal()] = food == 0 ? 1.5f : food == 1 ? (hp < 2 ? 1.0f : 0.5f) : 0f;
        q[Option.COLLECT.ordinal()] = threat == 0 ? 0.5f : 0f;
        q[Option.FOLLOW.ordinal()] = 0.2f + (v[7] == 1 ? 0.2f : 0f);
        q[Option.EXPLORE.ordinal()] = 0.1f;
        q[Option.GATHER_WOOD.ordinal()] = threat == 0 ? 0.4f : 0f;
        q[Option.MINE.ordinal()] = threat == 0 ? 0.4f : 0f;
        q[Option.HUNT.ordinal()] = animals && !hasFood && food <= 1 ? 0.8f : 0.1f;
        q[Option.REST.ordinal()] = hp < 2 && threat == 0 ? 0.3f : 0.05f;
        q[Option.CRAFT.ordinal()] = threat == 0 ? 0.7f : -0.3f;
        q[Option.HELP.ordinal()] = threat == 0 ? (hp == 2 ? 1.0f : hp == 1 ? 0.5f : -0.3f) : threat == 1 ? 0.2f : -0.5f;
        q[Option.STORE.ordinal()] = threat == 0 ? 0.8f : -0.5f;
        q[Option.FETCH.ordinal()] = threat == 0 ? 0.9f : -0.3f;
        q[Option.LOOT.ordinal()] = threat == 0 ? 0.6f : -0.3f;
        q[Option.FARM.ordinal()] = threat == 0 ? 0.5f : -0.4f;
        q[Option.EXPEDITION.ordinal()] = threat == 0 && hp == 2 ? 0.15f : -0.5f;
        q[Option.JOIN.ordinal()] = threat == 0 ? 0.8f : 0.1f;
        q[Option.QUARRY.ordinal()] = threat == 0 ? 0.65f : -0.3f;
        q[Option.DISCOVER.ordinal()] = threat == 0 ? 0.45f : -0.4f;
        q[Option.BREW.ordinal()] = threat == 0 ? 0.6f : -0.4f;
        q[Option.ANIMALS.ordinal()] = threat == 0 ? 0.55f : -0.4f;
        q[Option.FISH.ordinal()] = threat == 0 ? (food <= 1 && !hasFood ? 0.7f : 0.25f) : -0.4f;
        q[Option.SALVAGE.ordinal()] = threat == 0 ? 0.5f : -0.3f;
    }
}
