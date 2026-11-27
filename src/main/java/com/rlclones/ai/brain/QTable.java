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
        if (actions < 1 || actions > Integer.SIZE) {
            throw new IllegalArgumentException("actions must be between 1 and 32");
        }
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
        int available = mask & actionMask(e.q.length);
        if (available == actionMask(e.q.length)) {
            // Dense masks are common in replay. A simple contiguous scan is faster here.
            for (float q : e.q) if (q > best) best = q;
            return best == Float.NEGATIVE_INFINITY ? 0f : best;
        }
        for (int bits = available; bits != 0; bits &= bits - 1) {
            int a = Integer.numberOfTrailingZeros(bits);
            if (e.q[a] > best) {
                best = e.q[a];
            }
        }
        return best == Float.NEGATIVE_INFINITY ? 0f : best;
    }

    public static int actionMask(int count) {
        return count == 32 ? -1 : (1 << count) - 1;
    }

    public static int argmax(Entry e, int mask, Random random) {
        return argmax(e.q, mask, random);
    }

    static int argmax(float[] values, int mask, Random random) {
        int best = -1;
        float bestQ = Float.NEGATIVE_INFINITY;
        int ties = 0;
        for (int bits = mask & actionMask(values.length); bits != 0; bits &= bits - 1) {
            int a = Integer.numberOfTrailingZeros(bits);
            float q = values[a];
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
        if (actions != other.actions) {
            throw new IllegalArgumentException("Cannot merge tables with different action counts");
        }
        if (other == this) {
            return;
        }
        for (var it = other.map.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            Int2ObjectMap.Entry<Entry> oe = it.next();
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
                long total = (long) n1 + n2;
                if (total == 0) {
                    dst.q[a] = (float) (((double) dst.q[a] + src.q[a]) * 0.5);
                } else {
                    dst.q[a] = (float) (((double) dst.q[a] * n1 + (double) src.q[a] * n2) / total);
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
            int length = Math.toIntExact(8L + map.size() * (8L + 12L * actions));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(length);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(actions);
            out.writeInt(map.size());
            for (var it = map.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
                Int2ObjectMap.Entry<Entry> e = it.next();
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
            // Validate dimensions before allocating entries or iterating over an untrusted save.
            long entryBytes = 8L + 12L * actions;
            if (actions < 1 || actions > Integer.SIZE || size < 0
                    || 8L + size * entryBytes != data.length) {
                return table;
            }
            for (int i = 0; i < size; i++) {
                int key = in.readInt();
                Entry e = new Entry(expectedActions);
                e.visits = Math.max(0, in.readInt());
                for (int a = 0; a < actions; a++) {
                    float q = in.readFloat();
                    int n = in.readInt();
                    int d = in.readInt();
                    if (a < expectedActions) {
                        e.q[a] = Float.isFinite(q) ? q : 0f;
                        e.n[a] = Math.max(0, n);
                        e.demo[a] = Math.max(0, d);
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
