package org.mate.crash_reproduction;

import java.util.Arrays;
import java.util.stream.Stream;

public class TokenUtil {
    public static Stream<String> splitCamelCase(String camelCase) {
        // Taken from https://stackoverflow.com/questions/7593969/regex-to-split-camelcase-or-titlecase-advanced/7594052#7594052
        String[] array = camelCase.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
        return Arrays.stream(array);
    }
}
