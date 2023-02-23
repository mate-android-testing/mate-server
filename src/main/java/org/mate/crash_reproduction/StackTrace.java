package org.mate.crash_reproduction;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Models a stack trace.
 */
public class StackTrace {

    // TODO: What is this?
    private static final Set<String> IGNORE_TOKENS = Set.of("in", "and", "but", "the");

    /**
     * The individual stack trace lines.
     */
    private final List<StackTraceLine> stackTraceLines;

    /**
     * Initialises a stack trace.
     *
     * @param stackTraceLines The individual stack trace lines.
     */
    public StackTrace(List<StackTraceLine> stackTraceLines) {
        this.stackTraceLines = stackTraceLines;
    }

    // TODO: Document.
    public Set<String> getFuzzyTokens(String packageName) {
        return stackTraceLines.stream()
                .filter(stackTraceLine -> stackTraceLine.isFromPackage(packageName))
                .filter(stackTraceLine -> stackTraceLine instanceof AtStackTraceLine)
                .flatMap(StackTraceLine::getFuzzyTokens)
                .filter(token -> !packageName.contains(token) && token.length() > 2 && !IGNORE_TOKENS.contains(token))
                .collect(Collectors.toSet());
    }

    // TODO: May rename.
    public Set<String> getUserTokens() {
        return stackTraceLines.stream()
                .filter(stackTraceLine -> stackTraceLine instanceof CausedByStackTraceLine)
                .flatMap(StackTraceLine::getFuzzyTokens)
                .collect(Collectors.toSet());
    }

    /**
     * Retrieves the raw 'at' stack trace lines.
     *
     * @return Returns the raw 'at' stack trace lines.
     */
    public List<String> getAtLines() {
        return getStackTraceAtLines()
                .map(Objects::toString)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves the 'at' stack trace lines.
     *
     * @return Returns the {@link AtStackTraceLine} lines.
     */
    public Stream<AtStackTraceLine> getStackTraceAtLines() {
        return stackTraceLines.stream()
                .filter(line -> line instanceof AtStackTraceLine)
                .map(l -> (AtStackTraceLine) l);
    }
}
