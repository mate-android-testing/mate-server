package org.mate.crash_reproduction;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
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
                Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()), "de.k3b.android.androFotoFinder"));
    }

    @Test
    public void testStackTrace2() {
        final File stackTraceFile = new File(RESOURCES, "stack_trace_2.txt");
        Assertions.assertDoesNotThrow(() -> StackTraceParser.parse(
                Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()), "com.ichi2.anki"));
    }

    @Test
    public void testStackTraceComparisonWithNBSP() throws IOException {
        final File stackTraceFile1 = new File(RESOURCES, "stack_trace_3.txt");
        final File stackTraceFile2 = new File(RESOURCES, "stack_trace_3_with_nbsp.txt");
        final StackTrace stackTrace1
                = StackTraceParser.parse(Files.lines(stackTraceFile1.toPath()).collect(Collectors.toList()),
                "com.fsck.k9");
        final StackTrace stackTrace2
                = StackTraceParser.parse(Files.lines(stackTraceFile2.toPath()).collect(Collectors.toList()),
                "com.fsck.k9");
        Assertions.assertEquals(stackTrace1, stackTrace2);
    }

    @Test
    public void testStackTraceComparisonTruncated() throws IOException {
        final File stackTraceFile1 = new File(RESOURCES, "stack_trace_4.txt");
        final File stackTraceFile2 = new File(RESOURCES, "stack_trace_4_truncated.txt");
        final StackTrace stackTrace1
                = StackTraceParser.parse(Files.lines(stackTraceFile1.toPath()).collect(Collectors.toList()),
                "com.fsck.k9");
        final StackTrace stackTrace2
                = StackTraceParser.parse(Files.lines(stackTraceFile2.toPath()).collect(Collectors.toList()),
                "com.fsck.k9");
        Assertions.assertEquals(stackTrace1, stackTrace2);
    }
}
