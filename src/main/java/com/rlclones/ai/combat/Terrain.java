package com.rlclones.ai.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * What the ground gives in a fight: walls at the sides (a corridor or alcove lets few enemies reach us), height over the
 * enemy, and hazards (lava, fire, drops). Nothing here says what to do; the Q-table learns when {@code POSITION} pays.
 */
public final class Terrain {
    public static final int OPEN = 0, COVERED = 1, HAZARD = 2;
    private static final int RANGE = 5;

    private Terrain() {
    }

    private static boolean solid(BlockGetter l, BlockPos p) {
        return !l.getBlockState(p).getCollisionShape(l, p).isEmpty();
    }

    /** Cardinal neighbours that are walls at body height (closed doors count: they are blocks). */
    public static int walls(BlockGetter l, BlockPos feet) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (solid(l, feet.relative(d)) || solid(l, feet.above().relative(d))) {
                n++;
            }
        }
        return n;
    }

    private static boolean dangerous(BlockGetter l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getFluidState().is(FluidTags.LAVA) || s.is(net.minecraft.tags.BlockTags.FIRE) || s.is(net.minecraft.world.level.block.Blocks.CACTUS)
                || s.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
    }

    /** Lava / fire in or under the cell or its neighbours, or a fall of 3+ right beside it. */
    public static boolean hazard(BlockGetter l, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d);
            if (dangerous(l, n) || dangerous(l, n.below())) {
                return true;
            }
            if (!solid(l, n.below()) && !solid(l, n.below(2)) && !solid(l, n.below(3)) && !solid(l, n)) {
                return true;
            }
        }
        return dangerous(l, feet) || dangerous(l, feet.below());
    }

    public static int state(BlockGetter l, BlockPos feet) {
        return hazard(l, feet) ? HAZARD : walls(l, feet) >= 2 ? COVERED : OPEN;
    }

    private static double score(BlockGetter l, BlockPos p, Entity enemy) {
        double highGround = Math.max(0, Math.min(2, p.getY() - enemy.getY()));
        double near = Math.sqrt(p.distSqr(enemy.blockPosition())) < 1.5 ? 2 : 0;
        return Math.min(3, walls(l, p)) + highGround - near;
    }

    /** The best standing cell within a few blocks (walls round it, height over the enemy, no hazard); null when here is as good. */
    @Nullable
    public static BlockPos bestSpot(Player self, Entity enemy) {
        BlockGetter l = self.level();
        BlockPos here = self.blockPosition();
        double hereScore = hazard(l, here) ? -5 : score(l, here, enemy);
        BlockPos best = null;
        double bestScore = hereScore + 0.75;
        for (int dx = -RANGE; dx <= RANGE; dx++) {
            for (int dz = -RANGE; dz <= RANGE; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = here.offset(dx, dy, dz);
                    if (!solid(l, p.below()) || solid(l, p) || solid(l, p.above()) || l.getFluidState(p).is(FluidTags.WATER) || hazard(l, p)) {
                        continue;
                    }
                    double s = score(l, p, enemy) - 0.2 * Math.sqrt(p.distSqr(here));
                    if (s > bestScore) {
                        bestScore = s;
                        best = p;
                    }
                }
            }
        }
        return best;
    }
}
