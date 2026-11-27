package com.rlclones.ai.util;

/** Exact staircase footprint in coordinates relative to its top; horizontal direction only. */
public final class StairFootprint {
    private StairFootprint() {}
    public static boolean space(int dx, int dy, int dz, int dirX, int dirZ, int steps, boolean finished, boolean wide) {
        int more = wide && !finished ? 40 : 2;
        int reach = steps + more + 6;
        if (Math.abs(dx) > reach || Math.abs(dz) > reach || dy > 3 || dy < -steps - more) return false;
        int i = dx * dirX + dz * dirZ;
        int lane = dx * -dirZ + dz * dirX;
        int y = -Math.max(i, 0);
        return i >= -2 && i <= steps + more && lane >= (wide ? -3 : -1)
                && lane <= (wide ? 4 : 2) && dy >= y && dy <= y + 2;
    }
    public static boolean tread(int dx, int dy, int dz, int dirX, int dirZ, int steps) {
        int reach = steps + 6;
        if (Math.abs(dx) > reach || Math.abs(dz) > reach || dy > 0 || dy < -steps - 3) return false;
        int i = -dy - 1;
        if (i < 0 || i > steps + 1) return false;
        int along = dx * dirX + dz * dirZ;
        int lane = dx * -dirZ + dz * dirX;
        return along == i && lane >= -1 && lane <= 2;
    }
    /** Whether any step center through steps+1 is at/below the point and within two blocks horizontally. */
    public static boolean nearColumn(int dx, int dy, int dz, int dirX, int dirZ, int steps) {
        long along = (long) dx * dirX + (long) dz * dirZ;
        long side = (long) dx * -dirZ + (long) dz * dirX;
        if (side < -2 || side > 2) return false;
        long first = Math.max(Math.max(0L, -(long) dy), along - 2);
        long last = Math.min((long) steps + 1, along + 2);
        return first <= last;
    }
}
