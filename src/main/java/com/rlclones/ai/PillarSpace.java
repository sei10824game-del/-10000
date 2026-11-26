package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Swept space for the actual body, including a jump's overshoot above the final step. */
public final class PillarSpace {
    private PillarSpace(){}
    public static boolean clear(Player self,double rise){
        if(!Double.isFinite(rise)||rise<0)return false;
        AABB box=self.getBoundingBox().deflate(.001).expandTowards(0,rise,0);
        if(self.level().getBlockCollisions(self,box).iterator().hasNext())return false;
        for(BlockPos p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
            if(!self.level().hasChunkAt(p)||!self.level().getFluidState(p).isEmpty())return false;
        return true;
    }
}
