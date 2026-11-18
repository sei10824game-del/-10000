package com.rlclones.ai.util;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Standalone comparison of the former perception sort with bounded selection; not an FPS benchmark. */
public final class PerceptionSelectionBenchmark {
    private record Candidate(int id, double distance) {}
    private static volatile long sink;

    private static long previous(Candidate[] input) {
        List<Candidate> candidates = new ArrayList<>();
        for (Candidate candidate : input) candidates.add(candidate);
        if (candidates.size() > 1) {
            double[] keys = new double[candidates.size()];
            Integer[] order = new Integer[keys.length];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = candidates.get(i).distance;
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Double.compare(keys[a], keys[b]));
            List<Candidate> sorted = new ArrayList<>(keys.length);
            for (int i : order) sorted.add(candidates.get(i));
            candidates = sorted;
        }
        long result = 0;
        for (int i = 0; i < Math.min(64, candidates.size()); i++) result = result * 31 + candidates.get(i).id;
        return result;
    }

    private static long optimized(Candidate[] input, NearestCandidates<Candidate> nearest) {
        for (Candidate candidate : input) nearest.offer(candidate, candidate.distance);
        nearest.sort();
        long result = 0;
        for (int i = 0; i < nearest.size(); i++) result = result * 31 + nearest.get(i).id;
        nearest.clear();
        return result;
    }

    public static void main(String[] args) {
        ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocations.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        allocations.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println("Selection only; median of 5 rounds after warmup. Same 64 results required.");
        System.out.println("candidates,previous_ns,optimized_ns,speedup,previous_bytes,optimized_bytes");
        for (int count : new int[]{16, 64, 256, 1024}) {
            Candidate[][] fixtures = new Candidate[16][count];
            Random random = new Random(10824);
            for (int f = 0; f < fixtures.length; f++) {
                for (int i = 0; i < count; i++) fixtures[f][i] = new Candidate(i, random.nextInt(1000));
            }
            NearestCandidates<Candidate> nearest = new NearestCandidates<>(64);
            for (Candidate[] fixture : fixtures) {
                if (previous(fixture) != optimized(fixture, nearest)) throw new AssertionError("Selection mismatch");
            }
            int iterations = Math.max(2000, 1_000_000 / count);
            for (int i = 0; i < iterations; i++) {
                sink = previous(fixtures[i & 15]);
                sink = optimized(fixtures[i & 15], nearest);
            }
            double[] oldNanos = new double[5], newNanos = new double[5];
            double[] oldBytes = new double[5], newBytes = new double[5];
            for (int round = 0; round < 5; round++) {
                // Alternate order so that either implementation can run first.
                for (int pass = 0; pass < 2; pass++) {
                    boolean old = (pass + round) % 2 == 0;
                    long beforeBytes = allocations.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    long result = 0;
                    for (int i = 0; i < iterations; i++) {
                        Candidate[] fixture = fixtures[i & 15];
                        result += old ? previous(fixture) : optimized(fixture, nearest);
                    }
                    long elapsed = System.nanoTime() - start;
                    double bytes = (allocations.getThreadAllocatedBytes(thread) - beforeBytes) / (double) iterations;
                    sink = result;
                    if (old) {
                        oldNanos[round] = elapsed / (double) iterations;
                        oldBytes[round] = bytes;
                    } else {
                        newNanos[round] = elapsed / (double) iterations;
                        newBytes[round] = bytes;
                    }
                }
            }
            Arrays.sort(oldNanos); Arrays.sort(newNanos);
            Arrays.sort(oldBytes); Arrays.sort(newBytes);
            System.out.printf(Locale.ROOT, "%d,%.0f,%.0f,%.2f,%.0f,%.0f%n",
                    count, oldNanos[2], newNanos[2], oldNanos[2] / newNanos[2], oldBytes[2], newBytes[2]);
        }
    }
}
