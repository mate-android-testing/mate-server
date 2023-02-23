package org.mate.crash_reproduction;

import java.util.Set;

// TODO: Remove this class if not needed.
public final class StackTraceAnalyzer {

    private static final Set<String> ANDROID_TOKENS = Set.of(
            "android", "app", "activity", "fragment", "list", "dialog"
    );
}
