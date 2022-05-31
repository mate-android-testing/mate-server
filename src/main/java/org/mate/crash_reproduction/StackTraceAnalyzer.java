package org.mate.crash_reproduction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class StackTraceAnalyzer {
    private static final Set<String> ANDROID_TOKENS = Set.of(
            "android", "app", "activity", "fragment", "list", "dialog"
    );


}
