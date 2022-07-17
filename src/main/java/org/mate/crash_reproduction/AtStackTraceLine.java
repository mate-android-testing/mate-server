package org.mate.crash_reproduction;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

public class AtStackTraceLine implements StackTraceLine {
    private final String line;
    private final String packageName;
    private final String className;
    private final String methodName;
    private final String fileName;
    private final Integer lineNumber;

    public AtStackTraceLine(String line, String packageName, String className, String methodName) {
        this(line, packageName, className, methodName, null, null);
    }

    public AtStackTraceLine(String line, String packageName, String className, String methodName, String fileName) {
        this(line, packageName, className, methodName, fileName, null);
    }

    public AtStackTraceLine(String line, String packageName, String className, String methodName, String fileName, Integer lineNumber) {
        this.line = line;
        this.packageName = packageName;
        this.className = className;
        this.methodName = methodName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public Optional<String> getFileName() {
        return Optional.ofNullable(fileName);
    }

    public Optional<Integer> getLineNumber() {
        return Optional.ofNullable(lineNumber);
    }

    @Override
    public boolean isFromPackage(String string) {
        return getPackageName().startsWith(string);
    }

    @Override
    public Stream<String> getFuzzyTokens() {
        return Stream.concat(
                Arrays.stream(packageName.split("\\.")),
                Stream.concat(
                        Stream.of(methodName),
                        Arrays.stream(className.split("\\$"))
                ))
                .flatMap(this::splitCamelCase)
                .map(String::toLowerCase);
    }

    private Stream<String> splitCamelCase(String camelCase) {
        // Taken from https://stackoverflow.com/questions/7593969/regex-to-split-camelcase-or-titlecase-advanced/7594052#7594052
        String[] array = camelCase.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
        return Arrays.stream(array);
    }

    @Override
    public String toString() {
        return line;
    }
}
