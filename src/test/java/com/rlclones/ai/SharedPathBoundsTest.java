package com.rlclones.ai;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class SharedPathBoundsTest {
    @Test
    void broadPhaseNeverRejectsAnEligibleNode() {
        Random random = new Random(10824);
        for (int fixture = 0; fixture < 200; fixture++) {
            int[][] nodes = new int[1 + random.nextInt(80)][3];
            int[] lo = {Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
            for (int[] n : nodes) for (int axis=0; axis<3; axis++) {
                n[axis] = random.nextInt(80)-40;
                lo[axis] = Math.min(lo[axis],n[axis]);
                hi[axis] = Math.max(hi[axis],n[axis]);
            }
            for (int trial=0; trial<400; trial++) {
                int[] n = nodes[random.nextInt(nodes.length)];
                double x = n[0]+0.5+(random.nextInt(9)-4)*0.25;
                double y = n[1]+(random.nextInt(13)-6)*0.25;
                double z = n[2]+0.5+(random.nextInt(9)-4)*0.25;
                boolean old = false;
                for (int[] p : nodes) {
                    if (Math.abs(p[0]+0.5-x)<1.0 && Math.abs(p[2]+0.5-z)<1.0
                            && Math.abs(p[1]-y)<=1.0) { old=true; break; }
                }
                boolean passes = Motor.pathBoundsMayContain(x,y,z,lo[0],hi[0],lo[1],hi[1],lo[2],hi[2]);
                assertEquals(old, passes && old, "broad phase must never reject an eligible node");
            }
        }
    }
    @Test
    void strictHorizontalAndInclusiveVerticalBoundariesArePreserved() {
        assertTrue(Motor.pathBoundsMayContain(-2.5,11,4.5,-3,-3,10,10,4,4));
        assertFalse(Motor.pathBoundsMayContain(-1.5,10,4.5,-3,-3,10,10,4,4));
        assertFalse(Motor.pathBoundsMayContain(-3.5,10,4.5,-3,-3,10,10,4,4));
        assertFalse(Motor.pathBoundsMayContain(-2.5,11.001,4.5,-3,-3,10,10,4,4));
        assertTrue(Motor.pathBoundsMayContain(-2.5,9,4.5,-3,-3,10,10,4,4));
    }
}
