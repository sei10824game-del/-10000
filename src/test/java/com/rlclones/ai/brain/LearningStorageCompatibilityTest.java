package com.rlclones.ai.brain;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Compare the optimized storage with the old byte format and fixed-size replay ring. */
class LearningStorageCompatibilityTest {
    @Test
    void qTableBytesMatchOriginalWriter() throws IOException {
        for (int actions : new int[]{1, 2, 16, 32}) {
            QTable table = new QTable(actions);
            assertArrayEquals(oldQTableBytes(table), table.toBytes());
            Random random = new Random(10824);
            for (int state = -75; state < 75; state++) {
                QTable.Entry entry = table.get(state, QTable.Prior.ZERO);
                entry.visits = random.nextInt(10000);
                for (int a = 0; a < actions; a++) {
                    entry.q[a] = Float.intBitsToFloat(random.nextInt());
                    entry.n[a] = random.nextInt(1000);
                    entry.demo[a] = random.nextInt(1000);
                }
                entry.q[0] = Float.intBitsToFloat(0x7fc12345);
                if (actions > 1) entry.q[1] = -0f;
            }
            assertArrayEquals(oldQTableBytes(table), table.toBytes());
        }
    }

    private static byte[] oldQTableBytes(QTable table) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(table.actions());
            out.writeInt(table.size());
            for (var e : table.entries()) {
                out.writeInt(e.getIntKey());
                QTable.Entry v = e.getValue();
                out.writeInt(v.visits);
                for (int a = 0; a < table.actions(); a++) {
                    out.writeFloat(v.q[a]);
                    out.writeInt(v.n[a]);
                    out.writeInt(v.demo[a]);
                }
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void growingReplayMatchesFixedRingAcrossGrowthAndWrap() throws IOException {
        for (int capacity : new int[]{1, 3, 16, 17, 33, 128, 4096}) {
            ReplayBuffer buffer = new ReplayBuffer(capacity);
            int[] reference = new int[capacity];
            int size = 0;
            int next = 0;
            Random actualRandom = new Random(19);
            Random expectedRandom = new Random(19);
            assertNull(buffer.sample(actualRandom));
            assertArrayEquals(oldReplayBytes(reference, size, next), buffer.toBytes());
            for (int state = 0; state < capacity * 2 + 3; state++) {
                buffer.add("enemy", state, 1, 2f, state + 1, false, 0.9f, 3, 1f);
                reference[next] = state;
                next = (next + 1) % capacity;
                size = Math.min(size + 1, capacity);
                assertEquals(size, buffer.size());
                for (int j = 0; j < 4; j++) {
                    assertEquals(reference[expectedRandom.nextInt(size)], buffer.sample(actualRandom).s);
                }
                if (state < 40 || state % 127 == 0 || state == capacity - 1 || state == capacity * 2 + 2) {
                    assertArrayEquals(oldReplayBytes(reference, size, next), buffer.toBytes());
                }
            }
            ReplayBuffer restored = new ReplayBuffer(capacity);
            restored.readFrom(buffer.toBytes());
            assertArrayEquals(buffer.toBytes(), restored.toBytes());
        }
    }

    private static byte[] oldReplayBytes(int[] reference, int size, int next) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                int state = reference[(next - size + reference.length + i) % reference.length];
                out.writeUTF("enemy");
                out.writeInt(state);
                out.writeByte(1);
                out.writeFloat(2f);
                out.writeInt(state + 1);
                out.writeBoolean(false);
                out.writeFloat(0.9f);
                out.writeInt(3);
                out.writeFloat(1f);
            }
        }
        return bytes.toByteArray();
    }
}
