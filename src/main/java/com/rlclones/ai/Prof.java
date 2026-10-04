package com.rlclones.ai;

/**
 * A tiny section timer for the clone tick (switched on by the performance test only; off it costs nothing but a
 * boolean check). Shows where the time goes when many clones run at once.
 */
public final class Prof {
    public static final String[] NAMES = {"total", "perception", "watcher", "reflexes", "trap", "hazard", "strategy", "motor"};
    public static final int TOTAL = 0, PERCEPTION = 1, WATCHER = 2, REFLEXES = 3, TRAP = 4, HAZARD = 5, STRATEGY = 6, MOTOR = 7;
    public static boolean on;
    public static final long[] NANOS = new long[NAMES.length];
    public static long cloneTicks;

    private Prof() {
    }

    public static long t() {
        return on ? System.nanoTime() : 0L;
    }

    public static void add(int slot, long start) {
        if (on) {
            NANOS[slot] += System.nanoTime() - start;
        }
    }

    public static void reset() {
        java.util.Arrays.fill(NANOS, 0L);
        cloneTicks = 0;
    }

    /** Microseconds per clone tick, by section. */
    public static String report() {
        StringBuilder sb = new StringBuilder();
        long n = Math.max(1, cloneTicks);
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]).append('=').append(NANOS[i] / 1000 / n).append("us ");
        }
        return sb.append("over ").append(cloneTicks).append(" clone ticks").toString();
    }
}
