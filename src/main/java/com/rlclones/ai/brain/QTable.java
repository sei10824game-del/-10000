package com.rlclones.ai.brain;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/** Sparse tabular action-value function: state key -> Q values, visit counts and demonstration counts. */
public final class QTable {
    /** Supplies initial (prior) Q values for a newly visited state. */
    @FunctionalInterface
    public interface Prior {
        void fill(int state, float[] q);

        Prior ZERO = (s, q) -> {
        };
    }

    public static final class Entry {
        public final float[] q;
        public final int[] n;
        public final int[] demo;
        public int visits;

        Entry(int actions) {
            q = new float[actions];
            n = new int[actions];
            demo = new int[actions];
        }
    }

    private final int actions;
    private final Int2ObjectOpenHashMap<Entry> map = new Int2ObjectOpenHashMap<>();

    public QTable(int actions) {
        this.actions = actions;
    }

    public int actions() {
        return actions;
    }

    public int size() {
        return map.size();
    }

    public Entry peek(int state) {
        return map.get(state);
    }

    public Entry get(int state, Prior prior) {
        Entry e = map.get(state);
        if (e == null) {
            e = new Entry(actions);
            prior.fill(state, e.q);
            map.put(state, e);
        }
        return e;
    }

    public Iterable<Int2ObjectMap.Entry<Entry>> entries() {
        return map.int2ObjectEntrySet();
    }

    public static float max(Entry e, int mask) {
        float best = Float.NEGATIVE_INFINITY;
        for (int a = 0; a < e.q.length; a++) {
            if ((mask & (1 << a)) != 0 && e.q[a] > best) {
                best = e.q[a];
            }
        }
        return best == Float.NEGATIVE_INFINITY ? 0f : best;
    }

    public static int argmax(Entry e, int mask, Random random) {
        int best = -1;
        float bestQ = Float.NEGATIVE_INFINITY;
        int ties = 0;
        for (int a = 0; a < e.q.length; a++) {
            if ((mask & (1 << a)) == 0) {
                continue;
            }
            float q = e.q[a];
            if (q > bestQ + 1e-6f) {
                bestQ = q;
                best = a;
                ties = 1;
            } else if (Math.abs(q - bestQ) <= 1e-6f) {
                ties++;
                if (random.nextInt(ties) == 0) {
                    best = a;
                }
            }
        }
        return best;
    }

    /** Merge another table into this one, weighting every Q value by how often it was updated. */
    public void mergeFrom(QTable other) {
        for (Int2ObjectMap.Entry<Entry> oe : other.map.int2ObjectEntrySet()) {
            Entry src = oe.getValue();
            Entry dst = map.get(oe.getIntKey());
            if (dst == null) {
                dst = new Entry(actions);
                System.arraycopy(src.q, 0, dst.q, 0, actions);
                System.arraycopy(src.n, 0, dst.n, 0, actions);
                System.arraycopy(src.demo, 0, dst.demo, 0, actions);
                dst.visits = src.visits;
                map.put(oe.getIntKey(), dst);
                continue;
            }
            for (int a = 0; a < actions; a++) {
                int n1 = dst.n[a];
                int n2 = src.n[a];
                if (n1 + n2 == 0) {
                    dst.q[a] = (dst.q[a] + src.q[a]) * 0.5f;
                } else {
                    dst.q[a] = (dst.q[a] * n1 + src.q[a] * n2) / (float) (n1 + n2);
                }
                dst.n[a] = saturatingAdd(n1, n2);
                dst.demo[a] = saturatingAdd(dst.demo[a], src.demo[a]);
            }
            dst.visits = saturatingAdd(dst.visits, src.visits);
        }
    }

    private static int saturatingAdd(int a, int b) {
        long s = (long) a + b;
        return s > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s;
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(actions);
            out.writeInt(map.size());
            for (Int2ObjectMap.Entry<Entry> e : map.int2ObjectEntrySet()) {
                Entry v = e.getValue();
                out.writeInt(e.getIntKey());
                out.writeInt(v.visits);
                for (int a = 0; a < actions; a++) {
                    out.writeFloat(v.q[a]);
                    out.writeInt(v.n[a]);
                    out.writeInt(v.demo[a]);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static QTable fromBytes(byte[] data, int expectedActions) {
        QTable table = new QTable(expectedActions);
        if (data == null || data.length < 8) {
            return table;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int actions = in.readInt();
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                int key = in.readInt();
                Entry e = new Entry(expectedActions);
                e.visits = in.readInt();
                for (int a = 0; a < actions; a++) {
                    float q = in.readFloat();
                    int n = in.readInt();
                    int d = in.readInt();
                    if (a < expectedActions) {
                        e.q[a] = q;
                        e.n[a] = n;
                        e.demo[a] = d;
                    }
                }
                table.map.put(key, e);
            }
        } catch (IOException e) {
            return new QTable(expectedActions);
        }
        return table;
    }
}
