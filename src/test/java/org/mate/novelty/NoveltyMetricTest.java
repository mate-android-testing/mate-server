package org.mate.novelty;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mate.coverage.CoverageVector;
import org.mate.util.Log;

import java.util.Collections;
import java.util.Set;

public class NoveltyMetricTest {

    @BeforeAll
    public static void setup() {
        Log.registerLogger(new Log()); // Required for the logger invocations.
    }

    @Test
    public void testZeroVector() {
        final Set<String> targets = Set.of("1", "2", "3");
        final CoverageVector v1 = new CoverageVector(targets, Collections.emptySet());
        final CoverageVector v2 = new CoverageVector(targets, Set.of("1"));
        Assertions.assertEquals(1, NoveltyMetric.evaluate(v1, Collections.singletonList(v2), 1));
    }

    @Test
    public void testZeroVectors() {
        final Set<String> targets = Set.of("1", "2", "3");
        final CoverageVector v1 = new CoverageVector(targets, Collections.emptySet());
        final CoverageVector v2 = new CoverageVector(targets, Collections.emptySet());
        Assertions.assertEquals(0, NoveltyMetric.evaluate(v1, Collections.singletonList(v2), 1));
    }

    @Test
    public void testEqualVectors() {
        final Set<String> targets = Set.of("1", "2", "3");
        final CoverageVector v1 = new CoverageVector(targets, Set.of("1"));
        final CoverageVector v2 = new CoverageVector(targets, Set.of("1"));
        Assertions.assertEquals(0, NoveltyMetric.evaluate(v1, Collections.singletonList(v2), 1));
    }
}
