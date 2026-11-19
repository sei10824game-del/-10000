package com.rlclones.ai.brain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TrialMemoryTest {
    @Test void pagedDetailsExposeActualItemsAndBoundOutput(){var m=new TrialMemory();for(int i=0;i<12;i++)m.record("food|test:item"+i,1,false);m.record("food|test:bad",-1,true);var page=m.details(1);assertEquals(9,page.size());assertTrue(page.get(1).contains("food|test:bad"));assertTrue(page.get(1).contains("観測=1"));assertEquals(6,m.details(2).size());}

    @Test void sharedHarmfulEvidenceTakesPriorityOverSafeEntriesAtCapacity(){var a=new TrialMemory();for(int i=0;i<128;i++)a.record("gear|test:"+i,1,false);var b=new TrialMemory();b.record("food|test:bad",-1,true);a.merge(b);assertEquals(128,a.size());assertTrue(a.harmful("food|test:bad"));}

    @Test void capacityStopsNewTrialsAndKeepsHarmfulEvidence(){var m=new TrialMemory();m.record("food|test:bad",-1,true);for(int i=1;i<128;i++)m.record("gear|test:"+i,1,false);assertEquals(128,m.size());assertFalse(m.room("food|test:new"));m.record("food|test:new",1,false);assertFalse(m.known("food|test:new"));assertTrue(m.harmful("food|test:bad"));}
    @Test void observationsPersistAndBadExperienceIsNotErasedBySafeSamples(){var m=new TrialMemory();m.record("food|test:bad",-1,true);m.record("food|test:bad",1,false);var copy=TrialMemory.load(m.save());assertTrue(copy.harmful("food|test:bad"));copy.merge(m);assertEquals(1,copy.size());copy.merge(copy);assertEquals(1,copy.size());}
    @Test void invalidRewardsCannotAllocateEntries(){var m=new TrialMemory();m.record("bad",Float.NaN,true);m.record("",1,true);assertEquals(0,m.size());}
}
