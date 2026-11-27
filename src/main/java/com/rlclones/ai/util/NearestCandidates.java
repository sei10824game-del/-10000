package com.rlclones.ai.util;

/** Reusable, bounded nearest-first selection. Equal distances retain insertion order. Not thread-safe. */
public final class NearestCandidates<T> {
    private final Object[] values;
    private final double[] distances;
    private final long[] sequences;
    private final int[] heap;
    private int size;
    private long sequence;
    private boolean sorted;
    private boolean heapified;

    public NearestCandidates(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        values = new Object[limit];
        distances = new double[limit];
        sequences = new long[limit];
        heap = new int[limit];
    }

    public int size() { return size; }

    public void offer(T value, double distance) {
        if (sorted) throw new IllegalStateException("clear before offering another batch");
        long arrival = sequence++;
        if (size == heap.length) {
            heapify();
            int slot = heap[0];
            if (Double.compare(distance, distances[slot]) >= 0) return;
            set(slot, value, distance, arrival);
            siftDown(0, size);
            return;
        }
        heap[size] = size;
        set(size++, value, distance, arrival);
    }

    private void heapify() {
        if (heapified) return;
        for (int parent = size / 2 - 1; parent >= 0; parent--) siftDown(parent, size);
        heapified = true;
    }

    private void set(int slot, Object value, double distance, long arrival) {
        values[slot] = value;
        distances[slot] = distance;
        sequences[slot] = arrival;
    }

    private int compare(int a, int b) {
        int distance = Double.compare(distances[a], distances[b]);
        return distance != 0 ? distance : Long.compare(sequences[a], sequences[b]);
    }

    private void swap(int a, int b) {
        int slot = heap[a];
        heap[a] = heap[b];
        heap[b] = slot;
    }

    private void siftDown(int parent, int length) {
        while (parent * 2 + 1 < length) {
            int child = parent * 2 + 1;
            if (child + 1 < length && compare(heap[child], heap[child + 1]) < 0) child++;
            if (compare(heap[parent], heap[child]) >= 0) break;
            swap(parent, child);
            parent = child;
        }
    }

    public void sort() {
        if (sorted) return;
        heapify();
        for (int end = size - 1; end > 0; end--) {
            swap(0, end);
            siftDown(0, end);
        }
        sorted = true;
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        if (!sorted) throw new IllegalStateException("sort before reading candidates");
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return (T) values[heap[index]];
    }

    public void clear() {
        java.util.Arrays.fill(values, 0, size, null);
        size = 0;
        sequence = 0;
        sorted = false;
        heapified = false;
    }
}
