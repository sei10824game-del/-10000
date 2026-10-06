package com.rlclones.ai.brain;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** A* over remembered chunk outcomes. Risk increases route cost, while unknown chunks remain traversable. */
public final class RoutePlanner {
    private static final int MAX_EXPANSIONS = 12_000;
    private static final int MARGIN = 3;
    private static final double RISK_COST = 5.0;

    private record Node(int x, int z, double cost, double estimate) {
        long key() {
            return ChunkPos.asLong(x, z);
        }
    }

    private RoutePlanner() {
    }

    /**
     * Plan a chunk route that trades a short detour against remembered hazards. The returned list includes both
     * endpoints. An empty brain (or an unknown route) naturally reduces to the shortest path.
     */
    public static List<Long> path(Brain brain, String dimension, int startX, int startZ, int targetX, int targetZ,
                                  int context, long now) {
        long start = ChunkPos.asLong(startX, startZ);
        long target = ChunkPos.asLong(targetX, targetZ);
        if (start == target) {
            return List.of(start);
        }
        int minX = Math.min(startX, targetX) - MARGIN;
        int maxX = Math.max(startX, targetX) + MARGIN;
        int minZ = Math.min(startZ, targetZ) - MARGIN;
        int maxZ = Math.max(startZ, targetZ) + MARGIN;
        long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (area > 300_000L) {
            return line(startX, startZ, targetX, targetZ);
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::estimate).thenComparingDouble(Node::cost));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        open.add(new Node(startX, startZ, 0.0, heuristic(startX, startZ, targetX, targetZ)));
        best.put(start, 0.0);
        int expansions = 0;
        boolean reached = false;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            Node node = open.poll();
            long from = node.key();
            double currentBest = best.getOrDefault(from, Double.POSITIVE_INFINITY);
            if (node.cost() > currentBest + 1e-8) {
                continue;
            }
            if (from == target) {
                reached = true;
                break;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int x = node.x() + dx;
                    int z = node.z() + dz;
                    if (x < minX || x > maxX || z < minZ || z > maxZ) {
                        continue;
                    }
                    long key = ChunkPos.asLong(x, z);
                    double step = dx != 0 && dz != 0 ? Math.sqrt(2.0) : 1.0;
                    float risk = brain == null ? 0f : brain.routeRisk(dimension, x, z, context, now);
                    double candidate = node.cost() + step + RISK_COST * risk;
                    if (candidate + 1e-8 >= best.getOrDefault(key, Double.POSITIVE_INFINITY)) {
                        continue;
                    }
                    best.put(key, candidate);
                    parent.put(key, from);
                    open.add(new Node(x, z, candidate, candidate + heuristic(x, z, targetX, targetZ)));
                }
            }
        }
        if (!reached) {
            return line(startX, startZ, targetX, targetZ);
        }
        ArrayList<Long> reversed = new ArrayList<>();
        long at = target;
        reversed.add(at);
        while (at != start) {
            Long previous = parent.get(at);
            if (previous == null) {
                return line(startX, startZ, targetX, targetZ);
            }
            at = previous;
            reversed.add(at);
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /** Direct-line route, including both endpoints, for counterfactual comparison with A*. */
    public static List<Long> directPath(int startX, int startZ, int targetX, int targetZ) {
        return line(startX, startZ, targetX, targetZ);
    }

    /** Risk on a direct line, used to avoid adding waypoints when the known route is already clear. */
    public static float directRiskCost(Brain brain, String dimension, int startX, int startZ, int targetX, int targetZ,
                                       int context, long now) {
        if (brain == null) {
            return 0f;
        }
        return pathRisk(brain, dimension, line(startX, startZ, targetX, targetZ), context, now);
    }

    /** Remembered hazard exposure along the chosen path (not counting the current chunk). */
    public static float riskCost(Brain brain, String dimension, int startX, int startZ, int targetX, int targetZ,
                                 int context, long now) {
        if (brain == null) {
            return 0f;
        }
        List<Long> route = path(brain, dimension, startX, startZ, targetX, targetZ, context, now);
        return pathRisk(brain, dimension, route, context, now);
    }

    /** Total path length plus remembered risk, with costs matching the A* planner. */
    public static float routeCost(Brain brain, String dimension, List<Long> route, int context, long now) {
        if (route == null || route.isEmpty()) {
            return Float.MAX_VALUE;
        }
        double length = 0.0;
        for (int i = 1; i < route.size(); i++) {
            int dx = Math.abs(ChunkPos.getX(route.get(i)) - ChunkPos.getX(route.get(i - 1)));
            int dz = Math.abs(ChunkPos.getZ(route.get(i)) - ChunkPos.getZ(route.get(i - 1)));
            length += dx != 0 && dz != 0 ? Math.sqrt(2.0) : 1.0;
        }
        return (float) Math.min(Float.MAX_VALUE, length + RISK_COST * pathRisk(brain, dimension, route, context, now));
    }

    public static float pathRisk(Brain brain, String dimension, List<Long> route, int context, long now) {
        if (brain == null || route == null) {
            return 0f;
        }
        float risk = 0f;
        for (int i = 1; i < route.size(); i++) {
            long chunk = route.get(i);
            risk += brain.routeRisk(dimension, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), context, now);
        }
        return risk;
    }

    public static double distance(int startX, int startZ, int targetX, int targetZ) {
        return heuristic(startX, startZ, targetX, targetZ);
    }

    private static List<Long> line(int startX, int startZ, int targetX, int targetZ) {
        ArrayList<Long> chunks = new ArrayList<>();
        int dx = Math.abs(targetX - startX);
        int dz = Math.abs(targetZ - startZ);
        int sx = Integer.compare(targetX, startX);
        int sz = Integer.compare(targetZ, startZ);
        int err = dx - dz;
        int x = startX;
        int z = startZ;
        while (true) {
            chunks.add(ChunkPos.asLong(x, z));
            if (x == targetX && z == targetZ) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
        return List.copyOf(chunks);
    }

    private static double heuristic(int x, int z, int targetX, int targetZ) {
        int dx = Math.abs(targetX - x);
        int dz = Math.abs(targetZ - z);
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return diagonal * Math.sqrt(2.0) + straight;
    }
}
