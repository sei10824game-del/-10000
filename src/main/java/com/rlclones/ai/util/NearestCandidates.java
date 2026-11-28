package com.rlclones.ai.util;

/** Reusable, bounded nearest-first selection. Equal distances retain insertion order. Not thread-safe. */
public final class NearestCandidates<T> {
    private static final class Entry {
        Object value;
        double distance;
        long sequence;
    }

    private final Entry[] heap;
    private int size;
    private long sequence;
    private boolean sorted;
    private boolean heapified;

    public NearestCandidates(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        heap = new Entry[limit];
        // Allocate entries on demand; a quiet clone need not own a full scan workspace.
    }

    public int size() {
        return size;
    }

    public void offer(T value, double distance) {
        if (sorted) throw new IllegalStateException("clear before offering another batch");
        long arrival = sequence++;
        if (size == heap.length) {
            heapify();
            // Later equal-distance candidates cannot displace earlier ones.
            if (Double.compare(distance, heap[0].distance) >= 0) return;
            set(heap[0], value, distance, arrival);
            siftDown(0, size);
            return;
        }
        // Small scans only append; build a heap once if the scan exceeds the limit.
        Entry entry = heap[size];
        if (entry == null) heap[size] = entry = new Entry();
        set(entry, value, distance, arrival);
        size++;
    }

    private void heapify() {
        if (heapified) return;
        for (int parent = size / 2 - 1; parent >= 0; parent--) siftDown(parent, size);
        heapified = true;
    }

    private static void set(Entry entry, Object value, double distance, long sequence) {
        entry.value = value;
        entry.distance = distance;
        entry.sequence = sequence;
    }

    private static int compare(Entry a, Entry b) {
        int distance = Double.compare(a.distance, b.distance);
        return distance != 0 ? distance : Long.compare(a.sequence, b.sequence);
    }

    private void swap(int a, int b) {
        Entry entry = heap[a];
        heap[a] = heap[b];
        heap[b] = entry;
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

    /** Sort the retained candidates in place, without allocating temporary arrays. */
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
        return (T) heap[index].value;
    }

    /** Release entity references while retaining the fixed-size workspace for the next scan. */
    public void clear() {
        for (int i = 0; i < size; i++) heap[i].value = null;
        size = 0;
        sequence = 0;
        sorted = false;
        heapified = false;
    }
}
