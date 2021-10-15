package org.mate.novelty;

import org.apache.commons.text.similarity.CosineSimilarity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides a novelty metric based on cosine distance (= 1 - cosine similarity).
 */
public class NoveltyMetric {

    /**
     * Evaluates the novelty metric for the given solution.
     *
     * @param solution The solution vector.
     * @param population The population representing the current population plus the archive.
     * @param nearestNeighbours The number of nearest neighbours k.
     * @return Returns the novelty metric for the given solution.
     */
    public static double evaluate(NoveltyVector solution, List<NoveltyVector> population, int nearestNeighbours) {

        // evaluate distance from solution to every member in the population
        List<Double> distances = new ArrayList<>();

        for (NoveltyVector member : population) {
            distances.add(computeCosineDistance(solution, member));
        }

        // sort the distances
        Collections.sort(distances);

        // take the average distance to the k nearest neighbours
        double novelty = 0.0;

        for (int k = 0; k < nearestNeighbours; k++) {
            novelty += distances.get(k);
        }

        return novelty / nearestNeighbours;
    }

    /**
     * Computes the cosine distance between the vector v1 and v2. The result is bounded in [0,1] where 1 is the best
     * value in terms of novelty.
     *
     * @param v1 The first vector.
     * @param v2 The second vector.
     * @return Returns the cosine distance between vector v1 and v2. The result is bounded in [0,1].
     */
    private static double computeCosineDistance(NoveltyVector v1, NoveltyVector v2) {
        return 1 - new CosineSimilarity().cosineSimilarity(v1.getVector(), v2.getVector());
    }
}
