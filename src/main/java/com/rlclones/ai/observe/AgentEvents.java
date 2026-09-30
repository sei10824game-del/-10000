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
    private static final Map<UUID, ArrayDeque<Event>> LOGS = new HashMap<>();

    private AgentEvents() {
    }

    public static void record(UUID agent, long tick, Kind kind, float amount, int targetId, int flags) {
        ArrayDeque<Event> log = LOGS.computeIfAbsent(agent, k -> new ArrayDeque<>());
        log.addLast(new Event(tick, kind, amount, targetId, flags));
        while (!log.isEmpty() && (tick - log.peekFirst().tick() > KEEP_TICKS || log.size() > 256)) {
            log.pollFirst();
        }
    }

    /** Events with {@code from < tick <= to}. */
    public static List<Event> between(UUID agent, long from, long to) {
        ArrayDeque<Event> log = LOGS.get(agent);
        if (log == null || log.isEmpty()) {
            return List.of();
        }
        List<Event> out = new ArrayList<>();
        for (Event e : log) {
            if (e.tick() > from && e.tick() <= to) {
                out.add(e);
            }
        }
        return out;
    }

    public static void prune(long now) {
        Iterator<Map.Entry<UUID, ArrayDeque<Event>>> it = LOGS.entrySet().iterator();
        while (it.hasNext()) {
            ArrayDeque<Event> log = it.next().getValue();
            while (!log.isEmpty() && now - log.peekFirst().tick() > KEEP_TICKS) {
                log.pollFirst();
            }
            if (log.isEmpty()) {
                it.remove();
            }
        }
    }

    public static void clear() {
        LOGS.clear();
    }
}
