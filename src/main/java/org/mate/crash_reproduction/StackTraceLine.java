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

    // TODO: What is this, the keywords that can be extracted? May rename.
    Stream<String> getFuzzyTokens();

    /**
     * A textual representation of the stack trace line.
     *
     * @return Returns a textual representation of the stack trace line.
     */
    String toString();
}
