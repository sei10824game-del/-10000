package com.rlclones.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Predicate;

/** A conservative palette prefilter for ONE read-only block scan, never retained across world changes. */
public final class SectionPresence {
    private final Level level;
    private final Predicate<BlockState> target;
    private final int minX, minY, minZ, width, height, depth;
    private final byte[] presence; // 0 unknown, 1 absent, 2 possibly present
    private int sectionChecks;

    public SectionPresence(Level level, BlockPos min, BlockPos max, Predicate<BlockState> target) {
        this.level = level;
        this.target = target;
        minX = min.getX() >> 4; minY = min.getY() >> 4; minZ = min.getZ() >> 4;
        width = (max.getX() >> 4) - minX + 1;
        height = (max.getY() >> 4) - minY + 1;
        depth = (max.getZ() >> 4) - minZ + 1;
        presence = new byte[width * height * depth];
    }

    public boolean mayContain(BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return false;
        int x = (pos.getX() >> 4) - minX, y = (pos.getY() >> 4) - minY, z = (pos.getZ() >> 4) - minZ;
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth)
            return target.test(level.getBlockState(pos));
        int index = (z * height + y) * width + x;
        if (presence[index] == 0) {
            var chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            var section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
            presence[index] = (byte) (section.maybeHas(target) ? 2 : 1);
            sectionChecks++;
        }
        return presence[index] == 2;
    }

    /** Palette queries performed by this scan (verification/benchmarks). */
    public int sectionChecks() { return sectionChecks; }
}
