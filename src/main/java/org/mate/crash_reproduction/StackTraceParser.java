package org.mate.crash_reproduction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class StackTraceParser {
    private static final Map<Pattern, Function<Matcher, ? extends StackTraceLine>> STACK_TRACE_LINE_PATTERNS = new HashMap<>();
    static {
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("^Caused by: (\\S+): (.+)"), StackTraceParser::parseCausedByLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("^(\\S+): (.+)$"), StackTraceParser::parseCausedByLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\((.+):(\\d+)\\)"), StackTraceParser::parseNormalStackTraceLine);
        STACK_TRACE_LINE_PATTERNS.put(Pattern.compile("at (\\S+)\\.(\\S+)\\.(\\S+)\\(Native Method\\)"), StackTraceParser::parseOtherStackTraceLine);
    }

    private StackTraceParser() {
        throw new UnsupportedOperationException("Cannot initialize utility class");
    }

    public static StackTrace parse(List<String> lines) {
        List<StackTraceLine> parsedLines = lines.stream()
                .map(StackTraceParser::parseLine)
                .collect(Collectors.toList());
        return new StackTrace(parsedLines);
    }

    private static StackTraceLine parseLine(String line) {
        for (var entry : STACK_TRACE_LINE_PATTERNS.entrySet()) {
            Matcher matcher = entry.getKey().matcher(line);

            if (matcher.matches()) {
                return entry.getValue().apply(matcher);
            }
        }

        throw new IllegalArgumentException("Cannot parse '" + line + "'");
    }

    private static CausedByStackTraceLine parseCausedByLine(Matcher matcher) {
        String exception = matcher.group(1);
        String message = matcher.group(2);

        return new CausedByStackTraceLine(matcher.group(0), exception, message);
    }

    private static AtStackTraceLine parseNormalStackTraceLine(Matcher matcher) {
        String packageName = matcher.group(1);
        String className = matcher.group(2);
        String methodName = matcher.group(3);
        String fileName = matcher.group(4);
        int lineNumber = Integer.parseInt(matcher.group(5));

        return new AtStackTraceLine(matcher.group(0), packageName, className, methodName, fileName, lineNumber);
    }

    private static AtStackTraceLine parseOtherStackTraceLine(Matcher matcher) {
        String packageName = matcher.group(1);
        String className = matcher.group(2);
        String methodName = matcher.group(3);

        return new AtStackTraceLine(matcher.group(0), packageName, className, methodName);
    }
}
