package org.mate.novelty;

import org.apache.commons.text.similarity.CosineSimilarity;
import org.mate.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides a novelty metric based on cosine distance (= 1 - cosine similarity).
 */
public class NoveltyMetric {

    /**
     * Evaluates the novelty metric for the given population.
     *
     * @param population The population representing the current population plus the archive.
     * @param nearestNeighbours The number of nearest neighbours k.
     * @return Returns the novelty vector.
     */
    public static List<Double> evaluate(List<CoverageVector> population, int nearestNeighbours) {

        List<Double> noveltyVector = new ArrayList<>();

        // we need to compute the distance of each member with the rest of the population
        for (CoverageVector member : population) {

            List<Double> distances = new ArrayList<>();

            for (CoverageVector other : population) {

                if (!member.equals(other)) {
                    distances.add(computeCosineDistance(member, other));
                }
            }

            // take the average distance to the k nearest neighbours if k neighbours are present
            int neighbours = Math.min(nearestNeighbours, distances.size());

            double novelty = 0.0;

            for (int k = 0; k < neighbours; k++) {
                novelty += distances.get(k);
            }

            noveltyVector.add(novelty / neighbours);
        }

        return noveltyVector;
    }

    /**
     * Computes the cosine distance between the vector v1 and v2. The result is bounded in [0,1] where 1 is the best
     * value in terms of novelty.
     *
     * @param v1 The first vector.
     * @param v2 The second vector.
     * @return Returns the cosine distance between vector v1 and v2. The result is bounded in [0,1].
     */
    private static double computeCosineDistance(CoverageVector v1, CoverageVector v2) {

        /*
        * If both vectors v1 and v2 represent the 0-vector, the cosine similarity is erroneously defined as 0.
        * This, in turn would assign the highest novelty score to those vectors. We have to intercept this case
        * and assign a distance of 0.
         */
        if (v1.isZeroVector() && v2.isZeroVector()) {
            Log.println("Comparing two 0-vectors!");
            return 0;
        } else {
            return 1 - new CosineSimilarity().cosineSimilarity(v1.getVector(), v2.getVector());
        }
    }
}
