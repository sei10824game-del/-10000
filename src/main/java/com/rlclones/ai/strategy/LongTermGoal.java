package com.rlclones.ai.strategy;

/**
 * Learned abstract goals. The goal policy chooses one of these; the ordinary strategy policy still chooses
 * every concrete option, so these are not scripted action sequences.
 */
public enum LongTermGoal {
    NONE(0),
    PROGRESSION(1),
    FOOD_SECURITY(2),
    EXPLORE(3),
    COOPERATE(4),
    RETURN_HOME(5);

    public static final LongTermGoal[] VALUES = values();
    public static final int COUNT = VALUES.length;
    public static final int ALL = (1 << COUNT) - 2; // NONE is a state marker, never an action.

    private final int id;

    LongTermGoal(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public int bit() {
        return 1 << id;
    }

    public static LongTermGoal fromId(int id) {
        for (LongTermGoal goal : VALUES) {
            if (goal.id == id) {
                return goal;
            }
        }
        return NONE;
    }
}
