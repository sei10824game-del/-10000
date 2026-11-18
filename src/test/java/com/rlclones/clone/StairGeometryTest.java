package com.rlclones.clone;

import com.rlclones.ai.util.StairFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StairGeometryTest {
    static boolean space(List<StairGeometryReference.Fixture> stairs, BlockPos p, boolean wide) {
        for (var s : stairs) if (StairFootprint.space(p.getX()-s.top().getX(), p.getY()-s.top().getY(), p.getZ()-s.top().getZ(),
                s.dir().getStepX(), s.dir().getStepZ(), s.steps(), s.finished(), wide)) return true;
        return false;
    }
    static boolean tread(List<StairGeometryReference.Fixture> stairs, BlockPos p) {
        for (var s : stairs) if (StairFootprint.tread(p.getX()-s.top().getX(), p.getY()-s.top().getY(), p.getZ()-s.top().getZ(),
                s.dir().getStepX(), s.dir().getStepZ(), s.steps())) return true;
        return false;
    }
    @Test void matchesPreviousAlgorithmAtEveryBoundaryAndHeading() {
        for (Direction dir : Direction.Plane.HORIZONTAL) for (int steps : new int[]{0, 1, 16, 150})
            for (boolean finished : new boolean[]{false, true}) {
                BlockPos top = new BlockPos(-37, 100, 89);
                var stairs = List.of(new StairGeometryReference.Fixture(top, dir, steps, finished));
                for (int i : new int[]{-3,-2,-1,0,1,steps-1,steps,steps+1,steps+2,steps+40,steps+41})
                    for (int lane=-5; lane<=6; lane++) for (int dy=-2; dy<=4; dy++) {
                        BlockPos p = top.relative(dir,i).below(Math.max(0,i)).relative(dir.getClockWise(),lane).above(dy);
                        assertEquals(StairGeometryReference.tread(stairs,p), tread(stairs,p));
                        for (boolean wide : new boolean[]{false,true}) assertEquals(StairGeometryReference.space(stairs,p,wide),space(stairs,p,wide));
                    }
            }
    }
    @Test void matchesOverlappingStaircases() {
        Random random = new Random(10824);
        List<StairGeometryReference.Fixture> stairs = new ArrayList<>();
        for (int n=0;n<24;n++) stairs.add(new StairGeometryReference.Fixture(new BlockPos(random.nextInt(80)-40,80,random.nextInt(80)-40),
                Direction.from2DDataValue(n%4),random.nextInt(160),n%2==0));
        for (int n=0;n<6000;n++) {
            BlockPos p = new BlockPos(random.nextInt(400)-200,random.nextInt(220)-120,random.nextInt(400)-200);
            assertEquals(StairGeometryReference.tread(stairs,p),tread(stairs,p));
            for (boolean wide : new boolean[]{false,true}) assertEquals(StairGeometryReference.space(stairs,p,wide),space(stairs,p,wide));
        }
    }
    @Test void reflectsExtensionAndCompletionWithoutAStaleCache() {
        assertTrue(StairFootprint.space(20,-20,0,1,0,0,false,true));
        assertFalse(StairFootprint.space(20,-20,0,1,0,0,true,true));
        assertTrue(StairFootprint.space(20,-20,0,1,0,20,true,false));
        assertTrue(StairFootprint.tread(20,-21,0,1,0,20));
    }
}
