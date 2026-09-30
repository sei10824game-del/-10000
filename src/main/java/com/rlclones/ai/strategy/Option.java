package com.rlclones.ai.strategy;

import java.util.Locale;

/** High-level behaviours ("options") chosen by the strategy Q-table. */
public enum Option {
    FIGHT(160),
    FLEE(140),
    EAT(60),
    COLLECT(120),
    FOLLOW(100),
    EXPLORE(200),
    GATHER_WOOD(300),
    MINE(300),
    HUNT(160),
    REST(60);

    public static final Option[] VALUES = values();
    public static final int COUNT = VALUES.length;

    /** Hard time limit in ticks; the option may also terminate earlier on its own. */
    public final int maxTicks;

    Option(int maxTicks) {
        this.maxTicks = maxTicks;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
