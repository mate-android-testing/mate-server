package org.mate.crash_reproduction;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Models a stack trace line that starts with the prefix 'at'.
 */
public class AtStackTraceLine implements StackTraceLine {

    // TODO: Why those methods are ignored? -- Should be probably all Android methods (no keywords from them)
    private final static Set<String> IGNORE_METHODS = Set.of("onOptionsItemSelected");

    /**
     * The complete stack trace line.
     */
    private final String line;

    /**
     * The package name the stack trace line refers to.
     */
    private final String packageName;

    /**
     * The class name the stack trace line refers to.
     */
    private final String className;

    /**
     * The method name the stack trace line refers to.
     */
    private final String methodName;

    /**
     * The file name, e.g. MyProject.java, from which the exception was thrown from.
     */
    private final String fileName;

    /**
     * The source code line number from which the exception was thrown from.
     */
    private final Integer lineNumber;

    /**
     * Initialises an 'at' stack trace line without the file name and source code line number information.
     *
     * @param line The complete stack trace line.
     * @param packageName The package name the stack trace line refers to.
     * @param className The class name the stack trace line refers to.
     * @param methodName The method name the stack trace line refers to.
     */
    public AtStackTraceLine(String line, String packageName, String className, String methodName) {
        this(line, packageName, className, methodName, null, null);
    }

    /**
     * Initialises an 'at' stack trace line without the file name information.
     *
     * @param line The complete stack trace line.
     * @param packageName The package name the stack trace line refers to.
     * @param className The class name the stack trace line refers to.
     * @param methodName The method name the stack trace line refers to.
     * @param lineNumber The source code line number from which the exception was thrown from.
     */
    public AtStackTraceLine(String line, String packageName, String className, String methodName, int lineNumber) {
        this(line, packageName, className, methodName, null, lineNumber);
    }

    /**
     * Initialises an 'at' stack trace line without the source code line number information.
     *
     * @param line The complete stack trace line.
     * @param packageName The package name the stack trace line refers to.
     * @param className The class name the stack trace line refers to.
     * @param methodName The method name the stack trace line refers to.
     * @param fileName The file name from which the exception was thrown from.
     */
    public AtStackTraceLine(String line, String packageName, String className, String methodName, String fileName) {
        this(line, packageName, className, methodName, fileName, null);
    }

    /**
     * Initialises an 'at' stack trace line.
     *
     * @param line The complete stack trace line.
     * @param packageName The package name the stack trace line refers to.
     * @param className The class name the stack trace line refers to.
     * @param methodName The method name the stack trace line refers to.
     * @param fileName The file name from which the exception was thrown from.
     * @param lineNumber The source code line number from which the exception was thrown from.
     */
    public AtStackTraceLine(String line, String packageName, String className, String methodName, String fileName,
                            Integer lineNumber) {
        this.line = line;
        this.packageName = packageName;
        this.className = className;
        this.methodName = methodName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    /**
     * Returns the package name encoded in the stack trace line.
     *
     * @return Returns the package name encoded in the stack trace line.
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Returns the class name encoded in the stack trace line.
     *
     * @return Returns the class name encoded in the stack trace line.
     */
    public String getClassName() {
        return className;
    }

    /**
     * Returns the method name encoded in the stack trace line.
     *
     * @return Returns the method name encoded in the stack trace line.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the optional file name encoded in the stack trace line.
     *
     * @return Returns the optional file name encoded in the stack trace line.
     */
    public Optional<String> getFileName() {
        return Optional.ofNullable(fileName);
    }

    /**
     * Returns the optional source code line number encoded in the stack trace line.
     *
     * @return Returns the optional source code line number encoded in the stack trace line.
     */
    public Optional<Integer> getLineNumber() {
        return Optional.ofNullable(lineNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFromPackage(String packageName) {
        return getPackageName().startsWith(packageName);
    }

    /**
     * Returns essentially the keywords for promising actions (see paper).
     *
     * @return Returns the relevant keywords for the online-phase.
     */
    @Override
    public Stream<String> getFuzzyTokens() {
        return Stream.concat(
                Arrays.stream(packageName.split("\\.")), // split on packages
                Stream.concat(
                        IGNORE_METHODS.contains(methodName) ? Stream.empty() : Stream.of(methodName), // take method name as token
                        Arrays.stream(className.split("\\$")) // split on inner classes
                ))
                .flatMap(TokenUtil::splitCamelCase) // split all tokens on camel case
                .map(String::toLowerCase); // TODO: Probably redundant, check MATE how it compares the keywords...
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return line;
    }
}
