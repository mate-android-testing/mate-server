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

    // TODO: How did we derive those irrelevant tokens?
    private static final Set<String> IGNORE_TOKENS = Set.of("in", "and", "but", "the");

    /**
     * The individual stack trace lines.
     */
    private final List<StackTraceLine> stackTraceLines;

    /**
     * The package name of the AUT.
     */
    private final String packageName;

    /**
     * Initialises a stack trace.
     *
     * @param stackTraceLines The individual stack trace lines.
     */
    public StackTrace(List<StackTraceLine> stackTraceLines, String packageName) {
        this.stackTraceLines = stackTraceLines;
        this.packageName = packageName;
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
     * Retrieves the 'at' stack trace lines belonging to the AUT.
     *
     * @return Returns the {@link AtStackTraceLine} lines belonging to the AUT.
     */
    public List<AtStackTraceLine> getStackTraceAtLinesOfAUT() {
        return getStackTraceAtLines()
                .filter(stackTraceLine -> stackTraceLine.isFromPackage(packageName))
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

    /**
     * Returns a textual representation for the stack trace.
     *
     * @return Returns a textual representation for the stack trace.
     */
    @Override
    public String toString() {
        return stackTraceLines.stream().map(StackTraceLine::toString).collect(Collectors.joining(System.lineSeparator()));
    }

    /**
     * Compares two stack traces for equality by comparing each single stack trace line.
     *
     * @param o The other stack trace.
     * @return Returns {@code true} if the two stack traces are identical, otherwise {@code false}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StackTrace that = (StackTrace) o;
        return packageName.equals(that.packageName) && ((Objects.equals(stackTraceLines, that.stackTraceLines)
                // NOTE: If the stack trace was produced on a different emulator it is likely that the stack trace line
                // numbers diverge that belong to the Android framework, thus it reasonable to compare only the 'at'
                // stack trace lines belonging to the AUT. Moreover, the top stack trace line containing the exception
                // message or any 'caused by' lines might contain dynamic object ids, which makes the comparison on those
                // lines tricky. However, it could theoretically happen that we can't distinguish between two crashes
                // originating in the same line (there can be multiple statements in a single source code line).
                || Objects.equals(getStackTraceAtLinesOfAUT(), that.getStackTraceAtLinesOfAUT())));
    }

    /**
     * Computes a hash code for the stack trace.
     *
     * @return Returns the computed hash code for the stack trace.
     */
    @Override
    public int hashCode() {
        return Objects.hash(packageName, stackTraceLines, getStackTraceAtLinesOfAUT());
    }
}
