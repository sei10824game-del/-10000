package com.rlclones.clone;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
/** Previous implementation, retained only as a correctness and benchmark reference. */
public final class StairGeometryReference {
    public record Fixture(BlockPos top, net.minecraft.core.Direction dir, int depth, boolean finished, BlockPos end) {
        public Fixture(BlockPos top, net.minecraft.core.Direction dir, int depth, boolean finished) {
            this(top, dir, depth, finished, top.relative(dir, depth).below(depth));
        }
        public int steps() { return depth; }
    }
    public static boolean space(java.util.List<Fixture> staircases, BlockPos p, boolean wide) {
        for (Fixture s : staircases) {
            int more = wide && !s.finished ? 40 : 2; // (a staircase still being dug runs on below its recorded end)
            int reach = s.steps() + more + 6;
            if (Math.abs(p.getX() - s.top.getX()) > reach || Math.abs(p.getZ() - s.top.getZ()) > reach || p.getY() > s.top.getY() + 3 || p.getY() < s.end().getY() - more) {
                continue;
            }
            for (int i = -2; i <= s.steps() + more; i++) {
                BlockPos base = s.top.relative(s.dir, i).below(Math.max(i, 0));
                for (int o = wide ? -3 : -1; o <= (wide ? 4 : 2); o++) { // (the lanes dug beside the steps, and the wall beyond them: a niche there is cut away at the next widening)
                    BlockPos lane = o >= 0 ? base.relative(s.dir.getClockWise(), o) : base.relative(s.dir.getCounterClockWise(), -o);
                    if (lane.getX() == p.getX() && lane.getZ() == p.getZ() && p.getY() >= lane.getY() && p.getY() <= lane.getY() + 2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean tread(java.util.List<Fixture> staircases, BlockPos p) {
        for (Fixture s : staircases) {
            int reach = s.steps() + 3;
            if (Math.abs(p.getX() - s.top.getX()) > reach + 3 || Math.abs(p.getZ() - s.top.getZ()) > reach + 3 || p.getY() > s.top.getY() || p.getY() < s.end().getY() - 3) {
                continue;
            }
            for (int i = 0; i <= s.steps() + 1; i++) {
                BlockPos tread = s.top.relative(s.dir, i).below(i + 1);
                if (tread.getY() != p.getY()) {
                    continue;
                }
                for (int o = -1; o <= 2; o++) {
                    if (p.equals(o >= 0 ? tread.relative(s.dir.getClockWise(), o) : tread.relative(s.dir.getCounterClockWise(), -o))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}
