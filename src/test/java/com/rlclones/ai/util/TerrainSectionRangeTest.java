package com.rlclones.ai.util;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TerrainSectionRangeTest {
    @Test void binarySliceMatchesFullScanForAllHeightRanges() {
        Random random=new Random(10824);
        for(int fixture=0;fixture<200;fixture++) {
            BitSet bits=new BitSet(4096);
            for(int i=0;i<4096;i++)if(random.nextInt(1+fixture%80)==0)bits.set(i);
            int[] cells=bits.stream().toArray();
            for(int lo=0;lo<16;lo++)for(int hi=lo;hi<16;hi++) {
                final int min=lo,max=hi;
                int[] expected=Arrays.stream(cells).filter(p->(p>>8)>=min&&(p>>8)<=max).toArray();
                int[] actual=Arrays.copyOfRange(cells,TerrainSections.lowerBound(cells,lo<<8),TerrainSections.lowerBound(cells,(hi+1)<<8));
                assertArrayEquals(expected,actual);
            }
        }
        assertEquals(0,TerrainSections.lowerBound(new int[0],4096));
    }
}
