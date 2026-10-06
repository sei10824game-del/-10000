package com.rlclones.ai.strategy;

/**
 * Reusable, parameterized temporal skills layered over the ordinary Q-learned options.
 * A skill supplies context and a learned start/stop boundary; it never dictates an option sequence.
 */
public enum LearnedSkill {
    NONE(0, LongTermGoal.NONE),
    RESOURCE_RUN(1, LongTermGoal.PROGRESSION),
    FOOD_RESERVE(2, LongTermGoal.FOOD_SECURITY),
    SCOUTING(3, LongTermGoal.EXPLORE),
    PARTNER_AID(4, LongTermGoal.COOPERATE),
    RETURN_ROUTE(5, LongTermGoal.RETURN_HOME);

    public static final LearnedSkill[] VALUES = values();
    public static final int COUNT = VALUES.length;

    private final int id;
    private final LongTermGoal goal;

    LearnedSkill(int id, LongTermGoal goal) {
        this.id = id;
        this.goal = goal;
    }

    public int id() {
        return id;
    }

    public LongTermGoal goal() {
        return goal;
    }

    public static LearnedSkill fromId(int id) {
        for (LearnedSkill skill : VALUES) {
            if (skill.id == id) {
                return skill;
            }
        }
        return NONE;
    }
}
