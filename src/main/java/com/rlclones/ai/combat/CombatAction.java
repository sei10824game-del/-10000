package com.rlclones.ai.combat;

/** Low-level combat actions. Each one is executed for a few ticks and then a new decision is taken. */
public enum CombatAction {
    APPROACH(4),
    ATTACK(8),
    CRIT_ATTACK(14),
    SPRINT_ATTACK(10),
    RETREAT(5),
    STRAFE_LEFT(5),
    STRAFE_RIGHT(5),
    BLOCK(8),
    HOLD(4),
    SHOOT(40),
    PILLAR(14),
    /** Right-click the held special weapon (modded ability, gun, staff...); the effect is learned per enemy. */
    USE_ITEM(45);

    public static final CombatAction[] VALUES = values();
    public static final int COUNT = VALUES.length;
    public static final int ALL = (1 << COUNT) - 1;

    /** Maximum number of ticks this action runs before a new decision. */
    public final int duration;

    CombatAction(int duration) {
        this.duration = duration;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
