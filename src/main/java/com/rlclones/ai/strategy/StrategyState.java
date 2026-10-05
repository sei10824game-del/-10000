package com.rlclones.ai.strategy;

import java.util.Locale;

/** Discrete high-level situation used to pick an {@link Option}. */
public final class StrategyState {
    public static final int HP = 3;
    public static final int FOOD = 3;
    public static final int THREAT = 3;
    /** The original state space. Keeping this as the low-order part leaves every old Q key unchanged. */
    public static final int BASE_STATES = HP * FOOD * THREAT * (1 << 7);
    public static final int CONTEXT_STATES = 1 << 7;

    private static final int READY_BIT = 1;
    private static final int RETURN_BIT = 1 << 1;
    private static final int ROLE_SHIFT = 2;
    private static final int EXPERIENCE_SHIFT = 5;

    private StrategyState() {
    }

    /** Original encoding retained for saved brains and callers that do not have extra context. */
    public static int encode(int hp, int food, int threat, boolean hasFood, boolean items, boolean resource,
                             boolean ally, boolean dark, boolean armed, boolean animals) {
        return encode(hp, food, threat, hasFood, items, resource, ally, dark, armed, animals, 0);
    }

    /**
     * Encodes the legacy situation in the low digits and learned planning context in the high digits.
     * The context is: prepared for a trip, return journey pending, a 3-bit team role mask, and a 2-bit
     * remembered site outcome (0 unknown, 1 positive, 2 negative, 3 mixed).
     */
    public static int encode(int hp, int food, int threat, boolean hasFood, boolean items, boolean resource,
                             boolean ally, boolean dark, boolean armed, boolean animals, int context) {
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
        int safeContext = Math.max(0, Math.min(CONTEXT_STATES - 1, context));
        return safeContext * BASE_STATES + k;
    }

    public static int packContext(boolean tripReady, boolean returnPending, int roleMask, int experienceBand) {
        return (tripReady ? READY_BIT : 0)
                | (returnPending ? RETURN_BIT : 0)
                | ((roleMask & 7) << ROLE_SHIFT)
                | ((experienceBand & 3) << EXPERIENCE_SHIFT);
    }

    public static int context(int key) {
        return key < 0 ? 0 : key / BASE_STATES;
    }

    public static boolean tripReady(int key) {
        return (context(key) & READY_BIT) != 0;
    }

    public static boolean returnPending(int key) {
        return (context(key) & RETURN_BIT) != 0;
    }

    public static int teamRoleMask(int key) {
        return (context(key) >>> ROLE_SHIFT) & 7;
    }

    public static int experienceBand(int key) {
        return (context(key) >>> EXPERIENCE_SHIFT) & 3;
    }

    /** Returns {hp, food, threat, hasFood, items, resource, ally, dark, armed, animals}. */
    public static int[] decode(int key) {
        key = key < 0 ? 0 : key % BASE_STATES;
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
        return String.format(Locale.ROOT,
                "hp=%d food=%d threat=%d hasFood=%d items=%d resource=%d ally=%d dark=%d armed=%d animals=%d ready=%d return=%d roles=%d experience=%d",
                v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], tripReady(key) ? 1 : 0,
                returnPending(key) ? 1 : 0, teamRoleMask(key), experienceBand(key));
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
        q[Option.FARM.ordinal()] = threat == 0 ? 0.75f : -0.4f;
        q[Option.EXPEDITION.ordinal()] = threat == 0 && hp == 2 ? 0.15f : -0.5f;
        q[Option.JOIN.ordinal()] = threat == 0 ? 0.8f : 0.1f;
        q[Option.QUARRY.ordinal()] = threat == 0 ? 0.65f : -0.3f;
        q[Option.DISCOVER.ordinal()] = threat == 0 ? 0.45f : -0.4f;
        q[Option.BREW.ordinal()] = threat == 0 ? 0.6f : -0.4f;
        q[Option.ANIMALS.ordinal()] = threat == 0 ? 0.55f : -0.4f;
        q[Option.FISH.ordinal()] = threat == 0 ? (food <= 1 && !hasFood ? 0.7f : 0.25f) : -0.4f;
        q[Option.SALVAGE.ordinal()] = threat == 0 ? 0.5f : -0.3f;
        q[Option.PORTAL.ordinal()] = threat == 0 ? 0.6f : -0.4f;
        q[Option.FEED.ordinal()] = threat == 0 ? 1.0f : -0.3f;
        q[Option.BREED.ordinal()] = threat == 0 && hp == 2 ? 0.9f : -0.5f;
        q[Option.ACHIEVE.ordinal()] = threat == 0 ? 0.35f : -0.4f;
        q[Option.STAIRS.ordinal()] = threat == 0 ? 0.45f : -0.4f;
        q[Option.SHAFT.ordinal()] = threat == 0 ? 0.4f : -0.4f;
        q[Option.RETURN.ordinal()] = -0.1f;

        int context = context(key);
        if ((context & READY_BIT) != 0 && threat == 0) {
            q[Option.EXPEDITION.ordinal()] += 0.2f;
        }
        if ((context & RETURN_BIT) != 0) {
            q[Option.RETURN.ordinal()] = food == 0 || hp == 0 ? 1.5f : 1.0f;
        }
        if (experienceBand(key) == 2) {
            q[Option.EXPLORE.ordinal()] -= 0.15f;
            q[Option.EXPEDITION.ordinal()] -= 0.15f;
            q[Option.LOOT.ordinal()] -= 0.1f;
        }

        int roles = teamRoleMask(key);
        if ((roles & 1) != 0) {
            q[Option.FARM.ordinal()] -= 0.05f;
            q[Option.HUNT.ordinal()] -= 0.05f;
            q[Option.FISH.ordinal()] -= 0.05f;
        }
        if ((roles & 2) != 0) {
            q[Option.GATHER_WOOD.ordinal()] -= 0.05f;
            q[Option.MINE.ordinal()] -= 0.05f;
            q[Option.QUARRY.ordinal()] -= 0.05f;
        }
        if ((roles & 4) != 0) {
            q[Option.EXPLORE.ordinal()] -= 0.05f;
            q[Option.DISCOVER.ordinal()] -= 0.05f;
        }
    }
}
