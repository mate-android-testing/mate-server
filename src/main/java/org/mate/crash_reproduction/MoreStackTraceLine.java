package org.mate.crash_reproduction;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Models a '... X more' stack trace line. Such a line appears when the subsequent stack trace lines are repeating
 * themselves.
 */
public class MoreStackTraceLine implements StackTraceLine {

    /**
     * The number indicating how many stack trace lines would follow but have been compacted.
     */
    private final int numberOfSubsequentLines;

    /**
     * Initialises a '... X more' stack trace line with the given number of subsequent lines.
     * 
     * @param numberOfSubsequentLines The number of subsequent lines.
     */
    public MoreStackTraceLine(int numberOfSubsequentLines) {
        this.numberOfSubsequentLines = numberOfSubsequentLines;
    }

    /**
     * Returns the number of subsequent (missing) lines.
     * 
     * @return Returns the number of subsequent (missing) lines.
     */
    public int getNumberOfSubsequentLines() {
        return numberOfSubsequentLines;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFromPackage(String packageName) {
        return false;
    }

    /**
     * No tokens (keywords) can be derived from such a line as it encodes essentially no information.
     *
     * @return Returns an empty stream.
     */
    @Override
    public Stream<String> getFuzzyTokens() {
        return Stream.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "... " + numberOfSubsequentLines + " more";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MoreStackTraceLine that = (MoreStackTraceLine) o;
        return numberOfSubsequentLines == that.numberOfSubsequentLines;
    }

    @Override
    public int hashCode() {
        return Objects.hash(numberOfSubsequentLines);
    }
}
