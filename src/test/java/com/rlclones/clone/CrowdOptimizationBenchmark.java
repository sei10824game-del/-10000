package com.rlclones.clone;

import com.rlclones.ai.observe.AgentEvents;
import com.sun.management.ThreadMXBean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.LongSupplier;

/** Component workloads scaled to clone counts, not whole-server tick/FPS measurements. */
public final class CrowdOptimizationBenchmark {
    private static volatile long sink;
    private record Measurement(double nanos, double bytes) {}
    private static Measurement measure(LongSupplier work, int repetitions, ThreadMXBean memory) {
        long thread = Thread.currentThread().getId();
        long before = memory.getThreadAllocatedBytes(thread), start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < repetitions; i++) total += work.getAsLong();
        long elapsed = System.nanoTime() - start;
        sink = total;
        return new Measurement(elapsed / (double) repetitions,
                (memory.getThreadAllocatedBytes(thread) - before) / (double) repetitions);
    }
    private static void compare(String component, int clones, LongSupplier old, LongSupplier optimized, int repetitions, ThreadMXBean memory) {
        for (int i = 0; i < 12; i++) { sink = old.getAsLong(); sink = optimized.getAsLong(); }
        double[] oldNs = new double[5], newNs = new double[5], oldBytes = new double[5], newBytes = new double[5];
        for (int round = 0; round < 5; round++) {
            for (int pass = 0; pass < 2; pass++) {
                boolean baseline = (round + pass) % 2 == 0;
                Measurement m = measure(baseline ? old : optimized, repetitions, memory);
                if (baseline) { oldNs[round] = m.nanos; oldBytes[round] = m.bytes; }
                else { newNs[round] = m.nanos; newBytes[round] = m.bytes; }
            }
        }
        Arrays.sort(oldNs); Arrays.sort(newNs); Arrays.sort(oldBytes); Arrays.sort(newBytes);
        System.out.printf(Locale.ROOT, "%s,%d,%.0f,%.0f,%.2f,%.0f,%.0f%n", component, clones, oldNs[2], newNs[2], oldNs[2]/newNs[2], oldBytes[2], newBytes[2]);
    }
    private static long geometry(List<StairGeometryReference.Fixture> stairs, BlockPos[] queries, boolean baseline) {
        long result = 0;
        for (BlockPos pos : queries) {
            if (baseline ? StairGeometryReference.space(stairs, pos, true) : StairGeometryTest.space(stairs, pos, true)) result++;
            if (baseline ? StairGeometryReference.space(stairs, pos, false) : StairGeometryTest.space(stairs, pos, false)) result += 2;
            if (baseline ? StairGeometryReference.tread(stairs, pos) : StairGeometryTest.tread(stairs, pos)) result += 4;
        }
        return result;
    }
    private static long eventTick = 1000;
    private static long events(UUID[] agents, boolean baseline) {
        long now = ++eventTick;
        long result = 0;
        for (UUID id : agents) {
            // One new event invalidates the cache before eight observers inspect three windows.
            AgentEvents.record(id, now, AgentEvents.Kind.ATTACK, 1, 7, 0);
            for (int observer = 0; observer < 8; observer++) {
                for (long window : WINDOWS) {
                    var list = baseline ? AgentEvents.between(id, now - window, now) : AgentEvents.sharedBetween(id, now - window, now);
                    result += list.size();
                }
            }
        }
        return result;
    }
    private static final long[] WINDOWS = {40, 20, 4};
    public static void main(String[] args) {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        System.out.println("Java " + System.getProperty("java.version") + "; median of 5 alternating rounds after warmup");
        System.out.println("component,simulated_clones,previous_ns_per_batch,optimized_ns_per_batch,speedup,previous_bytes_per_batch,optimized_bytes_per_batch");
        List<StairGeometryReference.Fixture> stairs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            BlockPos top = new BlockPos((i % 4) * 24, 100, (i / 4) * 24);
            Direction dir = Direction.from2DDataValue(i % 4);
            stairs.add(new StairGeometryReference.Fixture(top, dir, 128, i % 2 == 0));
        }
        for (int count : new int[]{50, 150, 300}) {
            Random random = new Random(10824);
            BlockPos[] queries = new BlockPos[count * 4];
            for (int i = 0; i < queries.length; i++) {
                var stair = stairs.get(random.nextInt(stairs.size()));
                int step = random.nextInt(170) - 2;
                queries[i] = stair.top().relative(stair.dir(), step).below(Math.max(0, step))
                        .relative(stair.dir().getClockWise(), random.nextInt(9) - 4).above(random.nextInt(5) - 1);
            }
            if (geometry(stairs, queries, true) != geometry(stairs, queries, false)) throw new AssertionError("geometry mismatch");
            compare("stair_geometry", count, () -> geometry(stairs, queries, true), () -> geometry(stairs, queries, false), 5, memory);
            AgentEvents.clear();
            eventTick = 1000;
            UUID[] agents = new UUID[count];
            for (int i = 0; i < count; i++) {
                agents[i] = new UUID(0, i + 1);
                for (int tick = 800; tick <= 1000; tick++) AgentEvents.record(agents[i], tick, AgentEvents.Kind.ATTACK, 1, 7, 0);
            }
            if (events(agents, true) != events(agents, false)) throw new AssertionError("event mismatch");
            compare("observation_events", count, () -> events(agents, true), () -> events(agents, false), 8, memory);
        }
        AgentEvents.clear();
    }
}
