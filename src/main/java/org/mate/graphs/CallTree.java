package org.mate.graphs;

import com.android.tools.smali.dexlib2.ReferenceType;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.util.MethodUtil;
import de.uni_passau.fim.auermich.android_graphs.core.app.APK;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.Activity;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.Component;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.utility.*;
import org.jgrapht.alg.interfaces.ManyToManyShortestPathsAlgorithm;
import org.mate.crash_reproduction.*;
import org.mate.graphs.util.Util;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Represents a call tree where vertices represent methods and edges describe invocations from one method to the other.
 */
public class CallTree implements Graph<CallTreeVertex, CallTreeEdge> {

    /**
     * The employed shortest path algorithm. For individual vertices the bi-directional dijkstra seems to be the fastest
     * option, while for resolving the shortest paths between many vertices, the CH many-to-many shortest path algorithm
     * appears to be the best option.
     */
    private final ManyToManyShortestPathsAlgorithm<CallTreeVertex, CallTreeEdge> shortestPathAlgorithm;

    /**
     * The interCFG from which the call tree is finally derived.
     */
    private final InterCFG interCFG;

    /**
     * The core call tree.
     */
    private final de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTree callTree;

    /**
     * The set of discovered components, e.g. activities.
     */
    private final Set<Component> components;

    /**
     * The APK file.
     */
    private final APK apk;

    /**
     * Stores for each stack trace line detailed information.
     */
    private final Map<AtStackTraceLine, AnalyzedStackTraceLine> analyzedStackTraceLines;

    /**
     * The stack trace used for crash reproduction.
     */
    private final StackTrace stackTrace;

    /**
     * The list of target call tree vertices.
     */
    private List<CallTreeVertex> callTreeVertices;

    /**
     * The set of required constructor cals.
     */
    private final Set<String> requiredConstructors;

    /**
     * Constructs a new call tree with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to only resolve classes belonging to the AUT package.
     * @param appsDir The path to the apps dir.
     * @param packageName The package name of the AUT.
     * @param stackTracePath The path to the stack trace file.
     */
    public CallTree(File apkPath, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName, String stackTracePath) {
        this.callTree = GraphUtils.constructCallTree(apkPath, excludeARTClasses, resolveOnlyAUTClasses);
        this.interCFG = new InterCFG(callTree.getInterCFG(), appsDir, packageName);
        this.shortestPathAlgorithm = callTree.initCHManyToManyShortestPathAlgorithm();
        this.components = callTree.getInterCFG().getComponents();
        this.apk = callTree.getInterCFG().getApk();
        this.stackTrace = loadStackTrace(appsDir, packageName, stackTracePath);
        this.analyzedStackTraceLines = analyzeStackTrace(appsDir, packageName);
        this.requiredConstructors = analyzeRequiredConstructors();
    }

    /**
     * Determines which constructors are required to be covered.
     *
     * @return Returns the set of required constructor calls.
     */
    private Set<String> analyzeRequiredConstructors() {
        return analyzedStackTraceLines.values().stream()
                .map(AnalyzedStackTraceLine::getRequiredConstructorCalls)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    /**
     * Loads the stack trace from the specified location.
     *
     * @param appsDir The path to the apps folder.
     * @param packageName The package name of the AUT.
     * @param stackTracePath The stack trace file.
     * @return Returns the stack trace contained in the given stack trace file.
     */
    private StackTrace loadStackTrace(final Path appsDir, final String packageName, final String stackTracePath) {

        final File appDir = new File(appsDir.toFile(), packageName);

        // the stack_trace.txt should be located within the app directory
        final File stackTraceFile = new File(appDir, stackTracePath);

        if (!stackTraceFile.exists()) {
            throw new IllegalArgumentException("Stack trace file does not exist at: " + stackTraceFile.getAbsolutePath());
        }

        try {
            return StackTraceParser.parse(Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()));
        } catch (IOException e) {
            Log.printError("Could not read stack trace file from '" + stackTraceFile.getAbsolutePath() + "'!");
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the stack trace.
     *
     * @return Returns the stack trace.
     */
    public StackTrace getStackTrace() {
        return stackTrace;
    }

    /**
     * Analyzes every single stack trace line belonging to the AUT.
     *
     * @param appsDir The path to the apps dir.
     * @param packageName The package name of the AUT.
     * @return Returns a mapping from an 'at' stack trace line to an analyzed stack trace line.
     */
    private Map<AtStackTraceLine, AnalyzedStackTraceLine> analyzeStackTrace(final Path appsDir, final String packageName) {

        // Analyse every 'at' stack trace line that belongs to the given package and comes in consecutive order.
        return getLastConsecutiveLines(stackTrace.getStackTraceAtLines()
                .collect(Collectors.toList()), packageName).stream()
                .collect(Collectors.toMap(Function.identity(), line -> {

                    // Retrieve the inter-procedural CFG vertices that are mapped to the given stack trace line.
                    final Set<CFGVertex> targetInterCFGVertices = getTargetVerticesForStackTraceLine(line);

                    // TODO: Retrieve the target method name directly from the method name encoded in the stack trace line.
                    final String targetMethod = Util.expectOne(targetInterCFGVertices.stream()
                            .map(CFGVertex::getMethod)
                            .collect(Collectors.toSet()));

                    // create the intraCFG matching the target method (method encoded in the stack trace line)
                    final IntraCFG intraCFG = new IntraCFG(apk.getApkFile(), targetMethod, true, appsDir, packageName);

                    // TODO: Remove once we can assure that those vertices are identical to the interTargetVertices!
                    final Set<CFGVertex> targetIntraCFGVertices = targetInterCFGVertices.stream()
                            .flatMap(interVertex -> Util.tracesForStatement(interVertex.getStatement()))
                            .map(intraCFG::lookupVertex)
                            .collect(Collectors.toSet());

                    if (!targetInterCFGVertices.equals(targetIntraCFGVertices)) {
                        Log.println("Not same set of vertices!");
                        Log.println("InterCFG vertices: " + targetInterCFGVertices);
                        Log.println("IntraCFG vertices: " + targetIntraCFGVertices);
                    }

                    // Retrieves the required constructors to properly call the target method in the stack trace line.
                    final var requiredConstructorCalls = getRequiredConstructorCalls(line);

                    return new AnalyzedStackTraceLine(targetInterCFGVertices, intraCFG,
                            targetIntraCFGVertices, requiredConstructorCalls);
                }));
    }

    /**
     * Initialises the target vertices for crash reproduction.
     *
     * @return Returns the target vertices for crash reproduction.
     */
    public List<CFGVertex> getTargetVertices() {

        // Retrieve the target vertices from the stack trace lines.
        final List<CFGVertex> targetInterCFGVertices = stackTrace.getStackTraceAtLines()
                .filter(analyzedStackTraceLines::containsKey)
                .map(analyzedStackTraceLines::get)
                .map(AnalyzedStackTraceLine::getInterCFGVertices)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        // At least a single line (target) in the stack trace must refer to the AUT.
        if (targetInterCFGVertices.isEmpty()) {
            throw new IllegalStateException("No targets found for stack trace!");
        }

        // Map the interCFG vertices to the callTree vertices.
        callTreeVertices = targetInterCFGVertices.stream()
                .map(CFGVertex::getMethod)
                .map(CallTreeVertex::new)
                .collect(Collectors.toList());

        // TODO: Why do we reverse the list?
        Collections.reverse(callTreeVertices);

        // The target vertices must be reachable in the call tree.
        if (callTree.getShortestPathWithStops(callTreeVertices).isEmpty()) {
            throw new IllegalStateException("No path from root to target vertices!");
        }

        return targetInterCFGVertices;
    }

    /**
     * Converts the call tree to a dot file.
     *
     * @param output The path to the dot file.
     * @param methodsToHighlight The methods (call tree vertices) that should be highlighted.
     */
    public void toDot(final File output, final Map<String, String> methodsToHighlight) {
        callTree.toDot(output, methodsToHighlight);
    }

    /**
     * Looks up a component by name and type.
     *
     * @param componentName The component name.
     * @param componentType The component type.
     * @return Returns the component if matching name and type.
     */
    private Optional<Component> getComponentByNameAndType(final String componentName, final ComponentType componentType) {

        final Optional<Component> component = ComponentUtils.getComponentByName(components, componentName);

        if (component.isPresent() && component.get().getComponentType() == componentType) {
            return component;
        } else {
            return Optional.empty();
        }
    }

    /**
     * Retrieves the (inter-procedural) target vertices associated with the given stack trace line.
     *
     * @param line The given (at) stack trace line.
     * @return Returns the target vertices associated with the given stack trace line.
     */
    private Set<CFGVertex> getTargetVerticesForStackTraceLine(final AtStackTraceLine line) {

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
    private Set<String> getRequiredConstructorCalls(final AtStackTraceLine line) {
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

        final Optional<Tuple<ClassDef, Method>> classMethodTuple
                = MethodUtils.searchForTargetMethod(apk.getDexFiles(), methodName);

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

        for (DexFile dexFile : apk.getDexFiles()) {
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
    private List<AtStackTraceLine> getLastConsecutiveLines(final List<AtStackTraceLine> stackTrace, final String packageName) {

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
    private Optional<Tuple<Method, Set<BuilderInstruction>>> getInstructionsForLine(final AtStackTraceLine stackTraceLine) {

        // If the stack trace line doesn't contain a line number, we can't map it to any bytecode instructions.
        if (stackTraceLine.getLineNumber().isEmpty()) {
            return Optional.empty();
        }

        final String fileName = stackTraceLine.getFileName().orElse(stackTraceLine.getClassName() + ".java");
        final String dottedClassName = stackTraceLine.getPackageName() + "." + stackTraceLine.getClassName();

        for (DexFile dexFile : apk.getDexFiles()) {
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
                                if (Util.getLineNumber(instruction.getLocation().getDebugItems())
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
    private Stream<String> getTokensFromStackTraceLine(final AtStackTraceLine stackTraceLine) {
        var result = getInstructionsForLine(stackTraceLine).orElseThrow();
        return result.getY().stream()
                .flatMap(instruction -> Stream.concat(
                        getTokensFromInstruction(instruction),
                        getMenuItemFromLine(result.getX(), instruction).stream()
                                .map(MenuItemWithResolvedTitle::getTitle)
                ));
    }

    // TODO: Understand and document.
    private Optional<MenuItemWithResolvedTitle> getMenuItemFromLine(final Method method, final BuilderInstruction instruction) {
        return getComponentByNameAndType(MethodUtils.getClassName(method.toString()), ComponentType.ACTIVITY)
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
                    Optional<String> menuItemId = MenuUtils.getMenuItemStringId(instruction, method, apk.getDexFiles());

                    return menuItemId.flatMap(id -> menuItems.stream().filter(item -> item.getId().equals(id)).findAny());
                });
    }

    // TODO: Understand and document.
    private Stream<String> getTokensFromInstruction(final Instruction instruction) {

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
     * Computes a mapping that describes which stack trace line (target method) has been covered by the given traces.
     *
     * @param traces The given traces.
     * @return Returns a mapping that tracks which target method (stack trace line) has been covered by the traces.
     */
    private Map<AtStackTraceLine, Boolean> reachedTargetMethods(final Set<String> traces) {

        final Set<String> reachedMethods = traces.stream().map(Util::traceToMethod).collect(Collectors.toSet());

        final Map<AtStackTraceLine, Boolean> reachedTargetMethods = analyzedStackTraceLines.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, stackTraceLine -> {
                    final String method = Util.expectOne(stackTraceLine.getValue().getInterCFGVertices().stream()
                            .map(CFGVertex::getMethod)
                            .collect(Collectors.toSet()));
                    return reachedMethods.contains(method);
                }));
        onlyAllowCoveredIfPredecessorCoveredAsWell(reachedTargetMethods);
        return reachedTargetMethods;
    }

    // TODO: Need help here for understanding!
    private void onlyAllowCoveredIfPredecessorCoveredAsWell(final Map<AtStackTraceLine, Boolean> map) {
        // We are only interested in a covered method if its predecessor from the stack trace was reached as well
        // TODO Does not consider the following case:
        // Stack trace from crash we are trying to reproduce:
        // at com.example.Class2.method2()
        // at com.example.Class1.method1()
        //
        // Traces
        // - com.example.Class2.method2() covered
        // - com.example.Class1.method1() covered
        //
        // Result
        // - com.example.Class1.method1() will be marked as reached -> fine
        // - com.example.Class2.method2() will be marked as reached
        //      -> Case: method2 is called by method3
        //      -> should ideally not be marked as reached (since it was not called by method1)

        var orderedEntries = stackTrace.getStackTraceAtLines()
                .filter(map::containsKey)
                .map(line -> map.entrySet().stream().filter(e -> e.getKey().equals(line)).findAny())
                .map(Optional::orElseThrow)
                .collect(Collectors.toList());
        Collections.reverse(orderedEntries);

        Iterator<Map.Entry<AtStackTraceLine, Boolean>> coveredStackTraceLineIterator = orderedEntries.listIterator();

        while (coveredStackTraceLineIterator.hasNext() && coveredStackTraceLineIterator.next().getValue()) {
            // Run from bottom to top of stack trace lines until an uncovered line is reached
        }

        // Set remaining lines to not covered, since predecessor is also not covered
        while (coveredStackTraceLineIterator.hasNext()) {
            coveredStackTraceLineIterator.next().setValue(false);
        }
    }

    /**
     * Retrieves the minimal basic block distance (approach level) between the given traces and the target method
     * contained in the stack trace.
     *
     * @param traces The set of traces.
     * @param stackTraceLine The stack trace line containing the target method.
     * @return Returns the minimal basic block distance between the traces and the target method.
     */
    private int getBasicBlockDistance(final Set<String> traces, final AtStackTraceLine stackTraceLine) {

        // retrieve the intra CFG corresponding to the given stack trace line
        final var analyzedStackTraceLine = analyzedStackTraceLines.get(stackTraceLine);
        final IntraCFG intraCFG = analyzedStackTraceLine.getIntraCFG();

        final String targetMethod = analyzedStackTraceLine.getIntraCFGVertices().stream()
                .findAny().orElseThrow().getMethod();

        int minDistance = Integer.MAX_VALUE;

        for (String trace : traces) {
            if (Util.traceToMethod(trace).equals(targetMethod)) {
                int distance = analyzedStackTraceLine.getIntraCFGVertices().stream()
                        // TODO: Employ a cache for the distances!
                        .map(targetVertex -> intraCFG.getDistance(intraCFG.lookupVertex(trace), targetVertex))
                        .map(dist -> dist == -1 ? Integer.MAX_VALUE : dist) // -1 means not reachable
                        .min(Integer::compare)
                        .orElseThrow();

                if (distance < minDistance) {
                    minDistance = distance;
                }
            }
        }

        return minDistance;
    }

    /**
     * Computes the normalized basic block distance between the given traces and the target methods described by the
     * stack trace.
     *
     * @param tracesPerFile The given traces per file. One file essentially represents the traces of a single action.
     * @return Returns a mapping that describes for each stack trace line the normalized basic block distance.
     */
    private Map<AtStackTraceLine, Double> getNormalizedBasicBlockDistances(final List<Set<String>> tracesPerFile) {

        // Look for the traces that reached most target methods.
        final var bestTraces = tracesPerFile.stream()
                .map(traces -> new Tuple<>(traces, reachedTargetMethods(traces)))
                .max(Comparator.comparingLong(tuple -> tuple.getY().values().stream().filter(b -> b).count()))
                .orElseThrow();

        return bestTraces.getY().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    final int distance = e.getValue()
                            // only need to compute distance if we reached the target method (stack trace line)
                            ? getBasicBlockDistance(bestTraces.getX(), e.getKey())
                            : Integer.MAX_VALUE;

                    // normalize distance in [0,1]
                    return distance == Integer.MAX_VALUE
                            ? 1D
                            : (double) distance / ((double) distance + 1);
                }));
    }

    /**
     * Retrieves the normalized (average) basic block distance between the traces and the target methods.
     *
     * @param chromosome The chromosome for which the basic block distance should be derived.
     * @param tracesPerFile The traces per file (action).
     * @return Returns the normalized basic block distance for the given chromosome.
     */
    private double getBasicBlockDistance(final String chromosome, final List<Set<String>> tracesPerFile) {

        Log.println("Computing the call tree distance for the chromosome: " + chromosome);

        final Map<AtStackTraceLine, Double> basicBlockDistances = getNormalizedBasicBlockDistances(tracesPerFile);

        // computes the average basic block distance
        double sum = basicBlockDistances.values().stream().mapToDouble(d -> d).sum();
        double averageBasicBlockDistance = sum / basicBlockDistances.size();

        Log.println("Basic block distance for " + chromosome + " is: " + averageBasicBlockDistance);

        return averageBasicBlockDistance;
    }

    /**
     * Retrieves the number (percentage) of reached constructors for the given chromosome.
     *
     * @param chromosome The chromosome for which the number of reached constructors should be derived.
     * @param traces The traces for the given chromosome.
     * @return Returns the number of reached constructors for the given chromosome.
     */
    private double getNumberOfReachedConstructors(final String chromosome, final Set<String> traces) {

        Log.println("Computing number of reached constructors for the chromosome: " + chromosome);

        // track which methods have been visited by the traces
        final Set<String> reachedMethods = traces.stream().map(Util::traceToMethod).collect(Collectors.toSet());

        // count how many constructors have been reached
        double reachedConstructors = requiredConstructors.stream().filter(reachedMethods::contains).count();

        // normalize in the range [0,1]
        double normalisedNumberOfReachedConstructors = requiredConstructors.size() == 0
                ? 1
                : reachedConstructors / requiredConstructors.size();

        Log.println("Number of reached constructors for " + chromosome + " is: " + normalisedNumberOfReachedConstructors);

        return normalisedNumberOfReachedConstructors;
    }

    /**
     * Retrieves the normalized call tree distance for the given chromosome.
     *
     * @param chromosome The chromosome for which the call tree distance should be derived.
     * @param tracesPerFile The traces per file (action).
     * @return Returns the normalized call tree distance for the given chromosome.
     */
    private double getCallTreeDistance(final String chromosome, final List<Set<String>> tracesPerFile) {

        Log.println("Computing the call tree distance for the chromosome: " + chromosome);

        // We don't want to mix the traces of different actions, since our target action should produce all traces
        // necessary to cover the stack trace methods.
        // If we mix the traces then it's possible that we get a call tree distance of zero even if the target methods
        // are called from different actions
        // (and never just by one action). Then we have technically reached all target methods, but not in the right sequence
        double callTreeDistance = tracesPerFile.stream()
                .map(traces -> traces.stream().map(Util::traceToMethod).collect(Collectors.toSet()))
                .mapToInt(this::getCallTreeDistance)
                .min().orElseThrow();

        double normalizedCallTreeDistance = callTreeDistance == Integer.MAX_VALUE
                ? 1
                : callTreeDistance / (callTreeDistance + 1);

        Log.println("Call tree distance for " + chromosome + " is: abs. distance " + callTreeDistance
                + ", rel. distance " + normalizedCallTreeDistance);

        return normalizedCallTreeDistance;
    }

    /**
     * Computes the call tree distance between the target vertices and the given traces.
     *
     * @param traces The given traces.
     * @return Returns the call tree distance.
     */
    private int getCallTreeDistance(final Set<String> traces) {

        // the call tree vertices describing the stack trace in reversed order (operate on copy since being modified)
        final List<CallTreeVertex> callTreeVertices = new ArrayList<>(this.callTreeVertices);

        Optional<CallTreeVertex> lastCoveredVertex = Optional.empty();

        while (!callTreeVertices.isEmpty() && traces.contains(callTreeVertices.get(0).getMethod())) {
            // remove target vertices that we have already covered
            lastCoveredVertex = Optional.of(callTreeVertices.remove(0));
        }

        if (callTreeVertices.isEmpty()) {
            // We have already reached all targets, thus a distance of 0.
            return 0;
        } else if (lastCoveredVertex.isPresent()) {
            // We partially covered the targets, thus the distance is defined as the minimal path length from the last
            // covered vertex through the remaining targets.
            return callTree.getShortestPathWithStops(lastCoveredVertex.get(), callTreeVertices).orElseThrow().getLength();
        } else {
            // TODO: Computing the minimal path between every single trace and the targets can be expensive. Track it
            //  or compute the distance in advance. Alternatively, use a different metric in this case.
            // We have not found any targets yet, thus the distance is defined as the minimal path length from a trace
            // through the targets.
            int minDistance = Integer.MAX_VALUE;

            for (String trace : traces) {
                var path
                        = callTree.getShortestPathWithStops(new CallTreeVertex(trace), callTreeVertices);
                if (path.isPresent()) {
                    final int distance = path.get().getLength();

                    if (distance < minDistance) {
                        minDistance = distance;
                    }
                }
            }
            return minDistance;
        }
    }

    /**
     * Computes the crash distance for the given chromosome.
     *
     * @param chromosome The chromosome for which the crash distance should be computed.
     * @param tracesPerFile The traces per file.
     * @param traces The set of traces.
     * @return Returns the crash distance for the given chromosome.
     */
    public double getCrashDistance(final String chromosome, final List<Set<String>> tracesPerFile,
                                   final Set<String> traces) {
        double callTreeDistance = getCallTreeDistance(chromosome, tracesPerFile);
        double basicBlockDistance = getBasicBlockDistance(chromosome, tracesPerFile);
        double reachedConstructorsPercentage = getNumberOfReachedConstructors(chromosome, traces);
        return (basicBlockDistance + callTreeDistance + reachedConstructorsPercentage) / 3;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return callTree.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAppName() {
        return interCFG.getAppName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<CallTreeVertex> getVertices() {
        return new ArrayList<>(callTree.getVertices());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BiFunction<CallTreeVertex, CallTreeVertex, Integer> getDistances(Set<CallTreeVertex> sources, Set<CallTreeVertex> targets) {
        final var distances
                = shortestPathAlgorithm.getManyToManyPaths(sources, targets);
        return (s, t) -> {
            final var path = distances.getPath(s, t);
            return path != null ? path.getLength() : -1;
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDistance(CallTreeVertex source, CallTreeVertex target) {
        return callTree.getShortestDistance(source, target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CallTreeVertex lookupVertex(String trace) {
        return callTree.lookUpVertex(trace);
    }

    /**
     * Maps the given set of traces to vertices in the graph.
     *
     * @param traces The set of traces that should be mapped to vertices.
     * @return Returns the vertices described by the given set of traces.
     */
    @Override
    public List<CallTreeVertex> lookupVertices(final List<String> traces) {

        long start = System.currentTimeMillis();

        // we need to mark vertices we visited
        final Set<CallTreeVertex> visitedVertices = Collections.newSetFromMap(new ConcurrentHashMap<CallTreeVertex, Boolean>());

        // map trace to vertex
        traces.parallelStream().forEach(trace -> {

            if (trace.contains(":")) {
                // skip branch distance trace and traces without a matching vertex pair.
                return;
            }

            // mapEntryTraceToVertex(visitedVertices, trace);
            // mapExitTraceToVertex(visitedVertices, trace);

            // mark actual vertex corresponding to trace
            var visitedVertex = lookupVertex(trace);

            if (visitedVertex == null) {
                Log.printWarning("Couldn't derive vertex for trace: " + trace);
            } else {
                visitedVertices.add(visitedVertex);
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping traces to vertices took: " + (end - start) + " ms.");

        Log.println("Number of visited vertices: " + visitedVertices.size());
        return new ArrayList<>(visitedVertices);
    }

    /**
     * Maps an entry trace to its vertex.
     *
     * @param visitedVertices The set of visited vertices.
     * @param trace The potential entry trace.
     */
    @SuppressWarnings("unused")
    private void mapEntryTraceToVertex(final Set<CallTreeVertex> visitedVertices, final String trace) {

        // mark virtual entry
        final String entryMarker = "->entry";
        final int entryIndex = trace.indexOf(entryMarker);
        if (entryIndex != -1) {
            final String entryTrace = trace.substring(0, entryIndex + entryMarker.length());
            final CallTreeVertex visitedEntry = lookupVertex(entryTrace);

            if (visitedEntry != null) {
                visitedVertices.add(visitedEntry);
            } else {
                Log.printWarning("Couldn't derive vertex for entry trace: " + entryTrace);
            }
        }
    }

    /**
     * Maps an exit trace to its vertex.
     *
     * @param visitedVertices The set of visited vertices.
     * @param trace The potential exit trace.
     */
    @SuppressWarnings("unused")
    private void mapExitTraceToVertex(final Set<CallTreeVertex> visitedVertices, final String trace) {

        // mark virtual exit
        final String exitMarker = "->exit";
        final int exitIndex = trace.indexOf(exitMarker);
        if (exitIndex != -1) {
            final String exitTrace = trace.substring(0, exitIndex + exitMarker.length());
            final CallTreeVertex visitedExit = lookupVertex(exitTrace);

            if (visitedExit != null) {
                visitedVertices.add(visitedExit);
            } else {
                Log.printWarning("Couldn't derive vertex for exit trace: " + exitTrace);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReachable(CallTreeVertex vertex) {
        return shortestPathAlgorithm.getPath(callTree.getRoot(), vertex) != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void draw(File outputPath) {
        callTree.drawGraph(outputPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void draw(File outputPath, Set<CallTreeVertex> visitedVertices, Set<CallTreeVertex> targets) {
        callTree.drawGraph(outputPath, visitedVertices, targets);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<CallTreeEdge> getOutgoingEdges(CallTreeVertex vertex) {
        return callTree.getOutgoingEdges(vertex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<CallTreeEdge> getIncomingEdges(CallTreeVertex vertex) {
        return callTree.getIncomingEdges(vertex);
    }
}
