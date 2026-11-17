package com.rlclones.ai;

/**
 * A tiny section timer for the clone tick (switched on by the performance test only; off it costs nothing but a
 * boolean check). Shows where the time goes when many clones run at once.
 */
public final class Prof {
    public static final String[] NAMES = {"total", "perception", "watcher", "reflexes", "trap", "hazard", "strategy", "motor", "decide", "pathing", "p.entities", "p.vision", "p.blocks"};
    public static final int TOTAL = 0, PERCEPTION = 1, WATCHER = 2, REFLEXES = 3, TRAP = 4, HAZARD = 5, STRATEGY = 6, MOTOR = 7, DECIDE = 8, PATHING = 9, P_ENTITIES = 10, P_VISION = 11, P_BLOCKS = 12;
    public static boolean on;
    public static final long[] NANOS = new long[NAMES.length];
    public static long cloneTicks;
    /** The clone mobs' body sync (per server tick, all bodies), and the server ticks it was timed over. */
    public static long mobNanos, serverTicks;

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

    /** Nanoseconds by option (what the strategy section is spent on), and the ticks each option ran. */
    public static final long[] OPT_NANOS = new long[64];
    public static final long[] OPT_TICKS = new long[64];

    public static void addOption(int ordinal, long start) {
        if (on) {
            OPT_NANOS[ordinal] += System.nanoTime() - start;
            OPT_TICKS[ordinal]++;
        }
    }

    public static void reset() {
        java.util.Arrays.fill(OPT_NANOS, 0L);
        java.util.Arrays.fill(OPT_TICKS, 0L);
        java.util.Arrays.fill(NANOS, 0L);
        cloneTicks = 0;
        mobNanos = 0;
        serverTicks = 0;
    }

    /** Microseconds per clone tick, by section. */
    public static String report() {
        StringBuilder sb = new StringBuilder();
        long n = Math.max(1, cloneTicks);
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]).append('=').append(NANOS[i] / 1000 / n).append("us ");
        }
        sb.append("| by option (us per tick it ran, share of clone ticks): ");
        for (com.rlclones.ai.strategy.Option o : com.rlclones.ai.strategy.Option.values()) {
            long t = OPT_TICKS[o.ordinal()];
            if (t > 0) {
                sb.append(o.name()).append('=').append(OPT_NANOS[o.ordinal()] / 1000 / t).append("us/").append(t * 100 / n).append("% ");
            }
        }
        return sb.append("over ").append(cloneTicks).append(" clone ticks").toString();
    }
}
