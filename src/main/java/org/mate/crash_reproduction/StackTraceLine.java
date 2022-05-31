package org.mate.crash_reproduction;

import java.util.stream.Stream;

public interface StackTraceLine {
    boolean isFromPackage(String string);

    Stream<String> getFuzzyTokens();
}
