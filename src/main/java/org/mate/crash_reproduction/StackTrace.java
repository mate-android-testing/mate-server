package org.mate.crash_reproduction;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StackTrace {
    private static final Set<String> IGNORE_TOKENS = Set.of(
            "in", "and", "but", "the"
    );
    private final List<StackTraceLine> stackTraceLines;

    public StackTrace(List<StackTraceLine> stackTraceLines) {
        this.stackTraceLines = stackTraceLines;
    }

    public Set<String> getFuzzyTokens(String packageName) {
        return stackTraceLines.stream()
                .filter(stackTraceLine -> stackTraceLine.isFromPackage(packageName))
                .filter(stackTraceLine -> stackTraceLine instanceof AtStackTraceLine)
                .flatMap(StackTraceLine::getFuzzyTokens)
                .filter(token -> !packageName.contains(token) && token.length() > 2 && !IGNORE_TOKENS.contains(token))
                .collect(Collectors.toSet());
    }

    public Set<String> getUserTokens() {
        return stackTraceLines.stream()
                .filter(stackTraceLine -> stackTraceLine instanceof CausedByStackTraceLine)
                .flatMap(StackTraceLine::getFuzzyTokens)
                .collect(Collectors.toSet());
    }

    public List<String> getAtLines() {
        return getStackTraceAtLines()
                .map(Objects::toString)
                .collect(Collectors.toList());
    }

    public Stream<AtStackTraceLine> getStackTraceAtLines() {
        return stackTraceLines.stream()
                .filter(line -> line instanceof AtStackTraceLine)
                .map(l -> (AtStackTraceLine) l);
    }
}
