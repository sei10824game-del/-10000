package com.rlclones.ai.util;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class NearestCandidatesTest {
    private record Candidate(int id, double distance) {}

    @Test
    void matchesFullStableSortAcrossSizesAndRepeatedScans() {
        Random random = new Random(10824);
        for (int limit : new int[]{1, 2, 16, 64}) {
            NearestCandidates<Candidate> nearest = new NearestCandidates<>(limit);
            for (int count : new int[]{0, 1, 2, 16, 63, 64, 65, 128, 1024, 0, 7}) {
                for (int mode = 0; mode < 4; mode++) {
                    List<Candidate> expected = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        double distance = switch (mode) {
                            case 0 -> random.nextInt(12); // ties across the cutoff
                            case 1 -> i;
                            case 2 -> count - i;
                            default -> random.nextDouble();
                        };
                        Candidate candidate = new Candidate(i, distance);
                        expected.add(candidate);
                        nearest.offer(candidate, distance);
                    }
                    expected.sort(Comparator.comparingDouble(Candidate::distance));
                    nearest.sort();
                    assertEquals(Math.min(limit, count), nearest.size());
                    for (int i = 0; i < nearest.size(); i++) assertSame(expected.get(i), nearest.get(i));
                    nearest.sort(); // repeated reads must not reorder the batch
                    for (int i = 0; i < nearest.size(); i++) assertSame(expected.get(i), nearest.get(i));
                    nearest.clear();
                    assertEquals(0, nearest.size());
                }
            }
        }
    }

    @Test
    void usesDoubleCompareOrderingForSpecialValues() {
        double[] distances = {Double.NaN, Double.POSITIVE_INFINITY, 0.0, -0.0, -1, Double.NEGATIVE_INFINITY, 0.0};
        NearestCandidates<Integer> nearest = new NearestCandidates<>(5);
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < distances.length; i++) {
            expected.add(i);
            nearest.offer(i, distances[i]);
        }
        expected.sort(Comparator.comparingDouble(i -> distances[i]));
        nearest.sort();
        for (int i = 0; i < nearest.size(); i++) assertEquals(expected.get(i), nearest.get(i));
    }

    @Test
    void keepsDuplicatesAndEarliestEqualDistanceCandidates() {
        NearestCandidates<String> nearest = new NearestCandidates<>(3);
        nearest.offer("same entity", 1);
        nearest.offer("same entity", 1);
        nearest.offer("third", 1);
        nearest.offer("later", 1);
        nearest.sort();
        assertEquals("same entity", nearest.get(0));
        assertEquals("same entity", nearest.get(1));
        assertEquals("third", nearest.get(2));
    }

    @Test
    void enforcesBatchLifecycle() {
        assertThrows(IllegalArgumentException.class, () -> new NearestCandidates<>(0));
        NearestCandidates<String> nearest = new NearestCandidates<>(1);
        nearest.offer("a", 1);
        assertThrows(IllegalStateException.class, () -> nearest.get(0));
        nearest.sort();
        assertThrows(IndexOutOfBoundsException.class, () -> nearest.get(1));
        assertThrows(IllegalStateException.class, () -> nearest.offer("b", 0));
        nearest.clear();
        nearest.offer("b", 0);
        nearest.sort();
        assertEquals("b", nearest.get(0));
    }

    @Test
    void repeatedFullAndPartialHeapsKeepStableOrder() {
        Random random = new Random(10824);
        double[] special = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0, 1.0};
        for (int limit : new int[]{1, 2, 3, 16, 64, 128}) {
            NearestCandidates<Candidate> nearest = new NearestCandidates<>(limit);
            for (int scan = 0; scan < 2000; scan++) {
                List<Candidate> expected = new ArrayList<>();
                int count = random.nextInt(1100);
                for (int i = 0; i < count; i++) {
                    double distance = switch (scan % 5) {
                        case 0 -> random.nextInt(10);
                        case 1 -> i;
                        case 2 -> -i;
                        case 3 -> random.nextDouble();
                        default -> special[random.nextInt(special.length)];
                    };
                    Candidate candidate = new Candidate(i, distance);
                    expected.add(candidate);
                    nearest.offer(candidate, distance);
                }
                expected.sort(Comparator.comparingDouble(Candidate::distance));
                nearest.sort();
                nearest.sort();
                assertEquals(Math.min(limit, count), nearest.size());
                for (int i = 0; i < nearest.size(); i++) assertSame(expected.get(i), nearest.get(i));
                nearest.clear();
                assertEquals(0, nearest.size());
            }
        }
    }
}
