package com.rlclones.ai.brain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

/** Fixed-size ring buffer of transitions used for experience replay (Dyna-style re-learning). */
public final class ReplayBuffer {
    public static final class Transition {
        public String table;
        public int s;
        public int a;
        public float r;
        public int s2;
        public boolean terminal;
        public float gamma;
        public int nextMask;
        public float weight;
    }

    private static final Transition[] EMPTY = new Transition[0];
    private final int capacity;
    private Transition[] items = EMPTY;
    private int next;
    private int size;

    public ReplayBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public int size() {
        return size;
    }

    public void add(String table, int s, int a, float r, int s2, boolean terminal, float gamma, int nextMask, float weight) {
        // Before the first wrap, next is the number of stored transitions.
        // Grow only on demand; after reaching capacity the old ring layout is unchanged.
        if (next == items.length) {
            int length = (int) Math.min(capacity, Math.max(16L, 2L * items.length));
            items = Arrays.copyOf(items, length);
        }
        Transition t = items[next];
        if (t == null) {
            t = new Transition();
            items[next] = t;
        }
        t.table = table;
        t.s = s;
        t.a = a;
        t.r = r;
        t.s2 = s2;
        t.terminal = terminal;
        t.gamma = gamma;
        t.nextMask = nextMask;
        t.weight = weight;
        if (++next == capacity) next = 0;
        if (size < capacity) size++;
    }

    public Transition sample(Random random) {
        if (size == 0) {
            return null;
        }
        return items[random.nextInt(size)];
    }

    public void addAll(ReplayBuffer other) {
        if (other == this) {
            return;
        }
        for (int i = 0; i < other.size; i++) {
            Transition t = other.oldest(i);
            add(t.table, t.s, t.a, t.r, t.s2, t.terminal, t.gamma, t.nextMask, t.weight);
        }
    }

    /** Iterate in insertion order, including after the ring wraps. */
    private Transition oldest(int offset) {
        int start = next - size;
        if (start < 0) start += capacity;
        int untilEnd = capacity - start;
        return items[offset < untilEnd ? start + offset : offset - untilEnd];
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                Transition t = oldest(i);
                out.writeUTF(t.table);
                out.writeInt(t.s);
                out.writeByte(t.a);
                out.writeFloat(t.r);
                out.writeInt(t.s2);
                out.writeBoolean(t.terminal);
                out.writeFloat(t.gamma);
                out.writeInt(t.nextMask);
                out.writeFloat(t.weight);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void readFrom(byte[] data) {
        if (data == null || data.length < 4) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int n = in.readInt();
            // Each record has at least a two-byte UTF length and 26 bytes of fields.
            if (n < 0 || n > (data.length - 4) / 28) {
                return;
            }
            for (int i = 0; i < n; i++) {
                String table = in.readUTF();
                int s = in.readInt();
                int a = in.readUnsignedByte();
                float r = in.readFloat();
                int s2 = in.readInt();
                boolean terminal = in.readBoolean();
                float gamma = in.readFloat();
                int mask = in.readInt();
                float weight = in.readFloat();
                if (!table.isEmpty() && Float.isFinite(r) && Float.isFinite(gamma)
                        && gamma >= 0f && gamma <= 1f && Float.isFinite(weight) && weight > 0f) {
                    add(table, s, a, r, s2, terminal, gamma, mask, weight);
                }
            }
        } catch (IOException ignored) {
            // A truncated buffer only loses old memories.
        }
    }
}
