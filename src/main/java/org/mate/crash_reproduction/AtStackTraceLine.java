package org.mate.crash_reproduction;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class AtStackTraceLine implements StackTraceLine {
    private final static Set<String> IGNORE_METHODS = Set.of(
            "onOptionsItemSelected"
    );
    private final String line;
    private final String packageName;
    private final String className;
    private final String methodName;
    private final String fileName;
    private final Integer lineNumber;

    public AtStackTraceLine(String line, String packageName, String className, String methodName) {
        this(line, packageName, className, methodName, null, null);
    }

    public AtStackTraceLine(String line, String packageName, String className, String methodName, int lineNumber) {
        this(line, packageName, className, methodName, null, lineNumber);
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
                        IGNORE_METHODS.contains(methodName) ? Stream.empty() : Stream.of(methodName),
                        Arrays.stream(className.split("\\$"))
                ))
                .flatMap(TokenUtil::splitCamelCase)
                .map(String::toLowerCase);
    }

    @Override
    public String toString() {
        return line;
    }
}
