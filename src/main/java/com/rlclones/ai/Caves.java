package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * A cave to go and look in (ores show in its walls): an air pocket a few blocks under the surface that is joined to the open air by a passage of
 * air cells. The place to head for is the deepest cell found of it.
 * ponytail: looked for in the loaded land round the clone, not only where it has looked (a cave mouth is plain to see on a hillside; the walk
 * there is the ordinary navigation, which gives up when stuck); caves cut off by water or by a drop are not walkable and are not told apart.
 */
public final class Caves {
    private static final int SAMPLES = 10;
    private static final int BFS_LIMIT = 400;

    private Caves() {
    }

    @Nullable
    public static Vec3 find(Player p, Random random) {
        ServerLevel level = (ServerLevel) p.level();
        for (int i = 0; i < SAMPLES; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double d = 12 + random.nextDouble() * 24;
            int x = Mth.floor(p.getX() + Math.cos(a) * d);
            int z = Mth.floor(p.getZ() + Math.sin(a) * d);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = top - 3; y > top - 26 && y > level.getMinBuildHeight() + 2; y--) {
                BlockPos c = new BlockPos(x, y, z);
                if (pocket(level, c)) {
                    BlockPos deepest = reachesTheSky(level, c);
                    if (deepest != null) {
                        return Vec3.atBottomCenterOf(deepest);
                    }
                    break; // an enclosed pocket: not this column
                }
            }
        }
        return crater(p, random);
    }

    /**
     * A huge cave open to the sky (a crater, a giant ravine, a collapsed cavern) has no ceiling to find a pocket under: the ground simply falls away.
     * A column whose surface lies eight or more blocks below where the clone stands is the way in; the lowest of the samples is the goal.
     * ponytail: 16 samples 14-60 blocks out; the walk down is the ordinary navigation (slopes) - steep walls are left to the stairs / pit logic.
     */
    @Nullable
    private static Vec3 crater(Player p, Random random) {
        ServerLevel level = (ServerLevel) p.level();
        if (!level.canSeeSky(p.blockPosition().above())) {
            return null;
        }
        int myTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getBlockX(), p.getBlockZ());
        BlockPos best = null;
        for (int i = 0; i < 16; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double d = 14 + random.nextDouble() * 46;
            int x = Mth.floor(p.getX() + Math.cos(a) * d);
            int z = Mth.floor(p.getZ() + Math.sin(a) * d);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (h <= myTop - 8 && level.getFluidState(new BlockPos(x, h - 1, z)).isEmpty() && (best == null || h < best.getY())) {
                best = new BlockPos(x, h, z);
            }
        }
        return best == null ? null : Vec3.atBottomCenterOf(best);
    }

    private static boolean pocket(ServerLevel level, BlockPos c) {
        return level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir() && level.getFluidState(c).isEmpty()
                && !level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty();
    }

    /** Underground: the nearest air cell open to the sky that the cave floor leads to (null when the cave is closed or too big to search). */
    @Nullable
    public static Vec3 exitGoal(Player p) {
        ServerLevel level = (ServerLevel) p.level();
        BlockPos start = p.blockPosition();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < 3000) {
            BlockPos c = queue.poll();
            if (level.canSeeSky(c) && pocket(level, c)) {
                return Vec3.atBottomCenterOf(c);
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = c.relative(d).above(dy);
                    if (level.getBlockState(n).isAir() && level.getBlockState(n.above()).isAir() && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        return null;
    }

    /** The deepest air cell of the cave {@code start} is in if it is joined to the open air (else null). */
    @Nullable
    private static BlockPos reachesTheSky(ServerLevel level, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        BlockPos deepest = start;
        boolean open = false;
        while (!queue.isEmpty() && seen.size() < BFS_LIMIT) {
            BlockPos c = queue.poll();
            if (level.canSeeSky(c)) {
                open = true;
            }
            if (c.getY() < deepest.getY() && pocket(level, c)) {
                deepest = c;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = c.relative(d).above(dy);
                    if (level.getBlockState(n).isAir() && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        return open ? deepest : null;
    }

    /**
     * Underground in a cave: a place deeper in it to walk to (an air cell 8+ blocks off along the cave that is not near the last places walked to), so the
     * walls get looked at. Null when the cave is small or out of new places.
     */
    @Nullable
    public static Vec3 interiorGoal(Player p, Random random, java.util.Collection<Vec3> recent) {
        ServerLevel level = (ServerLevel) p.level();
        BlockPos start = p.blockPosition();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        java.util.List<BlockPos> far = new java.util.ArrayList<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < BFS_LIMIT) {
            BlockPos c = queue.poll();
            if (pocket(level, c) && c.distSqr(start) > 64) {
                boolean fresh = true;
                for (Vec3 r : recent) {
                    if (r.distanceToSqr(Vec3.atCenterOf(c)) < 36) {
                        fresh = false;
                        break;
                    }
                }
                if (fresh) {
                    far.add(c);
                }
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = c.relative(d).above(dy);
                    if (level.getBlockState(n).isAir() && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        if (far.isEmpty()) {
            return null;
        }
        return Vec3.atBottomCenterOf(far.get(random.nextInt(far.size())));
    }
}
