package org.mate.coverage;

import org.mate.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Represents a coverage vector, i.e. a vector that maps a target (objective) to 0 (not covered) or 1 (covered).
 */
public class CoverageVector {

    /**
     * Maps a target to the value 0 (not covered) or 1 (covered).
     */
    private final Map<CharSequence, Integer> vector;

    /**
     * Initializes a new coverage vector.
     *
     * @param targets The set of targets (keys).
     * @param traces The set of traces.
     */
    public CoverageVector(Set<String> targets, Set<String> traces) {
        vector = new HashMap<>();

        // initially, we assume that the targets are not covered
        for (String target : targets) {
            vector.put(target, 0);
        }

        // check which target is covered by the traces
        for (String trace : traces) {
            if (vector.containsKey(trace)) {
                vector.put(trace, 1);
            } else {
                Log.printWarning("The trace " + trace + " refers not to a target!");
            }
        }
    }

    /**
     * Checks whether the given vector represents the 0-vector.
     *
     * @return Returns {@code true} if the given vector represents the 0-vector, otherwise {@code false} is returned.
     */
    public boolean isZeroVector() {
        return vector.values().stream().allMatch(val -> val == 0);
    }

    /**
     * Returns the vector.
     *
     * @return Returns the internal vector.
     */
    public Map<CharSequence, Integer> getVector() {
        return Collections.unmodifiableMap(vector);
    }

    /**
     * Returns a simple string representation of the coverage vector.
     *
     * @return Returns the individual vector values as array.
     */
    @Override
    public String toString() {
        return "CoverageVector{" +
                "vector=" + vector.values() +
                '}';
    }
}
