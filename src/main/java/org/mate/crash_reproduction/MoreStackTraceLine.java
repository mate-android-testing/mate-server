package org.mate.crash_reproduction;

import java.util.stream.Stream;

public class MoreStackTraceLine implements StackTraceLine {
    private final int numberOfLinesMissing;

    public MoreStackTraceLine(int numberOfLinesMissing) {
        this.numberOfLinesMissing = numberOfLinesMissing;
    }

    public int getNumberOfLinesMissing() {
        return numberOfLinesMissing;
    }

    @Override
    public boolean isFromPackage(String string) {
        return false;
    }

    @Override
    public Stream<String> getFuzzyTokens() {
        return Stream.empty();
    }

    @Override
    public String toString() {
        return "... " + numberOfLinesMissing + " more";
    }
}
