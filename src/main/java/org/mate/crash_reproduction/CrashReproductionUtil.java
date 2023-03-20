package org.mate.crash_reproduction;

import de.uni_passau.fim.auermich.android_graphs.core.app.components.Activity;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.utility.*;
import org.jf.dexlib2.ReferenceType;
import org.jf.dexlib2.builder.BuilderDebugItem;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.debug.LineNumber;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.util.MethodUtil;
import org.mate.graphs.CallTree;
import org.mate.graphs.InterCFG;
import org.mate.util.Log;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Provides mainly utility functions for crash reproduction.
 */
public final class CrashReproductionUtil {

    // TODO: Make it either a real utility class or a singleton!

    /**
     * The dex files of the AUT.
     */
    private final List<DexFile> dexFiles;

    /**
     * The call tree.
     */
    private final CallTree callTree;

    public CrashReproductionUtil(CallTree callTree) {
        this.callTree = callTree;
        this.dexFiles = callTree.getApk().getDexFiles();
    }

    /**
     * Retrieves the (inter-procedural) target vertices associated with the given stack trace line.
     *
     * @param line The given (at) stack trace line.
     * @param interCFG The inter-procedural CFG from which the target vertices are derived.
     * @return Returns the target vertices associated with the given stack trace line.
     */
    public Set<CFGVertex> getTargetVerticesForStackTraceLine(final AtStackTraceLine line, final InterCFG interCFG) {

        // Retrieve the method and bytecode instructions that refer to the source code line number of the stack trace line.
        var mappedMethodAndByteCodeInstructions = getInstructionsForLine(line).orElseThrow();

        // Map the bytecode instructions back to vertices in the inter-procedural CFG. Since we use basic blocks for the
        // interCFG, multiple (consecutive) instructions potentially map to the same vertex.
        return mappedMethodAndByteCodeInstructions.getY().stream()
                .map(instruction -> interCFG.findVertexByInstruction(mappedMethodAndByteCodeInstructions.getX(), instruction))
                .collect(Collectors.toSet());
    }

    /**
     * Retrieves the set of required constructors calls for the given stack trace line.
     *
     * @param line The given stack trace line.
     * @return Returns the set of required constructor calls.
     */
    public Set<String> getRequiredConstructorCalls(final AtStackTraceLine line) { // TODO: Make static if possible.
        return getInstructionsForLine(line)
                .stream()
                .flatMap(methodAndInstructions -> Stream.concat(
                        // Required constructors to reach the method containing the instruction
                        getRequiredConstructorCalls(methodAndInstructions.getX().toString()),
                        methodAndInstructions.getY().stream()
                                .flatMap(this::getRequiredConstructorCalls)
                ))
                .collect(Collectors.toSet());
    }

    // TODO: Understand and fix documentation.

    /**
     *
     * @param instruction
     * @return
     */
    private Stream<String> getRequiredConstructorCalls(final BuilderInstruction instruction) {

        // What is this instruction essentially, an invoke-instruction?
        if (instruction.getOpcode().referenceType == ReferenceType.METHOD) {
            final String methodName = ((ReferenceInstruction) instruction).getReference().toString();
            return getRequiredConstructorCalls(methodName);
        } else {
            // TODO: What does the below TODO mean?
            // TODO add more cases (e.g. when accessing a field)
            return Stream.empty();
        }
    }

    /**
     * Retrieves the required constructor calls to properly invoke the given target method, i.e. for each parameter of
     * the target method the class constructor needs to called. In addition, all constructors of the target method's
     * class are considered.
     *
     * @param methodName The method name of the target method.
     * @return Returns a stream of required constructor calls to properly invoke the target method.
     */
    private Stream<String> getRequiredConstructorCalls(final String methodName) {

        final Optional<Tuple<ClassDef, Method>> classMethodTuple = MethodUtils.searchForTargetMethod(dexFiles, methodName);

        if (classMethodTuple.isEmpty()) {
            Log.printWarning("Was not able to find method " + methodName);
            return Stream.empty();
        } else {
            final Method targetMethod = classMethodTuple.get().getY();
            final String className = MethodUtils.getClassName(methodName);
            final List<String> classConstructors = new ArrayList<>(ClassUtils.getConstructors(classMethodTuple.get().getX()));

            // TODO: Ignore constructors of primitive types + Android-specific constructors.

            /*
            * Retrieves the required constructors to properly call the target method, i.e. for each parameter (some class)
            * of the target method (which might be itself a constructor) the constructor of that class needs to invoked.
            * In addition, all constructors of the target method's class are considered or only the static constructor
            * if the target method is static.
             */
            return Stream.concat(
                    // This is an over approximation, since it's always possible to pass null as a value, thus not every
                    // parameter might be actually required to call the method.
                    targetMethod.getParameterTypes().stream().map(Objects::toString).flatMap(this::getConstructors),
                    // Add the static constructor if the target method is static, otherwise all class constructors.
                    MethodUtil.isStatic(targetMethod)
                            ? Stream.of(className + "-><clinit>()V")
                            : classConstructors.stream().map(Objects::toString)
            );
        }
    }

    /**
     * Retrieves all class (i.e. non-static) constructors of the given class.
     *
     * @param className The class for which we should look up the constructors.
     * @return Returns the constructors of the given class.
     */
    private Stream<String> getConstructors(final String className) {

        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                if (classDef.toString().equals(className)) {
                    return StreamSupport.stream(classDef.getMethods().spliterator(), false)
                            .filter(MethodUtil::isConstructor)
                            .map(Method::toString);
                }
            }
        }

        Log.printWarning("Was not able to find constructor for " + className);
        return Stream.empty(); // primitive classes like int aren't contained and shouldn't be resolved
    }

    /**
     * Traverses the stack trace from bottom to top and returns as many as possible consecutive lines that belong to
     * the given package. This may skip the first few lines if they don't belong to the given package.
     *
     * @param stackTrace The given stack trace (lines).
     * @param packageName The given package name.
     * @return Returns the stack trace lines that belong to the given package and are in consecutive order.
     */
    public List<AtStackTraceLine> getLastConsecutiveLines(final List<AtStackTraceLine> stackTrace, final String packageName) {

        final List<AtStackTraceLine> stackTraceLines = new LinkedList<>();
        boolean reachedPackage = false;

        for (int i = stackTrace.size() - 1; i >= 0; i--) {
            var stackTraceLine = stackTrace.get(i);
            if (stackTraceLine.isFromPackage(packageName)) {
                reachedPackage = true;
                stackTraceLines.add(0, stackTraceLine);
            } else if (reachedPackage) {
                return stackTraceLines;
            }
        }

        return stackTraceLines;
    }

    /**
     * Retrieves the relevant tokens from stack trace lines corresponding to the given package name.
     *
     * @param stackTrace The given stack trace.
     * @param packageName The given package name.
     * @return Returns the relevant tokens from the stack trace.
     */
    public Stream<String> getTokensForStackTrace(final StackTrace stackTrace, final String packageName) {
        return stackTrace.getStackTraceAtLines()
                .filter(l -> l.isFromPackage(packageName))
                .filter(line -> line.getFileName().isPresent() && line.getLineNumber().isPresent())
                .flatMap(this::getTokensFromStackTraceLine);
    }

    /**
     * Retrieves the method and the instructions that refer to the given stack trace line if possible. This requires that
     * the stack trace line contains a line number as well as the bytecode instructions contain debug information about
     * the source code line numbers.
     *
     * @param stackTraceLine The given stack trace line.
     * @return Returns the method and instructions that map to the line number of the given stack trace line if possible.
     */
    public Optional<Tuple<Method, Set<BuilderInstruction>>> getInstructionsForLine(final AtStackTraceLine stackTraceLine) {
        return getInstructionsForLine(dexFiles, stackTraceLine);
    }

    // TODO: Make method private or directly call it and remove above method.

    /**
     * Retrieves the method and the instructions that refer to the given stack trace line if possible.
     *
     * @param dexFiles The dex files containing the bytecode instructions.
     * @param stackTraceLine The given stack trace line.
     * @return Returns the method and instructions that map to the line number of the given stack trace line if possible.
     */
    public static Optional<Tuple<Method, Set<BuilderInstruction>>> getInstructionsForLine(final Collection<DexFile> dexFiles,
                                                                                          final AtStackTraceLine stackTraceLine) {

        // If the stack trace line doesn't contain a line number, we can't map it to any bytecode instructions.
        if (stackTraceLine.getLineNumber().isEmpty()) {
            return Optional.empty();
        }

        final String fileName = stackTraceLine.getFileName().orElse(stackTraceLine.getClassName() + ".java");
        final String dottedClassName = stackTraceLine.getPackageName() + "." + stackTraceLine.getClassName();

        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                if (fileName.equals(classDef.getSourceFile())
                        || ClassUtils.dottedClassName(classDef.toString()).equals(dottedClassName)) {
                    for (Method method : classDef.getMethods()) {
                        if (method.toString().contains(stackTraceLine.getMethodName()) && method.getImplementation() != null) {

                            final Set<BuilderInstruction> instructionsAtLine = new HashSet<>();

                            final MutableMethodImplementation mutableMethodImplementation
                                    = new MutableMethodImplementation(method.getImplementation());
                            final List<BuilderInstruction> instructions = mutableMethodImplementation.getInstructions();

                            /*
                             * Retrieve the line number from the debug items and check whether they match the line number
                             * of the given stack trace line.
                             */
                            for (BuilderInstruction instruction : instructions) {
                                // TODO: The line number is only attached to the first bytecode instruction, but all
                                //  subsequent instructions up to the next line number also refer to the same line.
                                if (getLineNumber(instruction.getLocation().getDebugItems())
                                        .map(lineNumber -> lineNumber.equals(stackTraceLine.getLineNumber().get()))
                                        .orElse(false)) {
                                    instructionsAtLine.add(instruction);
                                }
                            }

                            if (!instructionsAtLine.isEmpty()) {
                                return Optional.of(new Tuple<>(method, instructionsAtLine));
                            }
                        }
                    }
                }
            }
        }

        return Optional.empty();
    }

    // TODO: Understand and document.
    public Stream<String> getTokensFromStackTraceLine(AtStackTraceLine stackTraceLine) {
        var result = getInstructionsForLine(stackTraceLine).orElseThrow();
        return result.getY().stream()
                .flatMap(instruction -> Stream.concat(
                        CrashReproductionUtil.getTokensFromInstruction(instruction),
                        getMenuItemFromLine(result.getX(), instruction).stream()
                                .map(MenuItemWithResolvedTitle::getTitle)
                ));
    }

    // TODO: Understand and document.
    private Optional<MenuItemWithResolvedTitle> getMenuItemFromLine(Method method, BuilderInstruction instruction) {
        return callTree.getComponentByNameAndType(MethodUtils.getClassName(method.toString()), ComponentType.ACTIVITY)
                .flatMap(c -> {
                    if (c instanceof Activity) {
                        Map<Method, List<MenuItemWithResolvedTitle>> menus = ((Activity) c).getMenus();

                        // need to resolve on create menu method
                        return Optional.ofNullable(MenuUtils.ITEM_SELECT_METHOD_TO_ON_CREATE_MENU.get(MethodUtils.getMethodName(method)))
                                .map(onCreateMenuMethod -> MethodUtils.getClassName(method.toString()) + "->" + onCreateMenuMethod)
                                .flatMap(fullyQualifiedOnCreateMenuMethod -> menus.entrySet().stream().filter(e -> e.getKey().toString().equals(fullyQualifiedOnCreateMenuMethod)).findAny())
                                .map(Map.Entry::getValue);
                    }
                    return Optional.empty();
                })
                .flatMap(menuItems -> {
                    Optional<String> menuItemId = MenuUtils.getMenuItemStringId(instruction, method, dexFiles);

                    return menuItemId.flatMap(id -> menuItems.stream().filter(item -> item.getId().equals(id)).findAny());
                });
    }

    // TODO: Understand and document.
    private static Stream<String> getTokensFromInstruction(final Instruction instruction) {

        if (instruction instanceof ReferenceInstruction) {
            ReferenceInstruction referenceInstruction = (ReferenceInstruction) instruction;

            if (referenceInstruction.getReferenceType() == ReferenceType.STRING) {
                return Arrays.stream(referenceInstruction.getReference().toString().split("[-_\\s]"));
            } else if (referenceInstruction.getReferenceType() == ReferenceType.METHOD) {
                // TODO: Here we have again some ignore cases...
                final Set<String> ignoreMethods = Set.of("<init>", "doInBackground");
                // TODO: There is a utility function for this.
                final String methodName = MethodUtils.getMethodName(referenceInstruction.getReference().toString())
                        .split("\\(")[0];
                return ignoreMethods.contains(methodName) ? Stream.empty() : TokenUtil.splitCamelCase(methodName)
                        .map(String::toLowerCase)
                        .filter(method -> !ignoreMethods.contains(method));
            }
        }
        return Stream.empty();
    }

    /**
     * Returns the optional line number from the debug items if present.
     *
     * @param debugItems The debug items attached to an instruction.
     * @return Returns the (source code) line number if present in the debug items.
     */
    private static Optional<Integer> getLineNumber(final Set<BuilderDebugItem> debugItems) {
        return debugItems.stream().map(a -> {
            if (a instanceof LineNumber) {
                return Optional.of((LineNumber) a);
            } else {
                return Optional.<LineNumber>empty();
            }
        }).flatMap(Optional::stream)
                .findAny().map(LineNumber::getLineNumber);
    }
}
