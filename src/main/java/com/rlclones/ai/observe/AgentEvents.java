package com.rlclones.ai.observe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Short-term log of what every agent (real player or clone - they are treated identically) did.
 * Observers only read the parts of it that happened while the agent was inside their field of view.
 */
public final class AgentEvents {
    public enum Kind {
        ATTACK, DEALT, HURT, KILL_HOSTILE, KILL_ANIMAL, DEATH, PICKUP, BREAK_LOG, BREAK_ORE, BREAK_OTHER, PILLAR, EAT, BLOCKED, SHOOT, CRAFT
    }

    public static final int FLAG_CRIT = 1;
    public static final int FLAG_SPRINT = 2;
    public static final int FLAG_HOSTILE = 4;

    public record Event(long tick, Kind kind, float amount, int targetId, int flags) {
    }

    private static final long KEEP_TICKS = 200;
    private record Range(long from, long to, List<Event> events) {}
    private static final class AgentLog {
        final ArrayDeque<Event> events = new ArrayDeque<>();
        final Range[] ranges = new Range[4];
        int next;
        void invalidate() { java.util.Arrays.fill(ranges, null); next = 0; }
    }
    private static final Map<UUID, AgentLog> LOGS = new HashMap<>();

    private AgentEvents() {
    }

    public static void record(UUID agent, long tick, Kind kind, float amount, int targetId, int flags) {
        AgentLog entry = LOGS.computeIfAbsent(agent, k -> new AgentLog());
        entry.invalidate();
        ArrayDeque<Event> log = entry.events;
        log.addLast(new Event(tick, kind, amount, targetId, flags));
        while (!log.isEmpty() && (tick - log.peekFirst().tick() > KEEP_TICKS || log.size() > 256)) {
            log.pollFirst();
        }
    }

    /** Events with {@code from < tick <= to}. */
    public static List<Event> between(UUID agent, long from, long to) {
        AgentLog entry = LOGS.get(agent);
        if (entry == null || entry.events.isEmpty()) {
            return List.of();
        }
        List<Event> out = new ArrayList<>();
        for (Event e : entry.events) {
            if (e.tick() > from && e.tick() <= to) {
                out.add(e);
            }
        }
        return out;
    }

    /** Immutable shared snapshot; recording or pruning invalidates every cached range for this agent. */
    public static List<Event> sharedBetween(UUID agent, long from, long to) {
        AgentLog entry = LOGS.get(agent);
        if (entry == null || entry.events.isEmpty()) return List.of();
        for (Range range : entry.ranges) {
            if (range != null && range.from == from && range.to == to) return range.events;
        }
        List<Event> snapshot = List.copyOf(between(agent, from, to));
        entry.ranges[entry.next] = new Range(from, to, snapshot);
        entry.next = (entry.next + 1) % entry.ranges.length;
        return snapshot;
    }

    public static void prune(long now) {
        Iterator<Map.Entry<UUID, AgentLog>> it = LOGS.entrySet().iterator();
        while (it.hasNext()) {
            AgentLog entry = it.next().getValue();
            ArrayDeque<Event> log = entry.events;
            boolean changed = false;
            while (!log.isEmpty() && now - log.peekFirst().tick() > KEEP_TICKS) {
                log.pollFirst();
                changed = true;
            }
            if (changed) entry.invalidate();
            if (log.isEmpty()) {
                it.remove();
            }
        }
    }

    public static void clear() {
        LOGS.clear();
    }
}
