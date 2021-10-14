package org.mate.novelty;

import org.mate.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Represents a novelty vector, i.e. a vector that maps a target to 0 (not covered) or 1 (covered).
 */
public class NoveltyVector {

    /**
     * Maps a target to the value 0 (not covered) or 1 (covered).
     */
    private final Map<CharSequence, Integer> vector;

    /**
     * Initializes a new novelty vector.
     *
     * @param targets The set of targets (keys).
     * @param traces The set of traces.
     */
    public NoveltyVector(Set<String> targets, Set<String> traces) {
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
     * Returns the vector.
     *
     * @return Returns the internal vector.
     */
    public Map<CharSequence, Integer> getVector() {
        return Collections.unmodifiableMap(vector);
    }
}
