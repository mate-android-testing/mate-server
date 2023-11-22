package org.mate.crash_reproduction;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mate.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.util.stream.Collectors;

public class StackTraceParserTest {

    private static final File RESOURCES = new File("./src/test/java/resources/");

    @BeforeAll
    public static void setup() {
        Log.registerLogger(new Log()); // Required for the logger invocations.
    }

    @Test
    public void testStackTrace1() {
        final File stackTraceFile = new File(RESOURCES, "stack_trace_1.txt");
        Assertions.assertDoesNotThrow(() -> StackTraceParser.parse(
                Files.lines(stackTraceFile.toPath()).collect(Collectors.toList())));
    }

    @Test
    public void testStackTrace2() {
        final File stackTraceFile = new File(RESOURCES, "stack_trace_2.txt");
        Assertions.assertDoesNotThrow(() -> StackTraceParser.parse(
                Files.lines(stackTraceFile.toPath()).collect(Collectors.toList())));
    }
}
