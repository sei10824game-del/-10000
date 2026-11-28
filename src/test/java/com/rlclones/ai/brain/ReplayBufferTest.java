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
    void growthPreservesPhysicalSamplingOrderAndSerializedChronology() throws Exception {
        for (int capacity : new int[]{1,3,16,17,31,32,33,100,4096}) {
            ReplayBuffer b = new ReplayBuffer(capacity);
            int[] physical = new int[capacity];
            int next=0,size=0;
            var field=ReplayBuffer.class.getDeclaredField("items");
            field.setAccessible(true);
            assertEquals(Math.min(16,capacity),((Object[])field.get(b)).length);
            Random rng = new Random(10824);
            for (int i=0;i<capacity*2+3;i++) {
                add(b,i);
                physical[next]=i;next=(next+1)%capacity;size=Math.min(size+1,capacity);
                assertEquals(size,b.size());
                for(int k=0;k<3;k++) {
                    final int index=rng.nextInt(size);
                    assertEquals(physical[index],b.sample(new Random(){
                        @Override public int nextInt(int bound) {return index;}
                    }).s);
                }
                if(i==0 || i==15 || i==16 || i==capacity-1 || i==capacity || i==capacity*2+2) {
                    ReplayBuffer copy=new ReplayBuffer(capacity);
                    copy.readFrom(b.toBytes());
                    assertArrayEquals(b.toBytes(),copy.toBytes());
                    for(int j=0;j<size;j++){
                        final int index=j;
                        assertEquals(physical[(next-size+capacity+j)%capacity],copy.sample(new Random(){
                            @Override public int nextInt(int bound) {return index;}
                        }).s);
                    }
                }
            }
            assertEquals(capacity,((Object[])field.get(b)).length);
        }
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
