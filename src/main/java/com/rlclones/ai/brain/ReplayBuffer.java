package com.rlclones.ai.brain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

    private final Transition[] items;
    private int next;
    private int size;

    public ReplayBuffer(int capacity) {
        items = new Transition[capacity];
    }

    public int size() {
        return size;
    }

    public void add(String table, int s, int a, float r, int s2, boolean terminal, float gamma, int nextMask, float weight) {
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
        next = (next + 1) % items.length;
        size = Math.min(size + 1, items.length);
    }

    public Transition sample(Random random) {
        if (size == 0) {
            return null;
        }
        return items[random.nextInt(size)];
    }

    public void addAll(ReplayBuffer other) {
        for (int i = 0; i < other.size; i++) {
            Transition t = other.items[i];
            add(t.table, t.s, t.a, t.r, t.s2, t.terminal, t.gamma, t.nextMask, t.weight);
        }
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                Transition t = items[i];
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
            for (int i = 0; i < n; i++) {
                String table = in.readUTF();
                int s = in.readInt();
                int a = in.readByte();
                float r = in.readFloat();
                int s2 = in.readInt();
                boolean terminal = in.readBoolean();
                float gamma = in.readFloat();
                int mask = in.readInt();
                float weight = in.readFloat();
                add(table, s, a, r, s2, terminal, gamma, mask, weight);
            }
        } catch (IOException ignored) {
            // A truncated buffer only loses old memories.
        }
    }
}
