package com.rlclones.ai.strategy;

/** Compact boundary state for learned skill initiation and termination. */
public final class SkillState {
    public static final int PARAMETER_BINS = 8;
    public static final int PROGRESS_BINS = 4;
    public static final int STATES = LearnedSkill.COUNT * LongTermGoal.COUNT * GoalState.STATES * PARAMETER_BINS * PROGRESS_BINS;

    public static final int DEFER = 0;
    public static final int INITIATE = 1;
    public static final int CONTINUE = 0;
    public static final int TERMINATE = 1;
    public static final int BOUNDARY_ACTIONS = 2;

    private SkillState() {
    }

    public static int encode(int skillId, int goalId, int goalState, int parameterBin, int progressBin) {
        int s = clamp(skillId, LearnedSkill.COUNT) * LongTermGoal.COUNT + clamp(goalId, LongTermGoal.COUNT);
        s = s * GoalState.STATES + clamp(goalState, GoalState.STATES);
        s = s * PARAMETER_BINS + clamp(parameterBin, PARAMETER_BINS);
        return s * PROGRESS_BINS + clamp(progressBin, PROGRESS_BINS);
    }

    /** Returns {skillId, goalId, goalState, parameterBin, progressBin}. */
    public static int[] decode(int state) {
        int s = Math.max(0, Math.min(STATES - 1, state));
        int progress = s % PROGRESS_BINS;
        s /= PROGRESS_BINS;
        int parameter = s % PARAMETER_BINS;
        s /= PARAMETER_BINS;
        int goalState = s % GoalState.STATES;
        s /= GoalState.STATES;
        int goal = s % LongTermGoal.COUNT;
        s /= LongTermGoal.COUNT;
        return new int[]{s, goal, goalState, parameter, progress};
    }

    /** Priors are mild: learned boundary values can move a skill's start decision either way. */
    public static void initiationPrior(int state, float[] q) {
        int[] v = decode(state);
        LearnedSkill skill = LearnedSkill.fromId(v[0]);
        LongTermGoal goal = LongTermGoal.fromId(v[1]);
        int[] goalState = GoalState.decode(v[2]);
        int threat = goalState[2];
        q[DEFER] = 0f;
        q[INITIATE] = skill.goal() == goal && skill != LearnedSkill.NONE ? 0.12f : -0.08f;
        if (threat == 2 && skill != LearnedSkill.RETURN_ROUTE && skill != LearnedSkill.PARTNER_AID) {
            q[INITIATE] -= 0.18f;
        }
        if (v[3] > 0) {
            q[INITIATE] += 0.015f * Math.min(4, v[3]); // more specific parameters make a useful skill instance
        }
    }

    /** A prior stop condition based on progress and goal alignment, corrected by learned outcomes. */
    public static void terminationPrior(int state, float[] q) {
        int[] v = decode(state);
        LearnedSkill skill = LearnedSkill.fromId(v[0]);
        LongTermGoal goal = LongTermGoal.fromId(v[1]);
        int progress = v[4];
        q[CONTINUE] = progress < 2 ? 0.12f : -0.02f;
        q[TERMINATE] = progress >= 2 ? 0.18f : -0.04f;
        if (progress >= 3) {
            q[TERMINATE] += 0.25f;
        }
        if (skill == LearnedSkill.NONE || skill.goal() != goal) {
            q[TERMINATE] = 1f;
            q[CONTINUE] = -1f;
        }
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
