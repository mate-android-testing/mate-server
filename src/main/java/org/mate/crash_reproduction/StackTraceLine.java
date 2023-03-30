package org.mate.crash_reproduction;

import java.util.stream.Stream;

/**
 * Models an abstract stack trace line.
 */
public interface StackTraceLine {

    /**
     * Checks whether the given package name is a prefix of the stack trace line's package name.
     *
     * @param packageName The given package name.
     * @return Returns {@code true} if the given package name is a prefix, otherwise {@code false} is returned.
     */
    boolean isFromPackage(String packageName);

    /**
     * Returns the relevant tokens for the online-phase (see paper) from the stack trace line.
     *
     * @return Returns the relevant tokens (keywords) for the online-phase.
     */
    Stream<String> getFuzzyTokens();

    /**
     * A textual representation of the stack trace line.
     *
     * @return Returns a textual representation of the stack trace line.
     */
    String toString();
}
