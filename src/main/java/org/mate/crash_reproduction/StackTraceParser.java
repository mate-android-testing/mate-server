package org.mate.crash_reproduction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides a parser for a stack trace.
 */
public final class StackTraceParser {

    /**
     * A map of various stack trace line patterns and the functions that are necessary to parse the stack traces from
     * those patterns.
     */
    private static final Map<Pattern, Function<Matcher, ? extends StackTraceLine>> STACK_TRACE_LINE_PATTERNS = new HashMap<>();

    static {
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("^Caused by: (\\S+): (.+)"), StackTraceParser::parseCausedByLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("^Caused by: (\\S+)$"), StackTraceParser::parseCausedByLineWithoutMessage);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("^(\\S+)$"), StackTraceParser::parseCausedByLineWithoutMessage);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("^(\\S+): (.+)$"), StackTraceParser::parseCausedByLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\((.+):(\\d+)\\)"), StackTraceParser::parseRegularAtStackTraceLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\(:(\\d+)\\)"), StackTraceParser::parseSpecialAtStackTraceLineWithLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\(Native Method\\)"), StackTraceParser::parseSpecialAtStackTraceLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\(SourceFile.*\\)"), StackTraceParser::parseSpecialAtStackTraceLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\(\\)"), StackTraceParser::parseSpecialAtStackTraceLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\(:.*\\)"), StackTraceParser::parseSpecialAtStackTraceLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\(.*SyntheticClass\\)"), StackTraceParser::parseSpecialAtStackTraceLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\(Unknown Source\\)"), StackTraceParser::parseSpecialAtStackTraceLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\((\\S+)\\.java\\)"), StackTraceParser::parseAtStackTraceLineWithoutLineNumber);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\((\\S+)\\.kt\\)"), StackTraceParser::parseAtStackTraceLineWithoutLineNumber);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("\\.\\.\\. (\\d+) more"), StackTraceParser::parseMoreStackTraceLine);
    }

    /**
     * Prevents instantiation of utility class.
     */
    private StackTraceParser() {
        throw new UnsupportedOperationException("Cannot initialize utility class!");
    }

    /**
     * Parses a list of lines into specific stack trace lines.
     *
     * @param lines The raw input lines.
     * @return Returns a list of parsed stack trace lines.
     */
    public static StackTrace parse(final List<String> lines) {
        final List<StackTraceLine> parsedLines = lines.stream()
                .map(String::trim)
                .map(StackTraceParser::parseLine)
                .collect(Collectors.toList());
        return new StackTrace(parsedLines);
    }

    /**
     * Parses a single line into a stack trace line.
     *
     * @param line The raw input line.
     * @return Returns the parsed stack trace line.
     */
    private static StackTraceLine parseLine(final String line) {

        // check which pattern applies to the given line
        for (var entry : STACK_TRACE_LINE_PATTERNS.entrySet()) {
            final Matcher matcher = entry.getKey().matcher(line);

            if (matcher.matches()) {
                return entry.getValue().apply(matcher);
            }
        }

        throw new IllegalArgumentException("Cannot parse '" + line + "'!");
    }

    /**
     * Parses a 'caused by' stack trace line.
     *
     * @param matcher The matcher matching the raw 'caused by' input line.
     * @return Returns the parsed {@link CausedByStackTraceLine}.
     */
    private static CausedByStackTraceLine parseCausedByLine(final Matcher matcher) {
        final String exception = matcher.group(1);
        final String message = matcher.group(2);
        return new CausedByStackTraceLine(matcher.group(0), exception, message);
    }

    /**
     * Parses a 'caused by' stack trace line without a message.
     *
     * @param matcher The matcher matching the raw 'caused by' input line.
     * @return Returns the parsed {@link CausedByStackTraceLine}.
     */
    private static CausedByStackTraceLine parseCausedByLineWithoutMessage(final Matcher matcher) {
        final String exception = matcher.group(1);
        return new CausedByStackTraceLine(matcher.group(0), exception);
    }

    /**
     * Parses a regular 'at' stack trace line.
     *
     * @param matcher The matcher matching the raw 'at' input line.
     * @return Returns the parsed {@link AtStackTraceLine}.
     */
    private static AtStackTraceLine parseRegularAtStackTraceLine(final Matcher matcher) {
        final String packageName = matcher.group(1);
        final String className = matcher.group(2);
        final String methodName = matcher.group(3);
        final String fileName = matcher.group(4);
        final int lineNumber = Integer.parseInt(matcher.group(5));
        return new AtStackTraceLine(matcher.group(0), packageName, className, methodName, fileName, lineNumber);
    }

    /**
     * Parses a special 'at' stack trace line, e.g. one that refers to a native method or one that has no source code
     * information attached.
     *
     * @param matcher The matcher matching the raw 'at' input line.
     * @return Returns the parsed {@link AtStackTraceLine}.
     */
    private static AtStackTraceLine parseSpecialAtStackTraceLine(final Matcher matcher) {
        final String packageName = matcher.group(1);
        final String className = matcher.group(2);
        final String methodName = matcher.group(3);
        return new AtStackTraceLine(matcher.group(0), packageName, className, methodName);
    }

    /**
     * Parses a special 'at' stack trace line, e.g. one that refers to a native method or one that has no source code
     * information attached, but that exposes at least a line number.
     *
     * @param matcher The matcher matching the raw 'at' input line.
     * @return Returns the parsed {@link AtStackTraceLine}.
     */
    private static AtStackTraceLine parseSpecialAtStackTraceLineWithLine(final Matcher matcher) {
        final String packageName = matcher.group(1);
        final String className = matcher.group(2);
        final String methodName = matcher.group(3);
        final int lineNumber = Integer.parseInt(matcher.group(4));
        return new AtStackTraceLine(matcher.group(0), packageName, className, methodName, lineNumber);
    }

    /**
     * Parses an 'at' stack trace line that doesn't expose a line number.
     *
     * @param matcher The matcher matching the raw 'at' input line.
     * @return Returns the parsed {@link AtStackTraceLine}.
     */
    private static AtStackTraceLine parseAtStackTraceLineWithoutLineNumber(final Matcher matcher) {
        final String packageName = matcher.group(1);
        final String className = matcher.group(2);
        final String methodName = matcher.group(3);
        final String fileName = matcher.group(4);
        return new AtStackTraceLine(matcher.group(0), packageName, className, methodName, fileName);
    }

    /**
     * Parses a '... X more' stack trace line.
     *
     * @param matcher The matcher matching the raw '... X more' input line.
     * @return Returns the parsed {@link MoreStackTraceLine}.
     */
    private static MoreStackTraceLine parseMoreStackTraceLine(final Matcher matcher) {
        return new MoreStackTraceLine(Integer.parseInt(matcher.group(1)));
    }
}
