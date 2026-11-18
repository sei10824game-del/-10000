package com.rlclones.ai.brain;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ReplayBufferTest {
    private static void add(ReplayBuffer buffer, int state) {
        buffer.add("enemy", state, 1, 2f, state + 1, false, 0.9f, 3, 1f);
    }

    private static void assertStates(ReplayBuffer buffer, int... states) {
        assertEquals(states.length, buffer.size());
        for (int i = 0; i < states.length; i++) {
            final int index = i;
            assertEquals(states[i], buffer.sample(new Random() {
                @Override public int nextInt(int bound) { return index; }
            }).s);
        }
    }

    @Test
    void savingWrappedRingRetainsNewestExperiencesAfterReload() {
        ReplayBuffer source = new ReplayBuffer(3);
        for (int i = 0; i < 5; i++) add(source, i);
        ReplayBuffer loaded = new ReplayBuffer(3);
        loaded.readFrom(source.toBytes());
        assertStates(loaded, 2, 3, 4);
        add(loaded, 5);
        ReplayBuffer copy = new ReplayBuffer(3);
        copy.readFrom(loaded.toBytes());
        assertStates(copy, 3, 4, 5);
    }

    @Test
    void mergingSmallerRingKeepsNewestTransitions() {
        ReplayBuffer source = new ReplayBuffer(3);
        for (int i = 0; i < 5; i++) add(source, i);
        ReplayBuffer target = new ReplayBuffer(2);
        target.addAll(source);
        ReplayBuffer loaded = new ReplayBuffer(2);
        loaded.readFrom(target.toBytes());
        assertStates(loaded, 3, 4);
        byte[] before = source.toBytes();
        source.addAll(source);
        assertArrayEquals(before, source.toBytes());
    }

    @Test
    void invalidRewardIsNotReplayed() {
        ReplayBuffer source = new ReplayBuffer(2);
        source.add("enemy", 1, 0, Float.NaN, 2, false, 0.9f, 1, 1f);
        add(source, 2);
        ReplayBuffer loaded = new ReplayBuffer(2);
        loaded.readFrom(source.toBytes());
        assertStates(loaded, 2);
    }

    @Test
    void emptyAndMalformedBuffersAreSafe() {
        ReplayBuffer buffer = new ReplayBuffer(1);
        assertNull(buffer.sample(new Random(1)));
        buffer.readFrom(new byte[]{127, -1, -1, -1});
        assertEquals(0, buffer.size());
        assertThrows(IllegalArgumentException.class, () -> new ReplayBuffer(0));
    }
}
