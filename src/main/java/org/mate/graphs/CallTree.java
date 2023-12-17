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
import org.jgrapht.GraphPath;
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
     * The directory where the stack traces are saved.
     */
    private static final String STACK_TRACES_DIR = "stack_traces";

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
     * Caches a mapping from trace to vertex.
     */
    private final Map<String, CallTreeVertex> traceToVertexCache;

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
     * The stack traces as discovered by the individual chromosomes; serves as a cache.
     */
    private final Map<String, StackTrace> stackTraces;

    /**
     * The list of target vertices, i.e. the list of methods encoded in the stack trace lines in reversed order
     * (from bottom to top, i.e. in call hierarchy order).
     */
    private final List<CallTreeVertex> targetVertices;

    /**
     * Describes the shortest path through the {@link #targetVertices}. We pre-compute this path to speed up the call
     * tree distance computation.
     */
    private final GraphPath<CallTreeVertex, CallTreeEdge> targetPath;

    /**
     * The set of required constructor cals.
     */
    private final Set<String> requiredConstructors;

    /**
     * The path to the apps directory.
     */
    private final Path appsDir;

    /**
     * The package name of the AUT.
     */
    private final String packageName;

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
        this.appsDir = appsDir;
        this.packageName = packageName;
        this.callTree = GraphUtils.constructCallTree(apkPath, excludeARTClasses, resolveOnlyAUTClasses);
        this.traceToVertexCache = initTraceToVertexCache();
        this.interCFG = new InterCFG(callTree.getInterCFG(), appsDir, packageName);
        this.shortestPathAlgorithm = callTree.initCHManyToManyShortestPathAlgorithm();
        this.components = callTree.getInterCFG().getComponents();
        this.apk = callTree.getInterCFG().getApk();
        this.stackTrace = loadStackTrace(appsDir, packageName, stackTracePath);
        this.stackTraces = new LinkedHashMap<>();
        this.analyzedStackTraceLines = analyzeStackTrace(appsDir, packageName);
        this.requiredConstructors = analyzeRequiredConstructors();
        this.targetVertices = computeTargetVertices();
        this.targetPath = callTree.getShortestPathWithStops(targetVertices.get(0),
                targetVertices.subList(1, targetVertices.size())).orElseThrow();
        initCache();
    }

    /**
     * Initialises the internal call tree distance cache by pre-computing the path between every vertex and the first
     * target vertex.
     */
    private void initCache() {
        long start = System.currentTimeMillis();
        final CallTreeVertex firstTargetVertex = targetVertices.get(0);
        for (CallTreeVertex callTreeVertex : callTree.getVertices()) {
            if (!targetVertices.contains(callTreeVertex)) {
                // NOTE: It is sufficient to pre-compute the path to the first target vertex (the bottom method in the
                // stack trace) since the path through the target vertices is fixed by the stack trace.
                callTree.getShortestPath(callTreeVertex, firstTargetVertex);
            }
        }
        long end = System.currentTimeMillis();
        Log.println("Initialising cache took: " + (end - start) + "ms");
    }

    /**
     * Reads the stack trace produced by the given chromosome if any.
     *
     * @param chromosome The name of the chromosome.
     * @return Returns the stack trace associated with the given chromosome or {@code null} if no stack trace exists.
     */
    private StackTrace readStackTrace(final String chromosome) {

        if (stackTraces.containsKey(chromosome)) {
            return stackTraces.get(chromosome);
        }

        final File appDir = new File(appsDir.toFile(), packageName);
        File stackTracesBaseDir = new File(appDir, STACK_TRACES_DIR);
        File stackTraceFile = new File(stackTracesBaseDir, chromosome + ".txt");
        if (!stackTraceFile.exists()) {
            return null;
        } else {
            // TODO: Adapt loadStackTrace() to accept final path.
            stackTracesBaseDir = new File(STACK_TRACES_DIR);
            stackTraceFile = new File(stackTracesBaseDir, chromosome + ".txt");
            final StackTrace stackTrace = loadStackTrace(appsDir, packageName, stackTraceFile.getPath());
            stackTraces.put(chromosome, stackTrace);
            return stackTrace;
        }
    }

    /**
     * Computes the crash distance for the given chromosome.
     *
     * @param chromosome The chromosome for which the crash distance should be computed.
     * @param tracesPerAction The traces per action.
     * @return Returns the crash distance for the given chromosome.
     */
    public double getCrashDistance(final String chromosome, final List<Set<String>> tracesPerAction) {

        final StackTrace stackTrace = readStackTrace(chromosome);

        if (stackTrace != null) {
            if (stackTrace.equals(this.stackTrace)) { // we could successfully reproduce crash
                Log.println("CallTreeDistance: " + 0.0d);
                Log.println("BasicBlockDistance: " + 0.0d);
                Log.println("ConstructorDistance: " + 0.0d);
                return 0.0;
            }
        }

        // Map traces to method format to be conformable with call tree structure.
        final List<Set<String>> coveredMethodsPerAction = tracesPerAction.stream()
                .map(t -> t.stream().map(Util::traceToMethod).collect(Collectors.toSet()))
                .collect(Collectors.toList());

        // Combine the covered methods.
        final Set<String> coveredMethods = coveredMethodsPerAction.stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        final double callTreeDistance = getCallTreeDistance(chromosome, coveredMethodsPerAction);
        final double basicBlockDistance = getBasicBlockDistance(chromosome, tracesPerAction);
        final double constructorDistance = getConstructorDistance(chromosome, coveredMethods);
        final double crashDistance = (callTreeDistance + basicBlockDistance + constructorDistance) / 3;

        Log.println("CallTreeDistance: " + callTreeDistance);
        Log.println("BasicBlockDistance: " + basicBlockDistance);
        Log.println("ConstructorDistance: " + constructorDistance);

        if (crashDistance == 0.0) {
            // NOTE: We can actually cover all stack trace lines but may not reproduce the crash. This can happen for
            // instance when the crash is state dependent, e.g., relying upon a specific input which is handed over to a
            // method out of our control (Android Framework), e.g., to a database. Since we only consider the stack trace
            // lines belonging to the AUT we have no guidance whether the remaining stack trace lines were covered in the
            // correct order as well. To avoid that the search stops here we need to return a crash distance > 0.
            // Example:
            // Caused by: android.database.sqlite.SQLiteException: near "bug": syntax error (code 1)
            //at android.database.sqlite.SQLiteConnection.nativePrepareStatement(Native Method)
            //at android.database.sqlite.SQLiteConnection.acquirePreparedStatement(SQLiteConnection.java:887)
            //at android.database.sqlite.SQLiteConnection.prepare(SQLiteConnection.java:498)
            //at android.database.sqlite.SQLiteSession.prepare(SQLiteSession.java:588)
            //at android.database.sqlite.SQLiteProgram.<init>(SQLiteProgram.java:58)
            //at android.database.sqlite.SQLiteQuery.<init>(SQLiteQuery.java:37)
            //at android.database.sqlite.SQLiteDirectCursorDriver.query(SQLiteDirectCursorDriver.java:44)
            //at android.database.sqlite.SQLiteDatabase.rawQueryWithFactory(SQLiteDatabase.java:1316)
            //at android.database.sqlite.SQLiteDatabase.rawQuery(SQLiteDatabase.java:1255)
            //at com.olam.DatabaseHelper.getSimilarStems(DatabaseHelper.java:115)
            //at com.olam.MainSearch$doSearch.doInBackground(MainSearch.java:255)
            //at com.olam.MainSearch$doSearch.doInBackground(MainSearch.java:228
            Log.println("Covered all stack trace lines belonging to the AUT without actually triggering the crash!");
            return 0.01d; // epsilon
        } else {
            return crashDistance;
        }
    }

    /**
     * Initializes the trace to vertex cache.
     *
     * @return Returns the trace to vertex cache.
     */
    private Map<String, CallTreeVertex> initTraceToVertexCache() {
        final Map<String, CallTreeVertex> traceToVertexCache = new HashMap<>();
        for (final CallTreeVertex vertex : callTree.getVertices()) {
            traceToVertexCache.put(vertex.getMethod(), vertex);
        }
        return traceToVertexCache;
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
            return StackTraceParser.parse(Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()), packageName);
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
                // NOTE: We require the line number information to map the stack trace lines to the respective vertices
                // in the intraCFG!
                .filter(stackTraceLine -> stackTraceLine.getLineNumber().isPresent())
                .collect(Collectors.toList()), packageName).stream()
                .collect(Collectors.toMap(Function.identity(), stackTraceLine -> {

                    // TODO: Directly compute the 'sourceCodeLineNumberIntraCFGVertices' from the stack trace line!

                    // Retrieve the interCFG vertices that are mapped to the given stack trace line.
                    final Set<CFGVertex> sourceCodeLineNumberInterCFGVertices
                            = getSourceCodeLineNumberInterCFGVertices(stackTraceLine);

                    // This seems to be a bit awkward but the target method name can't be derived from the stack trace
                    // line since the return type is not encoded in the stack trace line.
                    final String targetMethod = Util.expectOne(sourceCodeLineNumberInterCFGVertices.stream()
                            .map(CFGVertex::getMethod)
                            .collect(Collectors.toSet()));

                    // create the intraCFG matching the target method (method encoded in the stack trace line)
                    final IntraCFG intraCFG = new IntraCFG(apk.getApkFile(), targetMethod, true, appsDir, packageName);

                    // We need to map the interCFG vertices back to the intraCFG vertices since we define the basic block
                    // distance on the latter graph. Since an interCFG splits basic blocks upon the occurrence of invoke
                    // instructions there are potentially multiple interCFG vertices for a single vertex in the intraCFG.
                    // However, the traces produced by the basic block instrumentation are unaware of this splitting and
                    // map directly to the intraCFG vertices, thus this re-mapping is necessary and reasonable.
                    final Set<CFGVertex> sourceCodeLineNumberIntraCFGVertices = sourceCodeLineNumberInterCFGVertices.stream()
                            .flatMap(interCFGVertex -> Util.tracesForStatement(interCFGVertex.getStatement()))
                            .map(intraCFG::lookupVertex)
                            .collect(Collectors.toSet());

                    // Retrieves the required constructors to properly call the target method in the stack trace line.
                    final var requiredConstructorCalls = getRequiredConstructorCalls(stackTraceLine);

                    return new AnalyzedStackTraceLine(intraCFG, sourceCodeLineNumberIntraCFGVertices, requiredConstructorCalls);
                }));
    }

    /**
     * Initialises the target vertices, i.e., the set of target methods encoded in the stack trace lines.
     *
     * @return Returns the target vertices for crash reproduction.
     */
    private List<CallTreeVertex> computeTargetVertices() {

        // TODO: Directly derive the target call tree vertices from the analyzed stack trace lines!

        // Retrieve the target intraCFG vertices from the stack trace lines.
        final List<CFGVertex> targetIntraCFGVertices = stackTrace.getStackTraceAtLines()
                .filter(analyzedStackTraceLines::containsKey)
                .map(analyzedStackTraceLines::get)
                .map(AnalyzedStackTraceLine::getSourceCodeLineNumberIntraCFGVertices)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        // At least a single line (target method) in the stack trace must refer to the AUT.
        if (targetIntraCFGVertices.isEmpty()) {
            throw new IllegalStateException("No targets found for stack trace!");
        }

        // Map the intraCFG vertices to the callTree vertices, i.e. (the methods encoded in the stack trace lines).
        final List<CallTreeVertex> targetVertices = targetIntraCFGVertices.stream()
                .map(CFGVertex::getMethod)
                .map(CallTreeVertex::new)
                .collect(Collectors.toList());

        // Reverse since we want to cover them (the stacktrace actually) from bottom to top.
        Collections.reverse(targetVertices);

        // The target vertices must be reachable and a path through them in stack trace order must exist.
        if (callTree.getShortestPathWithStops(targetVertices).isEmpty()) {
            throw new IllegalStateException("No path from root to target vertices!");
        }

        return targetVertices;
    }

    /**
     * Retrieves the target vertices for crash reproduction, i.e., the set of target methods encoded in the stack trace lines.
     *
     * @return Returns the target vertices for crash reproduction.
     */
    public List<CallTreeVertex> getTargetVertices() {
        return Collections.unmodifiableList(targetVertices);
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
     * Retrieves the interCFG vertices that map to the source code line number encoded in the given stack trace line.
     *
     * @param stackTraceLine The given (at) stack trace line.
     * @return Returns the interCFG vertices associated with the given stack trace line.
     */
    private Set<CFGVertex> getSourceCodeLineNumberInterCFGVertices(final AtStackTraceLine stackTraceLine) {

        // Retrieve the method and bytecode instructions that refer to the source code line number encoded in the stack trace line.
        var mappedMethodAndByteCodeInstructions = getInstructionsForLine(stackTraceLine).orElseThrow();

        // Map the bytecode instructions back to vertices in the interCFG. Since we use basic blocks for the
        // interCFG, multiple (consecutive) instructions potentially map to the same vertex.
        return mappedMethodAndByteCodeInstructions.getY().stream()
                .map(instruction -> interCFG.findVertexByInstruction(mappedMethodAndByteCodeInstructions.getX(), instruction))
                .collect(Collectors.toSet());
    }

    /**
     * Retrieves the set of required constructors calls for the given stack trace line.
     *
     * @param stackTraceLine The given stack trace line.
     * @return Returns the set of required constructor calls.
     */
    private Set<String> getRequiredConstructorCalls(final AtStackTraceLine stackTraceLine) {
        return getInstructionsForLine(stackTraceLine)
                .stream()
                .flatMap(methodAndInstructions -> Stream.concat(
                        // Required constructors to reach the method containing the instruction
                        getRequiredConstructorCalls(methodAndInstructions.getX().toString()),
                        methodAndInstructions.getY().stream()
                                .flatMap(this::getRequiredConstructorCalls)
                ))
                .collect(Collectors.toSet());
    }

    /**
     * Retrieves required constructors derived from the given instruction, i.e. we derive from the target method of an
     * invoke instructions required constructor calls.
     *
     * @param instruction The instruction from which required constructors should be derived.
     * @return Returns a set of required constructors derived from the given instruction.
     */
    private Stream<String> getRequiredConstructorCalls(final BuilderInstruction instruction) {

        if (InstructionUtils.isInvokeInstruction(instruction)) {
            final String invokedMethod = ((ReferenceInstruction) instruction).getReference().toString();
            final String dottedClassName = ClassUtils.dottedClassName(MethodUtils.getClassName(invokedMethod));
            // TODO: Whitelist additional packages belonging to the AUT.
            if (dottedClassName.startsWith(getAppName())) {
                return getRequiredConstructorCalls(invokedMethod);
            }
        }

        // TODO: May derive additional constructors from field accesses performed by the instructions.
        return Stream.empty();
    }

    /**
     * Retrieves the required constructor calls to properly invoke the given target method, i.e. for each parameter of
     * the target method all class constructors needs to be called. In addition, all constructors of the target method's
     * class are considered.
     *
     * @param methodName The method name of the target method.
     * @return Returns a set of required constructor calls to properly invoke the target method.
     */
    private Stream<String> getRequiredConstructorCalls(final String methodName) {

        final Optional<Tuple<ClassDef, Method>> classMethodTuple
                = MethodUtils.searchForTargetMethod(apk.getDexFiles(), methodName);

        if (classMethodTuple.isEmpty()) {
            Log.printWarning("Was not able to find method: " + methodName);
            return Stream.empty();
        } else {
            final Method targetMethod = classMethodTuple.get().getY();
            final String className = MethodUtils.getClassName(methodName);

            // TODO: It is probably not necessary to call all class constructors to be able to reproduce the crash.
            final List<String> classConstructors = new ArrayList<>(ClassUtils.getConstructors(classMethodTuple.get().getX()));

            /*
            * Iterate over all parameters of the target method and include the class constructors of each parameter.
            * In addition, include the class constructors of the target method itself or the static constructor if the
            * target method is static.
             */
            return Stream.concat(
                    // This is an over approximation, since it's always possible to pass null as a value, thus not every
                    // parameter might be actually required to call the method.
                    targetMethod.getParameterTypes()
                            .stream()
                            .map(Objects::toString)
                            // TODO: Convert array types to its class name.
                            .filter(classNameOfParameter -> !Util.isPrimitiveClass(classNameOfParameter))
                            // TODO: Whitelist additional packages belonging to the AUT.
                            .filter(classNameOfParameter -> ClassUtils.dottedClassName(classNameOfParameter).startsWith(getAppName()))
                            .flatMap(this::getConstructors),
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
                .filter(stackTraceLine -> stackTraceLine.isFromPackage(packageName))
                .filter(stackTraceLine -> stackTraceLine.getFileName().isPresent() && stackTraceLine.getLineNumber().isPresent())
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

        final String sourceFileName = stackTraceLine.getFileName().orElse(stackTraceLine.getClassName() + ".java");
        final String dottedClassName = stackTraceLine.getPackageName() + "." + stackTraceLine.getClassName();

        for (final DexFile dexFile : apk.getDexFiles()) {
            for (final ClassDef classDef : dexFile.getClasses()) {
                if (sourceFileName.equals(classDef.getSourceFile())
                        || ClassUtils.dottedClassName(classDef.toString()).equals(dottedClassName)) {
                    for (final Method method : classDef.getMethods()) {
                        // NOTE: Below check matches any overloaded method but the internal check on the source code line
                        // number ensures we are referring to the correct method encoded in the stack trace line.
                        if (method.toString().contains(stackTraceLine.getMethodName()) && method.getImplementation() != null) {

                            final Set<BuilderInstruction> instructionsAtLine = new HashSet<>();

                            final MutableMethodImplementation mutableMethodImplementation
                                    = new MutableMethodImplementation(method.getImplementation());
                            final List<BuilderInstruction> instructions = mutableMethodImplementation.getInstructions();

                            /*
                             * Retrieve the line number from the debug items and check whether they match the line number
                             * of the given stack trace line.
                             */
                            for (final BuilderInstruction instruction : instructions) {
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

    /**
     * Derives tokens from the given stack trace line, i.e., tokens are extracted both from the underlying instruction(s)
     * described by the stack trace line and menu items associated with the underlying instruction(s).
     *
     * @param stackTraceLine The given stack trace line.
     * @return Returns the tokens associated with the given stack trace line.
     */
    private Stream<String> getTokensFromStackTraceLine(final AtStackTraceLine stackTraceLine) {
        var methodAndInstructions = getInstructionsForLine(stackTraceLine).orElseThrow();
        return methodAndInstructions.getY().stream()
                .flatMap(instruction -> Stream.concat(
                        getTokensFromInstruction(instruction),
                        getMenuItemFromInstruction(methodAndInstructions.getX(), instruction).stream()
                                .map(MenuItemWithResolvedTitle::getTitle)
                ));
    }

    /**
     * Tries to derive a menu item (id) from the given method and instruction.
     *
     * @param method The given method.
     * @param instruction The given instruction.
     * @return Returns an optional menu item (id) associated with the given method and instruction.
     */
    private Optional<MenuItemWithResolvedTitle> getMenuItemFromInstruction(final Method method, final BuilderInstruction instruction) {
        // Check whether the given method belongs to an activity.
        return getComponentByNameAndType(MethodUtils.getClassName(method.toString()), ComponentType.ACTIVITY)
                .flatMap(activity -> {
                    // Retrieve the menus associated with the given activity.
                    final Map<Method, List<MenuItemWithResolvedTitle>> menus = ((Activity) activity).getMenus();

                    // Check whether the method represents a menu item selection method, e.g., onOptionsItemSelected().
                    return Optional.ofNullable(MenuUtils.ITEM_SELECT_METHOD_TO_ON_CREATE_MENU.get(MethodUtils.getMethodName(method)))
                            .map(onCreateMenuMethod -> MethodUtils.getClassName(method) + "->" + onCreateMenuMethod)
                            .flatMap(fullyQualifiedOnCreateMenuMethod -> menus.entrySet().stream()
                                    .filter(entry -> MethodUtils.deriveMethodSignature(entry.getKey()).equals(fullyQualifiedOnCreateMenuMethod))
                                    .findAny())
                            .map(Map.Entry::getValue);
                })
                .flatMap(menuItems -> {
                    // Map the menu item to a menu item id.
                    Optional<String> menuItemId = MenuUtils.getMenuItemStringId(instruction, method, apk.getDexFiles());
                    return menuItemId.flatMap(id -> menuItems.stream().filter(item -> item.getId().equals(id)).findAny());
                });
    }

    /**
     * Derives tokens from the given instruction.
     *
     * @param instruction The instruction from which tokens should be derived.
     * @return Returns the tokens associated with the given instruction.
     */
    private Stream<String> getTokensFromInstruction(final Instruction instruction) {

        if (instruction instanceof ReferenceInstruction) {
            ReferenceInstruction referenceInstruction = (ReferenceInstruction) instruction;

            if (referenceInstruction.getReferenceType() == ReferenceType.STRING) { // const-string instruction
                // Split string reference into words, e.g., "my user-input" -> ["my", "user", "input"].
                return Arrays.stream(referenceInstruction.getReference().toString().split("[-_\\s]"));
            } else if (referenceInstruction.getReferenceType() == ReferenceType.METHOD) { // invoke instruction
                // Split the plain method name into words upon each camel case.
                // TODO: Why do we ignore those specific methods?
                final Set<String> ignoreMethods = Set.of("<init>", "doInBackground");
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
     * @param traces The given traces of a single action.
     * @return Returns a mapping that tracks which target method (stack trace line) has been covered by the traces.
     */
    private Map<AtStackTraceLine, Boolean> reachedTargetMethods(final Set<String> traces) {

        final Set<String> coveredMethods = traces.stream().map(Util::traceToMethod).collect(Collectors.toSet());

        final Map<AtStackTraceLine, Boolean> coveredTargetMethods = analyzedStackTraceLines.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, stackTraceLine -> {
                    // map a stack trace line to its method and check whether it has been covered by the traces
                    final String targetMethod = stackTraceLine.getValue().getIntraCFG().getMethod();
                    return coveredMethods.contains(targetMethod);
                }));
        // TODO: Check whether this is hindering the search.
        unsetIfPrecedingStackTraceLineIsNotCovered(coveredTargetMethods);
        return coveredTargetMethods;
    }

    /**
     * Unsets an AUT stack trace line and any succeeding stack trace line as covered if the preceding stack trace line
     * has not been covered.
     *
     * @param stackTraceLines A mapping that describes for each AUT stack trace line whether it was covered.
     */
    private void unsetIfPrecedingStackTraceLineIsNotCovered(final Map<AtStackTraceLine, Boolean> stackTraceLines) {

        // We only consider a stack trace line as truly covered if also its predecessor stack trace line has been covered.
        // Example:
        //
        // Stacktrace (ordered from bottom to top):
        // at com.example.Class1.method1()
        // at com.example.Class2.method2()
        //
        // Covered methods described by traces in the order they would be called:
        // - com.example.Class1.method1()
        // - com.example.Class1.method3()
        // - com.example.Class2.method2()
        //
        // Result:
        // The stack trace imposes the order method1() -> method2() while the traces impose the order method1() ->
        // method3() -> method2(), i.e., method2() was called through method3() unlike expected in the stack trace,
        // thus we should consider method2() as not being covered (wrong call order).

        // Retrieve the 'at' stack trace lines from top to bottom.
        var stackTraceLinesOrdered = stackTrace.getStackTraceAtLines()
                // ignore stack trace lines not belonging to the AUT
                .filter(stackTraceLines::containsKey)
                // TODO: This check (map operation) seems to be redundant to be honest.
                .map(stackTraceLine -> stackTraceLines.entrySet()
                        .stream()
                        .filter(entry -> entry.getKey().equals(stackTraceLine))
                        .findAny())
                .map(Optional::orElseThrow)
                .collect(Collectors.toList());

        // Reverse to have them ordered from bottom to top, i.e. in call hierarchy.
        Collections.reverse(stackTraceLinesOrdered);

        final Iterator<Map.Entry<AtStackTraceLine, Boolean>> stackTraceLineIterator
                = stackTraceLinesOrdered.listIterator();

        while (stackTraceLineIterator.hasNext() && stackTraceLineIterator.next().getValue()) {
            // Run from bottom to top of stack trace lines until an uncovered stack trace line is reached.
        }

        // Unset remaining stack trace lines as covered, since predecessor is also not covered.
        while (stackTraceLineIterator.hasNext()) {
            stackTraceLineIterator.next().setValue(false);
        }
    }

    /**
     * Computes the minimal basic block distance (approach level) between the given traces and the intraCFG vertices
     * referring to the encoded source code line number of the given stack trace line.
     *
     * @param traces The set of traces of a single action.
     * @param stackTraceLine The 'at' stack trace line that has been covered at the method level.
     * @return Returns the minimal basic block distance between the traces and the target method.
     */
    private int getBasicBlockDistance(final Set<String> traces, final AtStackTraceLine stackTraceLine) {

        // Retrieve the intraCFG corresponding to the given stack trace line.
        final var analyzedStackTraceLine = analyzedStackTraceLines.get(stackTraceLine);
        final IntraCFG intraCFG = analyzedStackTraceLine.getIntraCFG();
        final String targetMethod = intraCFG.getMethod();

        int minDistance = Integer.MAX_VALUE;

        // Compute the minimal approach level between the traces and the intraCFG vertices referring to the encoded
        // source code line number of the given stack trace line.
        for (final String trace : traces) {
            // We covered the stack trace line at the method level, thus it is sufficient to consider only the traces
            // referring to the stack trace line (target method).
            if (Util.traceToMethod(trace).equals(targetMethod)) {
                int distance = analyzedStackTraceLine.getSourceCodeLineNumberIntraCFGVertices().stream()
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
     * @param tracesPerAction The given traces per action.
     * @return Returns a mapping that describes for each stack trace line the normalized basic block distance.
     */
    private Map<AtStackTraceLine, Double> getNormalizedBasicBlockDistances(final List<Set<String>> tracesPerAction) {

        // TODO: Is it sensible to consider the traces per action here and not the entire traces as a whole?

        // Look for the traces of a single action that covered most target methods.
        final var bestTraces = tracesPerAction.parallelStream()
                .map(traces -> new Tuple<>(traces, reachedTargetMethods(traces)))
                .sequential()
                .max(Comparator.comparingLong(tuple -> tuple.getY().values().stream().filter(b -> b).count()))
                .orElseThrow();

        // Compute the basic block distance between the best action (most covered target methods) and each stack trace line.
        return bestTraces.getY().entrySet().parallelStream() // Set<Map.Entry<AtStackTraceLine, Boolean>>
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    final int distance = entry.getValue()
                            // only need to compute distance if the action covered the target method (stack trace line)
                            ? getBasicBlockDistance(bestTraces.getX(), entry.getKey())
                            // did not cover target method (stack trace line)
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
     * @param tracesPerAction The traces per action.
     * @return Returns the normalized basic block distance for the given chromosome.
     */
    private double getBasicBlockDistance(final String chromosome, final List<Set<String>> tracesPerAction) {

        Log.println("Computing the basic block distance for the chromosome: " + chromosome);

        // TODO: If the line numbers have been stripped from the stack trace and/or aren't contained in the APK, we should
        //  ignore the basic block distance completely.
        // Compute for each stack trace line the basic block distance.
        final Map<AtStackTraceLine, Double> basicBlockDistances = getNormalizedBasicBlockDistances(tracesPerAction);

        // Compute the average basic block distance.
        final double sum = basicBlockDistances.values().stream().mapToDouble(d -> d).sum();
        final double averageBasicBlockDistance = sum / basicBlockDistances.size();

        Log.println("Basic block distance for " + chromosome + " is: " + averageBasicBlockDistance);
        return averageBasicBlockDistance;
    }

    /**
     * Computes the constructor distance for the given chromosome, i.e. the relative number of non covered constructors.
     *
     * @param chromosome The chromosome for which the constructor distance should be derived.
     * @param coveredMethods The covered methods of the given chromosome.
     * @return Returns the relative number of non covered constructors.
     */
    private double getConstructorDistance(final String chromosome, final Set<String> coveredMethods) {

        Log.println("Computing constructors distance for the chromosome: " + chromosome);

        // count how many constructors have been covered / non covered
        final double coveredConstructors = requiredConstructors.stream().filter(coveredMethods::contains).count();
        final double nonCoveredConstructors = requiredConstructors.size() - coveredConstructors;

        // normalize in the range [0,1]
        final double normalisedConstructorDistance = requiredConstructors.size() == 0
                // TODO: There should be at least a single required constructor so this case should never happen actually!
                ? 0
                : nonCoveredConstructors / requiredConstructors.size();

        Log.println("Number of non covered constructors for " + chromosome + " is: " + nonCoveredConstructors);
        return normalisedConstructorDistance;
    }

    /**
     * Retrieves the normalized call tree distance for the given chromosome.
     *
     * @param chromosome The chromosome for which the call tree distance should be derived.
     * @param coveredMethodsPerAction The covered methods (described through the traces) per action.
     * @return Returns the normalized call tree distance for the given chromosome.
     */
    private double getCallTreeDistance(final String chromosome, final List<Set<String>> coveredMethodsPerAction) {

        Log.println("Computing the call tree distance for the chromosome: " + chromosome);

        /*
        * We cannot simply compare all traces at once to the stack trace since this does not guarantee that the final action
        * actually triggered the crash and hence produced the same stack trace. In theory it could happen that the combined
        * set of traces fully covers the stack trace lines but actually didn't trigger the crash. Thus, we need to compare
        * the traces per action.
         */
        final double callTreeDistance = coveredMethodsPerAction.parallelStream()
                .mapToInt(this::getCallTreeDistance)
                .sequential()
                .min()
                .orElseThrow();

        final double normalizedCallTreeDistance = callTreeDistance == Integer.MAX_VALUE
                ? 1
                : callTreeDistance / (callTreeDistance + 1);

        Log.println("Call tree distance for " + chromosome + " is: abs. distance " + callTreeDistance
                + ", rel. distance " + normalizedCallTreeDistance);

        return normalizedCallTreeDistance;
    }

    /**
     * Computes the call tree distance between the target methods encoded in the stack trace and the covered methods
     * described by the traces.
     *
     * @param coveredMethods The covered methods of a single action.
     * @return Returns the call tree distance between the covered and target methods.
     */
    private int getCallTreeDistance(final Set<String> coveredMethods) {

        // the call tree vertices describing the stack trace methods (targets) in reversed order (operate on copy since being modified)
        final List<CallTreeVertex> targetMethodVertices = new ArrayList<>(this.targetVertices);

        // describes the lastly covered target method in the stack trace (from bottom to top ordered!)
        Optional<CallTreeVertex> lastCoveredTargetMethod = Optional.empty();

        while (!targetMethodVertices.isEmpty() && coveredMethods.contains(targetMethodVertices.get(0).getMethod())) {
            // remove target vertices that we have already covered
            lastCoveredTargetMethod = Optional.of(targetMethodVertices.remove(0));
        }

        if (targetMethodVertices.isEmpty()) {
            // We have reached all target methods, thus a distance of 0.
            return 0;
        } else if (lastCoveredTargetMethod.isPresent()) {
            // We partially covered the target methods, thus the distance is defined as the minimal path length from the last
            // covered method through the remaining target methods.
            return callTree.getShortestPathWithStops(lastCoveredTargetMethod.get(), targetMethodVertices).orElseThrow().getLength();
        } else {
            // We have not covered any target methods yet, thus the distance is defined as the minimal path length from
            // a covered method (trace) through the target methods.

            // TODO: Computing the minimal path between every single trace and the target methods can be expensive. Track
            //  it or compute the distance in advance. Alternatively, use a different metric in this case.
            int minDistance = Integer.MAX_VALUE;
            
            final CallTreeVertex firstTargetVertex = targetVertices.get(0);
            final Set<CallTreeVertex> callTreeVertices = callTree.getVertices();

            for (final String coveredMethod : coveredMethods) {

                final CallTreeVertex coveredMethodVertex = new CallTreeVertex(coveredMethod);

                if (!callTreeVertices.contains(coveredMethodVertex)) {
                    Log.printWarning("Method not contained in call tree: " + coveredMethodVertex.getMethod());
                    continue;
                }

                /*
                * NOTE: We only need to compute the shortest path to the first target vertex since the path through the
                * remaining target vertices is fixed by the underlying stack trace.
                 */
                var path = callTree.getShortestPath(coveredMethodVertex, firstTargetVertex);
                if (path.isPresent()) {
                    // We simply add the pre-computed path length through the individual target vertices.
                    final int distance = path.get().getLength() + targetPath.getLength();
                    if (distance < minDistance) {
                        minDistance = distance;
                    }
                }
            }
            return minDistance;
        }
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
        // TODO: Employ cache if necessary!
        return callTree.getShortestDistance(source, target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CallTreeVertex lookupVertex(String trace) {
        if (traceToVertexCache.containsKey(trace)) {
            return traceToVertexCache.get(trace);
        } else {
            try {
                return callTree.lookUpVertex(trace);
            } catch (Exception e) {
                Log.printWarning(e.getMessage());
                return null;
            }
        }
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
        traces.parallelStream().forEach(trace -> { // className->methodName->basicBlockPosition->basicBlockSize->...

            if (trace.contains(":")) {
                // skip branch distance trace and traces without a matching vertex pair.
                return;
            }

            final String[] tokens = trace.split("->");
            final String method = tokens[0] + "->" + tokens[1];

            // mark actual vertex corresponding to trace
            var visitedVertex = lookupVertex(method);

            if (visitedVertex == null) {
                Log.printWarning("Couldn't derive vertex for trace: " + method);
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
