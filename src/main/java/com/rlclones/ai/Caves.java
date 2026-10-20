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
        return null;
    }

    private static boolean pocket(ServerLevel level, BlockPos c) {
        return level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir() && level.getFluidState(c).isEmpty()
                && !level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty();
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
}
