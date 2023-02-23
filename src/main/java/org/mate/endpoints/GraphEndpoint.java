package org.mate.endpoints;

import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTree;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.Tuple;
import org.apache.commons.io.FileUtils;
import org.jf.dexlib2.analysis.AnalyzedInstruction;
import org.mate.crash_reproduction.*;
import org.mate.graphs.Graph;
import org.mate.graphs.GraphType;
import org.mate.graphs.InterCFG;
import org.mate.graphs.IntraCFG;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;

/**
 * This endpoint offers an interface to operate with graphs in the background. This can be a simple control flow graph
 * to evaluate branch distance, but also a system dependence graph. The usage of this endpoint requires the
 * android-graphs-lib.jar as a dependency.
 */

public class GraphEndpoint implements Endpoint {

    @SuppressWarnings("unused")
    private final AndroidEnvironment androidEnvironment;

    /**
     * The underlying graph.
     */
    private Graph graph = null;

    /**
     * The path to the apps directory.
     */
    private final Path appsDir;

    /**
     * The target vertex, e.g. a random branch. If multiple targets exists, e.g. in a multi-objective search, this field
     * is set to {@code null}.
     */
    // private Vertex targetVertex = null;

    /**
     * Contains the instrumented branches but also the instrumented if and switch instructions. These instrumentation
     * points are relevant for the approach level pre-computation.
     */
    private static final String INSTRUMENTATION_POINTS_FILE = "instrumentation-points.txt";

    /**
     * The number of relevant vertices, i.e. branch, case, if or switch vertices. Initially {@code -1}.
     */
    private int relevantVerticesCount = -1;

    /**
     * Assigns each relevant vertex, i.e. a branch, case, if or switch statement, a unique id. This is required for
     * the addressing in the approach level cache, see {@link #approachLevels}.
     */
    private Map<CFGVertex, Integer> relevantVertexToIndex = null;

    /**
     * Describes whether the given vertex is a relevant vertex, i.e. a branch, case, if or switch vertex.
     *
     * @param vertex The given vertex.
     * @return Returns {@code true} if the given vertex describes a relevant vertex, otherwise {@code false} is returned.
     */
    private static boolean isRelevantVertex(final CFGVertex vertex) {
        return vertex.isBranchVertex() || vertex.isIfVertex() || vertex.isSwitchVertex();
    }

    /**
     * Caches the pre-computed approach levels in a compact representation. In particular, we store for each relevant
     * vertex, i.e. a branch, case, if or switch vertex, the approach level to each other branch vertex. To reduce the
     * memory footprint to a minimum and speed-up the computation, a compact representation of a one-dimensional char
     * array was chosen. The char array can be visualized as a flattened two-dimensional array where for each branch
     * vertex a row consisting of n branch vertex distances and k remaining distances exists. To compute the array index
     * for the approach level between a target (branch vertex) and a source vertex, one needs to know the index of the
     * target (branch) and source vertex by looking up the {@link #relevantVertexToIndex} mapping and follow the
     * following formula:
     *
     * approachLevel(t,s) := approachLevels[relevantVertexToIndex(t) * #relevantVertices + relevantVertexToIndex(s)]
     *
     * The multiplication defines the essentially the row in the flattened two-dimensional array and the addition the
     * offset to the respective source vertex. We favoured an char array over a short array, because the positive range
     * is greater (2^16 - 1 vs 2^15 - 1). The downside of this approach is that we can't store negative distances (a
     * distance of -1 is returned by the internal API if no path exists between a source and target vertex), thus we
     * need to add +1 when we store and subtract -1 when we read from the array.
     */
    private char[] approachLevels = null;

    /**
     * Assigns each method a unique id. This is required for the addressing in the branch distance cache, see
     * {@link #branchDistances}.
     */
    private static Map<String, Integer> methodNameIndex = null;

    /**
     * Caches the branch distances. To reduce the memory footprint a flattened two-dimensional is used where each row
     * represents a single method and consists of the size of the IPs, the indices of the IPs and the branch distance
     * values for both if and switch statements. Lastly a generation number is stored per row. To compute the row index
     * in the branch distance array, the method name is derived from a trace and mapped via {@link #methodNameIndex} to
     * its index. We can visualize a row in the branch distance array as follows:
     *
     * (1) number of IPs in given method (size)
     * (2) the instruction indices of the IPs in ascending order (n)
     * (3) the minimal branch distance value > 0 (if statement) for each IP (n)
     * (4) the minimal branch distance value > 0 (switch statement) for each IP (n)
     *
     * By knowing the method (row) index, one can effectively compute the address to any minimal branch distance by
     * adding the specific offset, e.g. to compute the array index of the if branch distance value of the first IP, one
     * calculates the address as follows: (row index + 1) + size * n. We need to have potentially two branch distance
     * values per IP because a branch can be shared between an if and switch statement and both have a different formula
     * for computing the branch distance.
     */
    private static short[] branchDistances = null;

    /**
     * A steadily decreasing generation number. This is required to control when branch distance values need to be reset
     * to its default value.
     */
    private static short generation = Short.MAX_VALUE;

    // TODO: Get rid of this, store results in specific app folder!
    private final Path resultsPath;

    private CallTree callTree;

    // a target vertex (a random branch)
    private List<Vertex> targetVertices;

    private Map<AtStackTraceLine, AnalyzedStackTraceLine> analyzedStackTraceLines;
    private Set<String> targetComponents;
    private StackTrace stackTrace;

    // TODO: Make utility class.
    private CrashReproductionUtil crashReproductionUtil;

    public GraphEndpoint(AndroidEnvironment androidEnvironment, Path appsDir, Path resultsPath) {
        this.androidEnvironment = androidEnvironment;
        this.appsDir = appsDir;
        this.resultsPath = resultsPath;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/graph/init")) {
            return initGraph(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance_vector")) {
            return getBranchDistanceVector(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance")) {
            return getBranchDistance(request);
        } else if (request.getSubject().startsWith("/graph/draw")) {
            return drawGraph(request);
        } else if (request.getSubject().startsWith("/graph/callGraph/draw")) {
            return drawCallTree(request);
        } else if (request.getSubject().startsWith("/graph/reached_targets")) {
            return reachedTargets(request);
        } else if (request.getSubject().startsWith("/graph/stack_trace_tokens")) {
            return getStackTraceTokens(request);
        } else if(request.getSubject().startsWith("/graph/stack_trace_user_tokens")) {
            return getStackTraceUserTokens(request);
        } else if (request.getSubject().startsWith("/graph/stack_trace")) {
            return getStackTrace(request);
        } else if (request.getSubject().startsWith("/graph/call_graph_distance")) {
            return getNormalizedCallTreeDistance(request);
        } else if (request.getSubject().startsWith("/graph/reached_required_constructors")) {
            return getNormalizedReachedRequiredConstructors(request);
        } else if (request.getSubject().startsWith("/graph/basic_block_distance")) {
            return getMergedNormalizedBasicBlockDistance(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by GraphEndpoint!");
        }
    }

    /**
     * Pre-computes the approach levels between every pair of relevant vertices and branch vertices.
     *
     * @param branchVertices The list of branch vertices (targets).
     */
    private void initApproachLevelCache(final List<Vertex> branchVertices) {

        final var relevantVertices = ((List<CFGVertex>) graph.getVertices())
                .stream()
                .filter(GraphEndpoint::isRelevantVertex)
                .toArray(CFGVertex[]::new);
        final var relevantVerticesCount = relevantVertices.length;

        final var branchVerticesCount = branchVertices.size();

        final var relevantVertexToIndex = new HashMap<CFGVertex, Integer>(relevantVerticesCount);

        // The branch vertices get assigned the ids 0 to n.
        for (int i = 0; i < branchVerticesCount; ++i) {
            relevantVertexToIndex.put((CFGVertex) branchVertices.get(i), i);
        }

        // Defines the reverse mapping (index to vertex) for every relevant vertex.
        final var indexToVertex = new CFGVertex[relevantVerticesCount];

        for (final CFGVertex vertex : relevantVertices) {
            final int newIndex = relevantVertexToIndex.size();
            // The remaining relevant vertices, i.e. switch and if vertices, get assigned the indices (n+1) onwards.
            final var oldIndex = relevantVertexToIndex.putIfAbsent(vertex, newIndex);
            indexToVertex[oldIndex != null ? oldIndex : newIndex] = vertex;
        }

        final var approachLevels = new char[relevantVerticesCount * branchVerticesCount];
        final BiFunction<CFGVertex, CFGVertex, Integer> distances = graph.getDistances(Set.of(relevantVertices), Set.copyOf(branchVertices));

        IntStream.range(0, branchVerticesCount).parallel().forEach(i -> {

            final var branchVertex = branchVertices.get(i);
            final var row = i * relevantVerticesCount; // each branch defines an individual row

            for (int j = 0; j < relevantVerticesCount; ++j) { // store the distance to every other vertex

                final var relevantVertex = indexToVertex[j];

                /*
                 * To store the distance, which can be -1 if no path exists between two vertices, in an (unsigned) char,
                 * we need to add +1 to make it non-negative. Later, upon reading from the cache, we subtract -1 again.
                 */
                final var distance = distances.apply(relevantVertex, (CFGVertex) branchVertex) + 1;

                if (distance <= Character.MAX_VALUE) {
                    approachLevels[row + j] = (char) distance;
                } else {
                    throw new AssertionError(String.format("Cannot store approach level of size %d in a char.", distance));
                }
            }
        });

        this.relevantVertexToIndex = relevantVertexToIndex;
        this.approachLevels = approachLevels;
        this.relevantVerticesCount = relevantVerticesCount;
    }

    /**
     * Computes the approach level and branch distance for the given branch vertex (target).
     *
     * @param visitedVertices The list of visited vertices (traces).
     * @param branchVertex The given branch vertex (target).
     * @return Returns the combined approach level + branch distance for the given branch vertex.
     */
    private String computeApproachLevelAndBranchDistance(final List<Vertex> visitedVertices, final CFGVertex branchVertex) {

        /*
         * TODO: There can be multiple vertices with the same minimal distance (approach level) to the given target branch.
         *  The current implementation simply picks an arbitrary vertex out of those, but this is not ideal. In fact, one
         *  would need to perform further graph traversals to decide which is the most suited one. Right now we may pick
         *  a switch or if statement that follows the target branch but not the one that is the direct predecessor (to
         *  which the target branch is actually attached), see for more details the comments in the method
         *  combineApproachLevelAndBranchDistance().
         *
         */
        int minDistance = Integer.MAX_VALUE;
        CFGVertex minDistanceVertex = null;

        for (final Vertex visitedVertex : visitedVertices) {

            final boolean isIfVertex = ((CFGVertex) visitedVertex).isIfVertex();
            final boolean isSwitchVertex = ((CFGVertex) visitedVertex).isSwitchVertex();
            final boolean isBranchVertex = ((CFGVertex) visitedVertex).isBranchVertex();

            /*
             * We are only interested in a direct hit (covered branch) or the distance to an if or switch statement.
             * This excludes distances to visited entry or exit vertices.
             */
            if (isIfVertex || isSwitchVertex || isBranchVertex) {

                final int branchVertexIndex = relevantVertexToIndex.get(branchVertex);
                final int visitedVertexIndex = relevantVertexToIndex.get(visitedVertex);
                final int index = branchVertexIndex * relevantVerticesCount + visitedVertexIndex;

                /*
                 * We add here +1 to compensate the previous -1 subtraction in initApproachLevelCache(), which was
                 * necessary to store the cached approach level in a compact representation (char instead of int/short).
                 */
                final int approachLevel = approachLevels[index] - 1;

                if (approachLevel == 0 // covered branch
                        // closest if or switch vertex
                        || (approachLevel != -1 && approachLevel < minDistance && (isIfVertex || isSwitchVertex))) {
                    minDistance = approachLevel;
                    minDistanceVertex = (CFGVertex) visitedVertex;
                }
            }
        }

        /*
         * We return a distance of 1 if there exists no path to the branch vertex; a distance of 0 if the branch vertex
         * could be covered; and otherwise we combine the approach level to the closest if or switch statement with the
         * branch distance.
         */
        return minDistanceVertex == null ? "1" : minDistance == 0 ? "0"
                : combineApproachLevelAndBranchDistance(minDistance , minDistanceVertex, branchVertex);
    }

    /**
     * Retrieves the method name from the given instrumentation point. Each instrumentation point is described by a
     * unique trace consisting of the following form: package->class->method->instruction.
     *
     * @param instrumentationPoint The given instrumentation point.
     * @return Returns the fully-qualified method name belonging to the instrumentation point.
     */
    private static String instrumentationPointToMethodName(final String instrumentationPoint) {
        return instrumentationPoint.substring(0, instrumentationPoint.lastIndexOf('>') - 1);
    }

    /**
     * Retrieves the instruction index of the given instrumentation point. Each instrumentation point is described by a
     * unique trace consisting of the following form: package->class->method->instruction.
     *
     * @param instrumentationPoint The given instrumentation point.
     * @return Returns the instruction index belonging to the instrumentation point.
     */
    private static short instrumentationPointToIndex(final String instrumentationPoint) {
        return (short) Integer.parseUnsignedInt(instrumentationPoint,
                instrumentationPoint.lastIndexOf('>') + 1, instrumentationPoint.length(), 10);
    }

    /**
     * Retrieves the instrumentation points from the AUT. These points basically represent the branch, case, if and
     * switch statements. Each instrumentation point is described by a unique trace referring to a particular instruction.
     *
     * @param packageName The package name of the AUT.
     * @return Returns the instrumentation points of the AUT.
     */
    private List<String> getInstrumentationPoints(final String packageName) {

        final Path instrumentationPointsFile = appsDir.resolve(packageName).resolve(INSTRUMENTATION_POINTS_FILE);

        final List<String> instrumentationPoints;
        try {
            instrumentationPoints = Files.readAllLines(instrumentationPointsFile);
        } catch(final IOException e) {
            throw new RuntimeException("Could not read " + INSTRUMENTATION_POINTS_FILE + "!", e);
        }

        return instrumentationPoints;
    }

    /**
     * Computes the fitness value for a given chromosome combining approach level + branch distance.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance information.
     */
    private Message getBranchDistance(final Message request) {

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance for the chromosome: " + chromosome);

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = mapTracesToVertices(traces);
        precomputeBranchDistances(traces);
        final var branchDistance = computeApproachLevelAndBranchDistance(visitedVertices,
                // there is only a single target
                (CFGVertex) targetVertices.get(0));
        return new Message.MessageBuilder("/graph/get_branch_distance")
                .withParameter("branch_distance", branchDistance)
                .build();
    }

    /**
     * Computes the branch distance vector for a given chromosome combining approach level + branch distance.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance vector.
     */
    private Message getBranchDistanceVector(final Message request) {

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance vector for the chromosome: " + chromosome);

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        long start = System.currentTimeMillis();
        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = mapTracesToVertices(traces);
        final var branchVertices =  graph.getBranchVertices();
        long start1 = System.currentTimeMillis();
        precomputeBranchDistances(traces);
        long end1 = System.currentTimeMillis();
        Log.println("Pre-Computing branch distances took: " + (end1 - start1) + "ms");
        final List<String> branchDistanceVector = computeBranchDistanceVector(visitedVertices, branchVertices);
        long end = System.currentTimeMillis();
        Log.println("Computing branch distance vector took: " + (end - start) + "ms");

        return new Message.MessageBuilder("/graph/get_branch_distance_vector")
                .withParameter("branch_distance_vector", String.join("+", branchDistanceVector))
                .build();
    }

    /**
     * Computes the branch distance vector (approach levels + branch distances) for the given branch vertices.
     *
     * @param visitedVertices The list of visited vertices (traces).
     * @param branchVertices The branch vertices (targets).
     * @return Returns the branch distance vector.
     */
    private List<String> computeBranchDistanceVector(final List<Vertex> visitedVertices, final List<Vertex> branchVertices) {

        final var vector = new String[branchVertices.size()];
        IntStream.range(0, branchVertices.size())
                .parallel()
                .forEach(index -> {
                    final var vertex = branchVertices.get(index);
                    final var distance = computeApproachLevelAndBranchDistance(visitedVertices, (CFGVertex) vertex);
                    vector[index] = distance;
                });

        final var branchDistanceVector = Arrays.asList(vector);
        return Collections.unmodifiableList(branchDistanceVector);
    }

    /**
     * Retrieves the cached branch distance for a particular vertex (described by a trace).
     *
     * @param method The method name contained in the trace.
     * @param instruction The instruction index contained in the trace.
     * @param isSwitchStatement Whether we deal with a switch instruction.
     * @return Returns the cached branch distance.
     */
    private static int getBranchDistance(final String method, final int instruction, final boolean isSwitchStatement) {

        final int rowIndex = methodNameIndex.get(method);
        final int size = branchDistances[rowIndex]; // the number of IPs for the given method

        if (size >= 0) { // regular case

            int instructionIndex = rowIndex + 1; // the instruction index of the first IP
            final int end = rowIndex + 1 + size; // the instruction index of the last IP

            // find the instruction index of the IP corresponding to the given instruction (sorted in ascending order)
            while (instructionIndex < end  && branchDistances[instructionIndex] < instruction) {
                ++instructionIndex;
            }

            // the offset describes the index to the if or switch branch distance value
            return branchDistances[instructionIndex + size * (isSwitchStatement ? 2 : 1)] ;
        } else { // optimized case for exactly three IPs

            // negating the size delivers the instruction index of the IP in the middle
            final int midInstruction = -size;

            /*
             * Recall that the row for the optimized case of exactly 3 IPs looks as follows:
             *
             * (1) negated instruction index of middle IP (branchDistances[rowIndex])
             * (2) the three if branch distance values (branchDistances[rowIndex + 1] - [branchDistances[rowIndex + 3])
             * (3) the three switch branch distance values (branchDistances[rowIndex + 4] - [branchDistances[rowIndex + 6])
             * (4) the generation number (branchDistances[rowIndex + 7])
             *
             * That means that (rowIndex + 2) refers to index of the middle if branch distance value. The signum()
             * computation either returns -1 when instruction < midInstruction, 0 if instruction == midInstruction or
             * +1 if instruction > midInstruction. This offset defines the index to the given instruction. If we deal with
             * a switch instruction, we need to add an offset of 3.
             */
            final int branchDistanceIndex =
                    rowIndex + 2 + Integer.signum(instruction - midInstruction) + (isSwitchStatement ? 3 : 0);
            return branchDistances[branchDistanceIndex];
        }
    }

    /**
     * Initialises the branch distance cache.
     *
     * @param instrumentationPoints The list of instrumentation points, i.e. branch, case, switch and if statements.
     */
    private static void initBranchDistanceCache(final List<String> instrumentationPoints) {

        /*
         * TODO: Only allocate a branch distance entry for if and case statements since only for those statements a
         *  branch distance is ever requested. Right now for every IP (branch, case, if and switch) such an entry is
         *  reserved. Moreover, we could only allocate an entry for a switch branch distance if needed. We can check
         *  for each branch, whether there are multiple predecessors that refer both to an if and switch statement.
         */

        /*
         * TODO: Theoretically it could happen that a case statement is shared between two switch statements similar to
         *  the case of a shared branch between an if and a switch statement or two if statements. Since we store the
         *  branch distance value directly at the case statement in our cache, there is only a entry for potentially two
         *  distinct branch distance values. We would need to allocate for each switch statement an entry or store the
         *  branch distance values of the case statements directly within the switch statement similar to if statements.
         *  However, this would require then some additional addressing to refer to some individual case.
         */

        final Map<String, Set<Short>> indicesPerMethod
                = instrumentationPoints.stream()
                .collect(groupingBy(
                        GraphEndpoint::instrumentationPointToMethodName,
                        Collectors.mapping(GraphEndpoint::instrumentationPointToIndex, Collectors.toSet())));

        methodNameIndex = new HashMap<>(indicesPerMethod.size());

        /*
         * We need to assign each method a unique id. Similar to the approach level array, we divide the array into
         * rows/segments, where each row describes a method including the number of IPs, the indices of the IPs and its
         * branch distance values for both if and switch instructions. Lastly, a generation number follows. The method
         * index serves as the base address of a particular row.
         */
        int total = 0;
        for (final var entry : indicesPerMethod.entrySet()) {
            methodNameIndex.put(entry.getKey(), total);
            final int size = entry.getValue().size(); // the number of IPs

            /*
             * If the number of IPs is not equal 3, we require (3 * size) many entries for the indices of the IPs and if
             * as well as switch branch distance values. In addition, one field is required for the number of IPs and
             * one field for the generation number. If we have exactly 3 IPs for a method, an optimization can be applied
             * which saves certain fields. In particular, we require only 8 fields for the 3 if and 3 switch branch
             * distance values as well as the instruction index of the middle IP and the generation number.
             */
            final int add = size != 3 ? 3 * size + 2 : 8;
            total += add;
        }

        branchDistances = new short[total];

        indicesPerMethod.forEach((key, value) -> {

            final int rowIndex = methodNameIndex.get(key); // the base index in the array for the given method
            final int size = value.size();

            if (size != 3) { // regular case

                /*
                 * A row stores the number of IPs, followed by the indices of the IPs in ascending order, the if branch
                 * distance values, the switch branch distance values and lastly the generation number.
                 */
                branchDistances[rowIndex] = (short) size; // store the size of the IPs as first entry

                int i = rowIndex + 1;
                for (final int instructionIndex : value) {
                    branchDistances[i++] = (short) instructionIndex; // store the instruction indices of the IPs next
                }

                // sort the instruction indices in ascending order
                Arrays.sort(branchDistances, rowIndex + 1, i);

                // init the if + switch branch distance for each IP with a dummy value as well as the generation number
                Arrays.fill(branchDistances, i, i + 2 * size + 1, Short.MAX_VALUE);
            } else {
                /*
                 * We can apply a special optimization if we deal exactly with three IPs. Instead of saving the number
                 * of IPs and its three indices, we store only the negated index of the middle instruction followed by
                 * dummy values for the 6 (if + switch) branch distance values and the generation number.
                 */
                final List<Short> instructions = new ArrayList<>(value);
                instructions.sort(Comparator.naturalOrder());
                final int midInstruction = instructions.get(1);
                branchDistances[rowIndex] = (short) -midInstruction;
                Arrays.fill(branchDistances, rowIndex + 1, rowIndex + 8, Short.MAX_VALUE);
            }
        });
    }

    /**
     * Pre-computes / updates the branch distances for the given traces.
     *
     * @param traces The list of traces.
     */
    private static void precomputeBranchDistances(final List<String> traces) {

        final short g = generation--;

        for (final String trace : traces) {

            final int arrow = trace.lastIndexOf('>');
            final int colon = trace.indexOf(':', arrow);

            if (colon != -1) {

                final short distance = (short) Integer.parseUnsignedInt(trace, colon + 1, trace.length(), 10);

                /*
                 * We don't need to store a branch distance of 0 for neither if or switch statements, because we would
                 * have taken that branch or case statement (approach level of 0), thus never requesting the branch
                 * distance values at all.
                 */
                if (distance == 0) {
                    continue;
                }

                final String switchStr = "->switch->";
                final boolean isSwitchTrace = trace.regionMatches(arrow + 1 - switchStr.length(), switchStr,
                        0, switchStr.length());

                final String method = trace.substring(0, isSwitchTrace ? arrow + 1 - switchStr.length() : arrow - 1);
                final int instruction = Integer.parseUnsignedInt(trace, arrow + 1, colon, 10);

                final int rowIndex = methodNameIndex.get(method);
                final int size = branchDistances[rowIndex]; // the number of IPs is stored at the row index

                if (size >= 0) { // regular case

                    final int instructionBaseAddress = rowIndex + 1; // the instruction index of the first IP
                    final int branchDistanceBaseAddress = instructionBaseAddress + size; // the index of the first BD value
                    final int generation = branchDistanceBaseAddress + 2 * size; // the index of the generation number

                    if (branchDistances[generation] > g) { // reset the branch distance values upon new generation
                        Arrays.fill(branchDistances, branchDistanceBaseAddress, generation, Short.MAX_VALUE);
                        branchDistances[generation] = g; // update the generation number
                    }

                    // find the instruction index of the IP described by the trace
                    final int instructionIndex = Arrays.binarySearch(
                            branchDistances, instructionBaseAddress, branchDistanceBaseAddress, (short) instruction);

                    if (instructionIndex >= 0) {
                        // the index of the branch distance value is located at a fixed offset from the index of the IP
                        final int branchDistanceIndex = instructionIndex + size * (isSwitchTrace ? 2 : 1);

                        // update branch distance if better than previous one
                        final short oldDistance = branchDistances[branchDistanceIndex];
                        branchDistances[branchDistanceIndex] = distance < oldDistance ? distance : oldDistance;
                    } else {
                        Log.println("Instruction index not found in branch distance array for trace: " + trace);
                    }
                } else { // optimized variant for exactly three IPs
                    final int generation = rowIndex + 7; // the index of the generation number

                    if (branchDistances[generation] > g) { // reset the branch distance values upon new generation
                        Arrays.fill(branchDistances, rowIndex + 1, generation, Short.MAX_VALUE);
                        branchDistances[generation] = g; // update the generation number
                    }

                    final int midInstruction = -size; // the negated value refers to the index of the middle instruction

                    final int branchDistanceIndex =
                            rowIndex + 2 + Integer.signum(instruction - midInstruction) + (isSwitchTrace ? 3 : 0);

                    // update branch distance if better than previous one
                    final short oldDistance = branchDistances[branchDistanceIndex];
                    branchDistances[branchDistanceIndex] = distance < oldDistance ? distance : oldDistance;
                }
            }
        }
    }

    /**
     * Combines the approach level and branch distance computed for the given two vertices.
     *
     * @param approachLevel The computed approach level.
     * @param minDistanceVertex The vertex with the closest distance (approach level) to the given branch vertex.
     * @param branchVertex The given branch vertex (target).
     * @return Returns the normalised approach level + branch distance fitness value.
     */
    private String combineApproachLevelAndBranchDistance(final int approachLevel, final CFGVertex minDistanceVertex,
                                                         final CFGVertex branchVertex) {

        final int minBranchDistance;

        if (minDistanceVertex.isIfVertex()) {

            /*
             * Check if the target branch is a direct successor of the closest visited if statement. One might think that
             * we could check for approach level == 1, but this doesn't give us any direction. Consider the following
             * counter example: The target branch can have both as predecessor and successor an if statement, while the
             * target branch itself was not covered. That means the successor if statement was reached through a different
             * branch of the predecessor if statement. Both if statements have an approach level of 1, but only the
             * predecessor if statement is the one we would be interested. However, the current implementation supplies
             * an arbitrary if statement as the vertex with the closest distance.
             */
            final boolean directSuccessor
                    = ((Set<CFGEdge>) graph.getOutgoingEdges(minDistanceVertex))
                    .stream()
                    .map(CFGEdge::getTarget)
                    .anyMatch(vertex -> vertex.equals(branchVertex));

            if (directSuccessor) {

                /*
                 * The vertex with the closest distance represents an if stmt at which the execution path took the wrong
                 * direction. We need to find the shortest branch distance value for the given if stmt. Note that the if
                 * stmt could have been visited multiple times. Thus, we need to find the minimum > 0 (a branch distance
                 * of 0 would mean that we have actually covered the target branch).
                 */
                final Statement stmt = minDistanceVertex.getStatement();

                // the if statement is located the last position of the block
                final BasicStatement ifStmt = (BasicStatement) ((BlockStatement) stmt).getLastStatement();

                // the branch distance value is attached to the if statement
                minBranchDistance = getBranchDistance(minDistanceVertex.getMethod(), ifStmt.getInstructionIndex(),
                        false);

            } else {
                /*
                 * It can happen that there are multiple closest if statements and without a further graph traversal we
                 * don't know which one is the correct one. We simply assign here the highest possible distance to indicate
                 * that we need to choose a different path in the future.
                 */
                minBranchDistance = Integer.MAX_VALUE;
            }
        } else if (minDistanceVertex.isSwitchVertex()) {

            /*
             * TODO: Improve the branch distance metric for switch case statements. Right now, the branch distance for
             *  an individual case statement can be only 1, since we only differentiate between covered (0) and not
             *  covered (1), and we already filtered out direct hits.
             */

            /*
             * Check if the target branch (case stmt) is a direct successor of the closest visited switch statement. One
             * might think that we could check for approach level == 1, but this doesn't give us any direction. Consider
             * the following counter example: The target branch (case stmt) can have both as predecessor and successor
             * a switch statement, while the case stmt itself was not covered. That means the successor switch statement
             * was reached through a different case of the predecessor switch statement. Both switch statements have an
             * approach level of 1, but only the predecessor switch statement is the one we would be interested. However,
             * the current implementation supplies an arbitrary switch statement as the vertex with the closest distance.
             */
            final boolean directSuccessor
                    = ((Set<CFGEdge>) graph.getOutgoingEdges(minDistanceVertex))
                    .stream()
                    .map(CFGEdge::getTarget)
                    .anyMatch(vertex -> vertex.equals(branchVertex));

            if (directSuccessor) {
                // find the branch distance trace(s) that describe(s) the case stmt
                final BasicStatement caseStmt = (BasicStatement) ((BlockStatement) branchVertex.getStatement())
                        .getFirstStatement();

                // the branch distance is attached to the case statement
                minBranchDistance = getBranchDistance(minDistanceVertex.getMethod(), caseStmt.getInstructionIndex(),
                        true);
            } else {
                /*
                 * It can happen that the branch vertex is not a direct successor of the closest switch statement. In
                 * such a case, there are no branch distance traces. Or to be more precise, we don't know which traces
                 * are the relevant ones without performing a further graph traversal. We would have to look up through
                 * which case statement a path goes from the switch to the branch vertex. Moreover, there might be
                 * multiple case statements through which a path goes to the branch vertex. We simply assign here the
                 * highest possible distance to indicate that we need to choose a different path in the future.
                 */
                minBranchDistance = Integer.MAX_VALUE;
            }
        } else {
            throw new AssertionError("Closest vertex doesn't refer to an if or switch vertex!");
        }

        // combine and normalise approach level + branch distance
        final float normalisedBranchDistance = (float) minBranchDistance / (minBranchDistance + 1);
        final float combined = approachLevel + normalisedBranchDistance;
        final float combinedNormalized = combined / (combined + 1);
        return String.valueOf(combinedNormalized);
    }

    /**
     * Retrieves the stack trace (lines).
     *
     * @param request The request message.
     * @return Returns a response message containing the stack trace (lines).
     */
    private Message getStackTrace(Message request) {
        return new Message.MessageBuilder("/graph/stack_trace")
                .withParameter("stack_trace", String.join(",", stackTrace.getAtLines()))
                .build();
    }

    /**
     * Retrieves the stack trace tokens.
     *
     * @param request The request message.
     * @return Returns a response message containing the stack trace tokens.
     */
    private Message getStackTraceTokens(Message request) {

        final String packageName = request.getParameter("package");
        final Set<String> stackTraceTokens = stackTrace.getFuzzyTokens(packageName);
        final Stream<String> instructionTokens = crashReproductionUtil.getTokensForStackTrace(stackTrace, packageName);
        final Set<String> tokens = Stream.concat(stackTraceTokens.stream(), instructionTokens).collect(Collectors.toSet());

        final var builder = new Message.MessageBuilder("/graph/stack_trace_tokens")
                .withParameter("tokens", String.valueOf(tokens.size()));

        int pos = 0;
        for (String token : tokens) {
            builder.withParameter("token_" + pos, token);
            pos++;
        }

        return builder.build();
    }

    /**
     * Retrieves the stack trace user tokens.
     *
     * @param request The request message.
     * @return Returns a response message containing the stack trace user tokens.
     */
    private Message getStackTraceUserTokens(Message request) {
        return new Message.MessageBuilder("/graph/stack_trace_user_tokens")
                .withParameter("tokens", String.join(",", stackTrace.getUserTokens()))
                .build();
    }

    /**
     * Retrieves the traces for a single chromosome or all if unspecified.
     *
     * @param request The request message.
     * @return Returns the traces for a single chromosome or all if unspecified.
     */
    private Set<String> getTraces(Message request) {
        return new HashSet<>(readTraces(getTraceFiles(request)));
    }

    /**
     * Retrieves the traces per file. One such file essentially represents the traces of a single action.
     *
     * @param request The request message.
     * @return Returns the traces per file / action.
     */
    private List<Set<String>> getTracesPerFile(Message request) {
        return getTraceFiles(request).stream()
                .map(f -> new HashSet<>(readTraces(List.of(f))))
                .collect(Collectors.toList());
    }

    /**
     * Retrieves the trace files for the given chromosome or all if unspecified.
     *
     * @param request The request message containing the chromosome identifier.
     * @return Returns the trace files belonging to the chromosome.
     */
    private List<File> getTraceFiles(Message request) {

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");

        // collect the relevant traces files
        final Path appDir = appsDir.resolve(packageName);
        final File tracesDir = appDir.resolve("traces").toFile();
        return getTraceFiles(tracesDir, chromosome);
    }

    /**
     * Retrieves the visited methods described by the given traces.
     *
     * @param traces The given traces.
     * @return Returns the set of visited methods.
     */
    private Set<String> getVisitedMethods(final Set<String> traces) {
        return traces.stream().map(this::traceToMethod).collect(Collectors.toSet());
    }

    /**
     * Retrieves the average basic block distance between the traces and the target methods.
     *
     * @param request The request message.
     * @return Returns the response containing the average basic block distance.
     */
    private Message getMergedNormalizedBasicBlockDistance(Message request) {

        final List<Set<String>> traces = getTracesPerFile(request);
        final Map<AtStackTraceLine, Double> basicBlockDistances = getNormalizedBasicBlockDistances(traces);

        // computes the average basic block distance
        double sum = basicBlockDistances.values().stream().mapToDouble(d -> d).sum();
        // TODO: Rename to average basic block distance.
        double mergedNormalizedBasicBlockDistance = sum / basicBlockDistances.size();

        return new Message.MessageBuilder(request.getSubject())
                // TODO: Rename to average basic block distance.
                .withParameter("mergedNormalizedDistance", String.valueOf(mergedNormalizedBasicBlockDistance))
                .build();
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

        // TODO: Is this cast correct?
        final String targetMethod = analyzedStackTraceLine.getTargetMethodVertices().stream()
                .map(v -> (CFGVertex) v)
                .findAny().orElseThrow().getMethod();

        int minDistance = Integer.MAX_VALUE;

        for (String trace : traces) {
            if (traceToMethod(trace).equals(targetMethod)) {
                int distance = analyzedStackTraceLine.getTargetMethodVertices().stream()
                        // TODO: Employ a cache for the distances!
                        .map(targetVertex -> intraCFG.getDistance(intraCFG.lookupVertex(trace), (CFGVertex) targetVertex))
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
     * Determines how many of the required constructors have been visited by traces.
     *
     * @param request The message request.
     * @return Returns the normalized number (percentage) of visited constructors.
     */
    private Message getNormalizedReachedRequiredConstructors(Message request) {

        // track which methods have been visited by the traces
        final Set<String> reachedMethods = getTraces(request).stream().map(this::traceToMethod).collect(Collectors.toSet());

        // TODO: Cache this computation when initialising the call graph.
        // track the set of required constructors by iterating over the stack trace lines
        final Set<String> requiredConstructors = analyzedStackTraceLines.values().stream()
                .map(AnalyzedStackTraceLine::getRequiredConstructorCalls)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        // count how many constructors have been reached
        double reachedConstructors = requiredConstructors.stream().filter(reachedMethods::contains).count();

        // normalize in the range [0,1]
        double normalisedNumberOfReachedConstructors = requiredConstructors.size() == 0
                ? 1
                : reachedConstructors / requiredConstructors.size();

        // TODO: Hardcode message subject for better readability.
        return new Message.MessageBuilder(request.getSubject())
                // TODO: Rename to 'reachedConstructors'.
                .withParameter("reached", String.valueOf(normalisedNumberOfReachedConstructors))
                .build();
    }

    /**
     * Retrieves the normalized call tree distance for a given chromosome.
     *
     * @param request The message request.
     * @return Returns the normalised call tree distance.
     */
    private Message getNormalizedCallTreeDistance(Message request) {

        final String chromosome = request.getParameter("chromosome");
        final List<Set<String>> tracesPerFile = getTracesPerFile(request);

        double minDistance = getCallTreeDistance(chromosome, tracesPerFile);
        double normalizedDistance = minDistance == Integer.MAX_VALUE
                ? 1
                : minDistance / (minDistance + 1);

        // TODO: Remove after testing.
        if (normalizedDistance < 0 || normalizedDistance > 1) {
            throw new IllegalStateException("Normalized distance not between 0 and 1!");
        }

        Log.println("Call tree distance for " + chromosome + " is: abs. distance " + minDistance
                + ", rel. distance " + normalizedDistance);

        return new Message.MessageBuilder("/graph/call_tree_distance")
                .withParameter("distance", String.valueOf(normalizedDistance))
                .build();
    }

    /**
     * Computes the call tree distance for the given chromosome.
     *
     * @param chromosome The given chromosome.
     * @param tracesPerFile The traces per file of the given chromosome.
     * @return Returns the call tree distance for the chromosome.
     */
    private int getCallTreeDistance(final String chromosome, final List<Set<String>> tracesPerFile) {

        Log.println("Computing the call tree distance for the chromosome: " + chromosome);

        // We don't want to mix the traces of different actions, since our target action should produce all traces
        // necessary to cover the stack trace methods.
        // If we mix the traces then it's possible that we get a call tree distance of zero even if the target methods
        // are called from different actions
        // (and never just by one action). Then we have technically reached all target methods, but not in the right sequence
        return tracesPerFile.stream()
                .map(traces -> traces.stream().map(this::traceToMethod).collect(Collectors.toSet()))
                .mapToInt(this::getCallTreeDistance)
                .min().orElseThrow();
    }

    /**
     * Computes the call tree distance between the target vertices and the given traces.
     *
     * @param traces The given traces.
     * @return Returns the call tree distance.
     */
    private int getCallTreeDistance(final Set<String> traces) {

        // TODO: Cache this computation.
        // the call tree vertices describing the stack trace in reversed order
        final List<CallTreeVertex> callTreeVertices = targetVertices.stream()
                .map(v -> (CFGVertex) v)
                // TODO: Is this cast correct?
                .map(CFGVertex::getMethod)
                .map(CallTreeVertex::new)
                .collect(Collectors.toList());
        Collections.reverse(callTreeVertices);

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

    // TODO: Understand & Document.
    private Message reachedTargets(Message request) {

        var tracesPerFile = getTracesPerFile(request);

        // If an exception is thrown then there is no trace of the line that threw the exception
        // Since (at least one of) the target lines will throw an exception we need to manually add these traces
        // in order to decide whether we have reached all target lines/methods
        tracesPerFile.forEach(traces -> traces.addAll(deduceTracesFromStackTrace(request.getParameter("stackTrace"),
                request.getParameter("packageName"))));

        Message response = new Message(request.getSubject());

        appendMap(response, "reachedTargetComponents", getMapWithMostTrue(tracesPerFile,
                this::reachedTargetComponents), Function.identity(), Object::toString);
        appendMap(response, "reachedTargetMethods", getMapWithMostTrue(tracesPerFile,
                this::reachedTargetMethods), AtStackTraceLine::toString, Object::toString);
        appendMap(response, "reachedTargetLines", getMapWithMostTrue(tracesPerFile,
                this::reachedTargetLines), AtStackTraceLine::toString, Object::toString);

        return response;
    }

    // TODO: Understand & Document.
    private Set<String> deduceTracesFromStackTrace(String stackTrace, String packageName) {

        if (stackTrace == null) {
            return Set.of();
        } else {
            StackTrace stackTraceObj = StackTraceParser.parse(Arrays.asList(stackTrace.split("\n")));
            return crashReproductionUtil.getTargetTracesForStackTrace(stackTraceObj.getStackTraceAtLines()
                    .collect(Collectors.toList()), (InterCFG) graph, packageName)
                    .values().stream().flatMap(Collection::stream)
                    // TODO This is an over approximation, since it will add traces that came potentially after the crash
                    // (e.g. if the exception is thrown at the beginning of a block statement we will still pretend like
                    // the remaining statements
                    // from the block statement were reached as well)
                    // TODO: Is this cast correct?
                    .flatMap(v -> tracesForStatement(((CFGVertex) v).getStatement()))
                    .collect(Collectors.toSet());
        }
    }

    // TODO: Rename this to something like 'appendRequestParameters'.
    private static <K, V> void appendMap(Message request, String mapName, Map<K, V> map,
                                         Function<K, String> keyToString, Function<V, String> valueToString) {

        request.addParameter(mapName + ".size", String.valueOf(map.size()));

        int pos = 0;
        for (var entry : map.entrySet()) {
            request.addParameter(mapName + ".k" + pos, keyToString.apply(entry.getKey()));
            request.addParameter(mapName + ".v" + pos, valueToString.apply(entry.getValue()));
            pos++;
        }
    }

    /**
     * Computes a mapping for each traces per file and returns the mapping with the most {@code true} values.
     *
     * @param tracesPerFile The traces per file / action.
     * @param tracesToMapFunction The traces to map function.
     * @param <T> The key type of the (result) map.
     * @return Returns a mapping that contains the most {@code true} values according to the supplied mapping function.
     */
    private <T> Map<T, Boolean> getMapWithMostTrue(final List<Set<String>> tracesPerFile,
                                                   Function<Set<String>, Map<T, Boolean>> tracesToMapFunction) {
        return tracesPerFile.stream()
                .map(tracesToMapFunction)
                .max(Comparator.comparingLong(map -> map.values().stream().filter(b -> b).count()))
                .orElseThrow();
    }

    /**
     * Computes a mapping that describes which target component (key) has been covered by the given traces.
     *
     * @param traces The given traces.
     * @return Returns a mapping that tracks which target component has been covered by the traces.
     */
    private Map<String, Boolean> reachedTargetComponents(final Set<String> traces) {
        final Set<String> reachedClasses = traces.stream().map(this::traceToClass).collect(Collectors.toSet());
        return targetComponents.stream()
                .map(ClassUtils::convertDottedClassName)
                .collect(Collectors.toMap(Function.identity(), reachedClasses::contains));
    }

    /**
     * Computes a mapping that describes which stack trace line (target method) has been covered by the given traces.
     *
     * @param traces The given traces.
     * @return Returns a mapping that tracks which target method (stack trace line) has been covered by the traces.
     */
    private Map<AtStackTraceLine, Boolean> reachedTargetMethods(final Set<String> traces) {

        final Set<String> reachedMethods = traces.stream().map(this::traceToMethod).collect(Collectors.toSet());

        final Map<AtStackTraceLine, Boolean> reachedTargetMethods = analyzedStackTraceLines.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, stackTraceLine -> {
                    final String method = expectOne(stackTraceLine.getValue().getTargetInterVertices().stream()
                            // TODO: Is this cast correct?
                            .map(v -> (CFGVertex) v)
                            .map(CFGVertex::getMethod)
                            .collect(Collectors.toSet()));
                    return reachedMethods.contains(method);
                }));
        onlyAllowCoveredIfPredecessorCoveredAsWell(reachedTargetMethods);
        return reachedTargetMethods;
    }

    /**
     * Computes a mapping that describes which target line has been covered by the given traces.
     *
     * @param traces The given traces.
     * @return Returns a mapping that tracks which target line has been covered by the traces.
     */
    private Map<AtStackTraceLine, Boolean> reachedTargetLines(final Set<String> traces) {

        final Set<String> reachedBasicBlocks = traces.stream()
                .map(trace -> {
                    final String[] parts = trace.split("->");
                    // class name -> method name -> basic block index
                    return parts[0] + "->" + parts[1] + "->" + parts[2];
                }).collect(Collectors.toSet());

        final Map<AtStackTraceLine, Boolean> reachedTargetLines = analyzedStackTraceLines.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    final long matches = e.getValue().getTargetInterVertices().stream()
                            // TODO: Is this cast correct?
                            .map(v -> (CFGVertex) v)
                            .filter(v -> tracesForStatement(v.getStatement()).anyMatch(reachedBasicBlocks::contains))
                            .count();

                    if (matches == 0) {
                        return false;
                    } else if (matches == e.getValue().getTargetInterVertices().size()) {
                        return true;
                    } else { // TODO: Can this really happen and what does this mean in fact?
                        Log.printWarning("Reached only some parts of line!");
                        return true;
                    }
                }));
        onlyAllowCoveredIfPredecessorCoveredAsWell(reachedTargetLines);
        return reachedTargetLines;
    }

    // TODO: Need help here for understanding!
    private void onlyAllowCoveredIfPredecessorCoveredAsWell(Map<AtStackTraceLine, Boolean> map) {
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
     * Checks whether the given collection contains exactly one element.
     *
     * @param collection The collection to be verified.
     * @param <T> The element type of the collection entries.
     * @return Returns the single element in the collection or throws an exception otherwise.
     */
    private static <T> T expectOne(Collection<T> collection) {
        if (collection.isEmpty()) {
            throw new NoSuchElementException("Empty collection!");
        } else if (collection.size() > 1) {
            throw new IllegalArgumentException("Collection contains more than one element!");
        } else {
            return collection.stream().findAny().orElseThrow();
        }
    }

    /**
     * Computes the traces for the given statement.
     *
     * @param statement The given statement.
     * @return Returns the traces for the statement.
     */
    private Stream<String> tracesForStatement(Statement statement) {
        return getInstructions(statement)
                .map(instruction -> statement.getMethod() + "->" + instruction.getInstructionIndex());
    }

    /**
     * Retrieves the instructions of the given statement.
     *
     * @param statement The given statement.
     * @return Returns the instructions belonging to the statement.
     */
    private static Stream<AnalyzedInstruction> getInstructions(Statement statement) {
        if (statement instanceof BasicStatement) {
            return Stream.of(((BasicStatement) statement).getInstruction());
        } else if (statement instanceof BlockStatement) { // basic block, unroll instructions
            return ((BlockStatement) statement).getStatements()
                    .stream().flatMap(GraphEndpoint::getInstructions);
        } else {
            return Stream.empty();
        }
    }

    /**
     * Retrieves the fully-qualified method name from the given trace.
     *
     * @param trace The given trace.
     * @return Returns the method name encapsulated in the trace.
     */
    private String traceToMethod(final String trace) {
        final String[] parts = trace.split("->");
        return parts[0] + "->" + parts[1];
    }

    /**
     * Retrieves the class name from the given trace.
     *
     * @param trace The given trace.
     * @return Returns the class name encapsulated in the trace.
     */
    private String traceToClass(String trace) {
        return trace.split("->")[0];
    }

    /**
     * Retrieves the target components that belong to the app package and are of the given component types.
     *
     * @param packageName The app package.
     * @param componentTypes The allowed component types, e.g. activities.
     * @return Returns the given target components.
     */
    private Set<String> getTargetComponents(String packageName, ComponentType... componentTypes) {
        return ((InterCFG) graph).getTargetComponents(stackTrace.getStackTraceAtLines()
                .filter(stackTraceLine -> stackTraceLine.isFromPackage(packageName))
                .collect(Collectors.toList()), componentTypes);
    }

    /**
     * Draws the graph and saves it inside the app directory.
     *
     * @param request The request message.
     * @return Returns a message indicating that the graph could be drawn.
     */
    private Message drawGraph(Message request) {

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        // TODO: Rename request parameter to 'chromosomes'.
        final String chromosomes = request.getParameter("chromosome");
        final boolean raw = Boolean.parseBoolean(request.getParameter("raw"));

        final File appDir = new File(appsDir.toFile(), graph.getAppName());
        final File drawDir = new File(appDir, "graph-drawings");
        drawDir.mkdirs();

        if (raw) {
            Log.println("Drawing raw graph...");
            graph.draw(drawDir);
        } else {
            Log.println("Drawing graph...");

            // TODO: Initialise the target vertices directly with the branches if multiple targets.

            // determine the target vertices (e.g. single branch or all branches)
            // final Set<Vertex> targetVertices = new HashSet<>(Objects.requireNonNullElseGet(this.targetVertices,
            //        () -> new HashSet<>(graph.getBranchVertices())));
            final Set<Vertex> targetVertices = new HashSet<>(this.targetVertices);

            // retrieve the visited vertices
            final Set<Vertex> visitedVertices = new HashSet<>(getVisitedVertices(appDir, chromosomes));

            // draw the graph where target and visited vertices are marked in different colours
            graph.draw(drawDir, visitedVertices, targetVertices);
        }

        return new Message("/graph/draw");
    }

    /**
     * Draws the call tree.
     *
     * @param request The request request.
     * @return Returns an empty response request.
     */
    private Message drawCallTree(Message request) {

        final Set<String> traces = getTraces(request);
        final Set<String> visitedMethods = getVisitedMethods(traces);

        // highlight the visited methods in red and the target methods in blue
        final Map<String, String> highlightMethods = visitedMethods.stream()
                .collect(Collectors.toMap(Function.identity(), a -> "red"));
        targetVertices.stream()
                // TODO: Is this cast correct?
                .map(v -> (CFGVertex) v)
                .map(CFGVertex::getMethod)
                .forEach(target -> highlightMethods.put(target, "blue"));

        // export the call tree to a dot file
        final String id = request.getParameter("id");
        final File dotFile = resultsPath.resolve(id + ".dot").toFile();
        callTree.toDot(dotFile, highlightMethods);

        // TODO: Follow here typical convention for subject names.
        return new Message("/graph/callTree/draw");
    }

    /**
     * Retrieves the set (actually a list without duplicates due to performance reasons) of visited vertices by
     * traversing over the specified chromosome traces contained in the app directory.
     *
     * @param appDir The app directory.
     * @param chromosomes A list of chromosomes separated by '+' or {@code null} if all traces should be considered.
     * @return Returns the visited vertices.
     */
    private List<Vertex> getVisitedVertices(final File appDir, final String chromosomes) {

        // get list of traces file
        final File tracesDir = new File(appDir, "traces");

        // collect the relevant traces files
        final List<File> tracesFiles = getTraceFiles(tracesDir, chromosomes);

        // read traces from trace file(s)
        final List<String> traces = readTraces(tracesFiles);

        return mapTracesToVertices(traces);
    }

    /**
     * Selects one or more target vertices based on the given target criterion.
     *
     * @param target Describes how a target should be selected.
     * @return Returns the selected target vertex.
     */
    private List<Vertex> selectTargetVertices(String target, String packageName, File apkPath, Message request) {

        Log.println("Target vertex selection strategy: " + target);

        switch (target) {
            // TODO: Rename to 'all_branches'/'branches'.
            case "no_target":
                return graph.getBranchVertices();
                // return null; // for multiple targets
            case "random_target":
            case "random_branch":
                List<CFGVertex> targets = target.equals("random_target") ? graph.getVertices() : graph.getBranchVertices();

                while (true) {
                    Random rand = new Random();
                    // TODO: Is this cast correct?
                    CFGVertex randomVertex = targets.get(rand.nextInt(targets.size()));

                    if (graph.isReachable(randomVertex)) {
                        Log.println("Randomly selected target vertex: " + randomVertex + " [" + randomVertex.getMethod() + "]");
                        return List.of(randomVertex);
                    }
                }
            case "stack_trace":
                // TODO: Hardcode file name of stack trace file to get rid of message request param!
                final File appDir = new File(appsDir.toFile(), packageName);

                // the stack_trace.txt should be located within the app directory
                final File stackTraceFile = new File(appDir, request.getParameter("stack_trace_path"));

                if (!stackTraceFile.exists()) {
                    throw new IllegalArgumentException("Stack trace file does not exist at: " + stackTraceFile.getAbsolutePath());
                }

                return getTargetVertices(stackTraceFile, packageName, apkPath);
            default:
                // look up target vertex/vertices by supplied trace(s)
                final List<Vertex> targetVertices = Arrays.stream(target.split(","))
                        .map(graph::lookupVertex)
                        .collect(Collectors.toList());

                if (targetVertices.isEmpty()) {
                    throw new UnsupportedOperationException("Custom target vertex/vertices not found: " + target);
                }
                return targetVertices;
        }
    }

    private List<Vertex> getTargetVertices(final File stackTraceFile, final String packageName, final File apkPath) {

        final InterCFG interCFG = (InterCFG) graph;

        // TODO: Make this crash reproduction util a real utility class
        crashReproductionUtil = new CrashReproductionUtil(interCFG.getApk().getDexFiles(), interCFG);

        try {
            stackTrace = StackTraceParser.parse(Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()));
        } catch (IOException e) {
            Log.printError("Could not read stack trace file from '" + stackTraceFile.getAbsolutePath() + "'!");
            throw new UncheckedIOException(e);
        }

        // TODO: Comment this.
        analyzedStackTraceLines = crashReproductionUtil.getLastConsecutiveLines(stackTrace.getStackTraceAtLines()
                .collect(Collectors.toList()), packageName).stream()
                .collect(Collectors.toMap(Function.identity(), line -> {

                    // TODO: Fix method name (vertices vs traces).
                    final var interVertices = crashReproductionUtil.getTargetTracesForStackTraceLine(line, interCFG);

                    // TODO: Require a more intuitive name. Is this the target method?
                    final String method = expectOne(interVertices.stream()
                            // TODO: Is this cast correct?
                            .map(v -> (CFGVertex) v)
                            .map(CFGVertex::getMethod)
                            .collect(Collectors.toSet()));

                    // TODO: Don't create intraCFG twice, retrieve intraCFG from interCFG!
                    final IntraCFG intraCFG = new IntraCFG(apkPath, method, true, appsDir, packageName);

                    // TODO: Can't we simply retrieve the vertices from the intraCFG directly?
                    final Set<Vertex> intraCFGVertices = interVertices.stream()
                            .map(v -> (CFGVertex) v)
                            .flatMap(interVertex -> tracesForStatement(interVertex.getStatement()))
                            .map(intraCFG::lookupVertex)
                            .collect(Collectors.toSet());

                    final var requiredConstructorCalls = crashReproductionUtil.getRequiredConstructorCalls(line);

                    return new AnalyzedStackTraceLine(interVertices, intraCFG, intraCFGVertices, requiredConstructorCalls);
                }));

        targetComponents = getTargetComponents(packageName, ComponentType.ACTIVITY, ComponentType.FRAGMENT);

        // retrieve the target vertices from the stack trace lines
        final List<Vertex> targetVertices = stackTrace.getStackTraceAtLines()
                .filter(analyzedStackTraceLines::containsKey)
                .map(analyzedStackTraceLines::get)
                .map(AnalyzedStackTraceLine::getTargetInterVertices)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        // at least a single in the stack trace must refer to the AUT
        if (targetVertices.isEmpty()) {
            throw new IllegalStateException("No targets found for stack trace!");
        }

        // TODO: Store the call tree vertices in a global variable.
        final var callTreeVertices = targetVertices.stream()
                // TODO: Is this cast correct?
                .map(v -> (CFGVertex) v)
                .map(CFGVertex::getMethod)
                .map(CallTreeVertex::new)
                .collect(Collectors.toList());
        // TODO: Why do we reverse the list?
        Collections.reverse(callTreeVertices);

        // the target vertices must be reachable in the call tree
        if (callTree.getShortestPathWithStops(callTreeVertices).isEmpty()) {
            throw new IllegalStateException("No path from root to target vertices!");
        }
        return targetVertices;
    }

    private Message initGraph(Message request) {

        String packageName = request.getParameter("packageName");
        GraphType graphType = GraphType.valueOf(request.getParameter("graph_type"));
        File apkPath = new File(request.getParameter("apk"));
        String target = request.getParameter("target");

        if (!apkPath.exists()) {
            throw new IllegalArgumentException("Can't locate APK: " + apkPath.getAbsolutePath() + "!");
        }

        boolean useBasicBlocks = Boolean.parseBoolean(request.getParameter("basic_blocks"));

        switch (graphType) {
            case INTRA_CFG:
                String methodName = request.getParameter("method");
                initIntraCFG(apkPath, methodName, useBasicBlocks, packageName, target, request);
                break;
            case INTER_CFG:
                boolean excludeARTClasses = Boolean.parseBoolean(request.getParameter("exclude_art_classes"));
                boolean resolveOnlyAUTClasses
                        = Boolean.parseBoolean(request.getParameter("resolve_only_aut_classes"));
                initInterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses,
                        packageName, target, request);
                break;
                // TODO: Introduce a graph type for the call graph
            default:
                throw new UnsupportedOperationException("Graph type not yet supported!");
        }

        long start = System.currentTimeMillis();
        initBranchDistanceCache(getInstrumentationPoints(packageName));
        initApproachLevelCache(targetVertices);
        long end = System.currentTimeMillis();
        Log.println("Pre-Computing approach levels and branch distances took: " + (end - start) + "ms");
        return new Message("/graph/init");

    }

    private void initIntraCFG(File apkPath, String methodName, boolean useBasicBlocks,
                                 String packageName, String target, Message request) {
        graph = new IntraCFG(apkPath, methodName, useBasicBlocks, appsDir, packageName);
        targetVertices = selectTargetVertices(target, packageName, apkPath, request);
    }

    private void initInterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,
                                 boolean resolveOnlyAUTClasses, String packageName, String target, Message request) {
        graph = new InterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, appsDir, packageName);
        // TODO: Only initialise call tree for crash reproduction.
        // callTree = ((InterCFG) graph).getCallTree();
        targetVertices = selectTargetVertices(target, packageName, apkPath, request);
    }

    private Message initCallTree() {
        return new Message("/graph/init");
    }

    /**
     * Retrieves the traces for the given chromosome.
     *
     * @param packageName The package name of the AUT.
     * @param chromosome The chromosome for which the traces should be retrieved.
     * @return Returns the traces for the given chromosome.
     */
    private List<String> getTraces(final String packageName, final String chromosome) {
        final Path appDir = appsDir.resolve(packageName);
        final File tracesDir = appDir.resolve("traces").toFile();
        final List<File> tracesFiles = getTraceFiles(tracesDir, chromosome);
        return readTraces(tracesFiles);
    }

    /**
     * Gets the list of traces files specified by the given chromosomes.
     *
     * @param tracesDir   The base directory containing the traces files.
     * @param chromosomes Encodes a mapping to one or several traces files.
     * @return Returns the list of traces files described by the given chromosomes.
     */
    private List<File> getTraceFiles(File tracesDir, String chromosomes) {

        // collect the relevant traces files
        List<File> tracesFiles = new ArrayList<>(FileUtils.listFiles(tracesDir, null, true));

        if (chromosomes != null) {

            // only consider the traces files described by the chromosome ids
            tracesFiles = new ArrayList<>();

            for (String chromosome : chromosomes.split("\\+")) {
                try {
                    tracesFiles.addAll(
                            Files.walk(tracesDir.toPath().resolve(chromosome))
                                    .filter(Files::isRegularFile)
                                    .map(Path::toFile)
                                    .collect(Collectors.toList()));
                } catch (IOException e) {
                    Log.printError("Couldn't retrieve traces files!");
                    throw new IllegalArgumentException(e);
                }
            }
        }

        Log.println("Number of considered traces files: " + tracesFiles.size());
        return tracesFiles;
    }

    /**
     * Reads the traces from the given list of traces files.
     *
     * @param tracesFiles A list of traces files.
     * @return Returns the unique traces contained in the given traces files.
     */
    private List<String> readTraces(List<File> tracesFiles) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        Set<String> traces = new LinkedHashSet<>();

        for (File traceFile : tracesFiles) {
            try (Stream<String> stream = Files.lines(traceFile.toPath(), StandardCharsets.UTF_8)) {
                traces.addAll(stream.collect(Collectors.toList()));
            } catch (IOException e) {
                Log.println("Reading traces.txt failed!");
                throw new IllegalStateException(e);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("Reading traces from file(s) took: " + (end - start) + " ms.");

        Log.println("Number of collected traces: " + traces.size());
        return new ArrayList<>(traces);
    }

    /**
     * Maps the given set of traces to vertices in the graph.
     *
     * @param traces The set of traces that should be mapped to vertices.
     * @return Returns the vertices described by the given set of traces.
     */
    private List<Vertex> mapTracesToVertices(List<String> traces) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        // we need to mark vertices we visited
        Set<Vertex> visitedVertices = Collections.newSetFromMap(new ConcurrentHashMap<Vertex, Boolean>());

        // map trace to vertex
        traces.parallelStream().forEach(trace -> {

            if (trace.contains(":")) {
                // skip branch distance trace
                return;
            }

            // mark virtual entry
            final String entryMarker = "->entry";
            final int entryIndex = trace.indexOf(entryMarker);
            if (entryIndex != -1) {
                final String entryTrace = trace.substring(0, entryIndex + entryMarker.length());
                final Vertex visitedEntry = graph.lookupVertex(entryTrace);

                if (visitedEntry != null) {
                    visitedVertices.add(visitedEntry);
                } else {
                    Log.printWarning("Couldn't derive vertex for entry trace: " + entryTrace);
                }
            }

            // mark virtual exit
            final String exitMarker = "->exit";
            final int exitIndex = trace.indexOf(exitMarker);
            if (exitIndex != -1) {
                final String exitTrace = trace.substring(0, exitIndex + exitMarker.length());
                final Vertex visitedExit = graph.lookupVertex(exitTrace);

                if (visitedExit != null) {
                    visitedVertices.add(visitedExit);
                } else {
                    Log.printWarning("Couldn't derive vertex for exit trace: " + exitTrace);
                }
            }

            // mark actual vertex corresponding to trace
            Vertex visitedVertex = graph.lookupVertex(trace);

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
}
