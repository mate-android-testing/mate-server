package org.mate.crash_reproduction;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CausedByStackTraceLine implements StackTraceLine {
    private final String line;
    private final String exception;
    private final String message;

    public CausedByStackTraceLine(String line, String exception, String message) {
        this.line = line;
        this.exception = exception;
        this.message = message;
    }

    @Override
    public boolean isFromPackage(String string) {
        return true;
    }

    @Override
    public Stream<String> getFuzzyTokens() {
        if (exception.equals("java.lang.NumberFormatException")) {
            Pattern messagePattern = Pattern.compile(".*\"(\\d*)\".*");
            Matcher matcher = messagePattern.matcher(message);

            if (matcher.matches()) {
                return Stream.of(matcher.group(1));
            }
        } else if (exception.equals("java.lang.NullPointerException")) {
            return Stream.empty();
        }
        return Stream.of(message);
    }

    @Override
    public String toString() {
        return line;
    }
}
