package com.rlclones.ai.observe;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentEventsTest {
    @BeforeEach void clear() { AgentEvents.clear(); }
    private void record(UUID id, long tick) { AgentEvents.record(id, tick, AgentEvents.Kind.ATTACK, 1, 9, 0); }
    @Test void sharesExactReadOnlySnapshots() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 40; i++) record(id, i);
        for (long from : new long[]{-1, 0, 10, 20}) {
            var expected = AgentEvents.between(id, from, 30);
            var snapshot = AgentEvents.sharedBetween(id, from, 30);
            assertEquals(expected, snapshot);
            assertSame(snapshot, AgentEvents.sharedBetween(id, from, 30));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new AgentEvents.Event(1, AgentEvents.Kind.EAT, 1, 0, 0)));
        }
    }
    @Test void seesNewEventsRecordedInTheSameTick() {
        UUID id = UUID.randomUUID(); record(id, 10);
        var first = AgentEvents.sharedBetween(id, 9, 10);
        record(id, 10);
        var second = AgentEvents.sharedBetween(id, 9, 10);
        assertEquals(1, first.size()); assertEquals(2, second.size());
        assertEquals(AgentEvents.between(id, 9, 10), second);
    }
    @Test void pruningAndOverflowNeverLeaveStaleEvents() {
        UUID id = UUID.randomUUID(); record(id, 0); record(id, 1);
        var old = AgentEvents.sharedBetween(id, -1, 1);
        AgentEvents.prune(201);
        assertEquals(1, AgentEvents.sharedBetween(id, -1, 1).size());
        assertEquals(2, old.size());
        for (int i = 0; i < 300; i++) record(id, 201);
        assertEquals(256, AgentEvents.sharedBetween(id, -1, 201).size());
        AgentEvents.prune(402);
        assertTrue(AgentEvents.sharedBetween(id, -1, 500).isEmpty());
    }
    @Test void rangeEvictionAndClearPreserveResults() {
        UUID id = UUID.randomUUID(); for (int i = 0; i < 100; i++) record(id, i);
        for (int i = 0; i < 30; i++) assertEquals(AgentEvents.between(id, i, 70), AgentEvents.sharedBetween(id, i, 70));
        AgentEvents.clear(); assertTrue(AgentEvents.sharedBetween(id, 0, 100).isEmpty());
        record(id, 90); assertEquals(1, AgentEvents.sharedBetween(id, 0, 100).size());
    }
}
