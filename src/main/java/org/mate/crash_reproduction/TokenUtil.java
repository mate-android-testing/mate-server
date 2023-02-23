package org.mate.crash_reproduction;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Provides a set of utility functions for tokens.
 */
public final class TokenUtil {

    private TokenUtil() {
        throw new UnsupportedOperationException("Cannot instantiate utility class!");
    }

    /**
     * Splits the given word at each camel case.
     *
     * @param camelCase The given camel case word.
     * @return Returns the camel case tokens.
     */
    public static Stream<String> splitCamelCase(String camelCase) {
        // Taken from https://stackoverflow.com/questions/7593969/regex-to-split-camelcase-or-titlecase-advanced/7594052#7594052
        final String[] array = camelCase.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
        return Arrays.stream(array);
    }
}
