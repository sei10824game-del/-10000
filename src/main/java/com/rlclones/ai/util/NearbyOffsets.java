package com.rlclones.ai.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Distance order with the same tie order as BlockPos.betweenClosed (x, then y, then z). */
public final class NearbyOffsets {
    private NearbyOffsets() {}
    public record Offset(int x, int y, int z) {
        public int distanceSquared() { return x * x + y * y + z * z; }
    }
    public static List<Offset> ordered(int horizontal, int vertical) {
        List<Offset> out = new ArrayList<>();
        for (int z = -horizontal; z <= horizontal; z++)
            for (int y = -vertical; y <= vertical; y++)
                for (int x = -horizontal; x <= horizontal; x++) out.add(new Offset(x, y, z));
        out.sort(Comparator.comparingInt(Offset::distanceSquared));
        return List.copyOf(out);
    }
}
