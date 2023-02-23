package org.mate.crash_reproduction;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Models a 'caused by' stack trace line.
 */
public class CausedByStackTraceLine implements StackTraceLine {

    /**
     * The complete stack trace line.
     */
    private final String line;

    /**
     * The exception name.
     */
    private final String exception;

    /**
     * The (optional) exception message.
     */
    private final String message;

    /**
     * Initialises a 'caused by' stack trace line without an exception message.
     *
     * @param line The complete stack trace line.
     * @param exception The exception name.
     */
    public CausedByStackTraceLine(String line, String exception) {
        this(line, exception, null);
    }

    /**
     * Initialises a 'caused by' stack trace line.
     *
     * @param line The complete stack trace line.
     * @param exception The exception name.
     * @param message The exception message.
     */
    public CausedByStackTraceLine(String line, String exception, String message) {
        this.line = line;
        this.exception = exception;
        this.message = message;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFromPackage(String packageName) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stream<String> getFuzzyTokens() {

        if (message == null) {
            return Stream.empty();
        }

        // TODO: Document this edge case.
        if (exception.equals("java.lang.NumberFormatException")) {
            final Pattern messagePattern = Pattern.compile(".*\"(\\d*)\".*");
            final Matcher matcher = messagePattern.matcher(message);

            if (matcher.matches()) {
                return Stream.of(matcher.group(1));
            }
        } else if (exception.equals("java.lang.NullPointerException")) {
            return Stream.empty();
        }
        return Stream.of(message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return line;
    }
}
