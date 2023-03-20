package org.mate.crash_reproduction;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Models a stack trace. A stack trace consists of multiple lines as the following example shows:
 *
 * Exception in thread "main" com.myproject.module.MyProjectFooBarException: (optional exception message)
 *     at com.myproject.module.MyProject.anotherMethod(MyProject.java:19)
 *     at com.myproject.module.MyProject.someMethod(MyProject.java:12)
 *     at com.myproject.module.MyProject.main(MyProject.java:8)
 * Caused by: java.lang.ArithmeticException: The denominator must not be zero
 *     at org.apache.commons.lang3.math.Fraction.getFraction(Fraction.java:143)
 *     at com.myproject.module.MyProject.anotherMethod(MyProject.java:17)
 *     ... 2 more
 *
 * We can observe essentially three different line types: 'at', 'caused by' and '... X more' stack trace lines.
 * A regular stack trace line has the following format:
 *
 *  at com.myproject.module.MyProject.anotherMethod(MyProject.java:19)
 *            |package name|class name|method name|file name|line number|
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

    /**
     * Retrieves all tokens from {@link AtStackTraceLine}s belonging to the specified package name.
     *
     * @param packageName The given package name.
     * @return Returns the relevant tokens for the online-phase.
     */
    public Set<String> getFuzzyTokens(String packageName) {
        return stackTraceLines.stream()
                .filter(stackTraceLine -> stackTraceLine.isFromPackage(packageName))
                .filter(stackTraceLine -> stackTraceLine instanceof AtStackTraceLine)
                .flatMap(StackTraceLine::getFuzzyTokens)
                .filter(token -> !packageName.contains(token) && token.length() > 2 && !IGNORE_TOKENS.contains(token))
                .collect(Collectors.toSet());
    }

    /**
     * Retrieves all tokens from {@link CausedByStackTraceLine}s.
     *
     * @return Returns the relevant tokens for the online-phase.
     */
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
