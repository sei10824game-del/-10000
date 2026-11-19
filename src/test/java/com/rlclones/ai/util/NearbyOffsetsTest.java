package com.rlclones.ai.util;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NearbyOffsetsTest {
    @Test void coversOriginalBoxInStableDistanceOrder() {
        var expected = new ArrayList<NearbyOffsets.Offset>();
        for (int z=-16;z<=16;z++) for (int y=-4;y<=4;y++) for(int x=-16;x<=16;x++)
            expected.add(new NearbyOffsets.Offset(x,y,z));
        expected.sort(Comparator.comparingInt(NearbyOffsets.Offset::distanceSquared));
        var actual = NearbyOffsets.ordered(16,4);
        assertEquals(expected, actual);
        assertEquals(9801, new HashSet<>(actual).size());
    }
    @Test void firstMatchIsSameAsOriginalNearestScanIncludingTies() {
        var ordered=NearbyOffsets.ordered(16,4);
        Random random=new Random(10824);
        for(int trial=0;trial<100;trial++) {
            var fires=new HashSet<NearbyOffsets.Offset>();
            for(int i=0;i<trial;i++) fires.add(ordered.get(random.nextInt(ordered.size())));
            NearbyOffsets.Offset old=null; int distance=Integer.MAX_VALUE;
            for(int z=-16;z<=16;z++) for(int y=-4;y<=4;y++) for(int x=-16;x<=16;x++) {
                var p=new NearbyOffsets.Offset(x,y,z);
                if(fires.contains(p)&&p.distanceSquared()<distance) {old=p;distance=p.distanceSquared();}
            }
            assertEquals(old, ordered.stream().filter(fires::contains).findFirst().orElse(null));
        }
    }
    @Test void nearbyMatchAvoidsMostBlockReadsWithoutReducingRange() {
        var offsets=NearbyOffsets.ordered(16,4);
        int reads=offsets.indexOf(new NearbyOffsets.Offset(2,0,0))+1;
        assertTrue(reads<40,"nearby campfire should not read the entire 9801-block box");
        assertTrue(offsets.contains(new NearbyOffsets.Offset(16,4,16)));
    }
}
