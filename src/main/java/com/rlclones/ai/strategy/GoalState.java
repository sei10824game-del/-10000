package com.rlclones.ai.strategy;

/** Compact abstract situation observed by the learned long-term goal policy. */
public final class GoalState {
    public static final int TIERS = 7;
    public static final int FOOD_BINS = 3;
    public static final int THREATS = 3;
    public static final int STATES = TIERS * FOOD_BINS * THREATS * 2 * 2;

    private GoalState() {
    }

    /**
     * @param progressionTier 0..6 (wood through complete diamond gear)
     * @param foodBin         combined supplies/satiety: 0 = low, 1 = some, 2 = stocked and fed
     * @param threat          0 = calm, 1 = nearby, 2 = severe
     * @param returning       a learned expedition return is waiting
     * @param socialNeed      a teammate currently has a request the clone can answer
     */
    public static int encode(int progressionTier, int foodBin, int threat, boolean returning, boolean socialNeed) {
        int state = clamp(progressionTier, TIERS) * FOOD_BINS + clamp(foodBin, FOOD_BINS);
        state = state * THREATS + clamp(threat, THREATS);
        state = state * 2 + (returning ? 1 : 0);
        return state * 2 + (socialNeed ? 1 : 0);
    }

    public static int[] decode(int state) {
        int k = Math.max(0, Math.min(STATES - 1, state));
        int socialNeed = k & 1;
        k >>>= 1;
        int returning = k & 1;
        k >>>= 1;
        int threat = k % THREATS;
        k /= THREATS;
        int food = k % FOOD_BINS;
        k /= FOOD_BINS;
        return new int[]{k, food, threat, returning, socialNeed};
    }

    /** Mild initial preferences; Q updates can override them as experience accumulates. */
    public static void prior(int state, float[] q) {
        int[] v = decode(state);
        int tier = v[0];
        int food = v[1];
        int threat = v[2];
        boolean returning = v[3] != 0;
        boolean socialNeed = v[4] != 0;

        q[LongTermGoal.PROGRESSION.id()] = tier < 6 ? 0.12f + (6 - tier) * 0.025f : -0.5f;
        q[LongTermGoal.FOOD_SECURITY.id()] = food == 0 ? 0.35f : food == 1 ? 0.12f : -0.2f;
        q[LongTermGoal.EXPLORE.id()] = threat == 0 ? 0.16f : threat == 1 ? -0.05f : -0.25f;
        q[LongTermGoal.COOPERATE.id()] = socialNeed && threat < 2 ? 0.2f : -0.1f;
        q[LongTermGoal.RETURN_HOME.id()] = returning ? 0.7f : -0.35f;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
