package org.mate.endpoints;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Edge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import org.apache.commons.io.FileUtils;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;

/**
 * This endpoint offers an interface to operate with graphs in the background. This can be a simple control flow graph
 * to evaluate branch distance, but also a system dependence graph. The usage of this endpoint requires the
 * android-graphs-lib.jar as a dependency.
 */
public final class GraphEndpoint implements Endpoint {

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
    private Vertex targetVertex = null;

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
    private Map<Vertex, Integer> relevantVertexToIndex = null;

    /**
     * Describes whether the given vertex is a relevant vertex, i.e. a branch, case, if or switch vertex.
     *
     * @param vertex The given vertex.
     * @return Returns {@code true} if the given vertex describes a relevant vertex, otherwise {@code false} is returned.
     */
    private static boolean isRelevantVertex(final Vertex vertex) {
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

    public GraphEndpoint(final AndroidEnvironment androidEnvironment, final Path appsDir) {
        this.androidEnvironment = androidEnvironment;
        this.appsDir = appsDir;
    }

    @Override
    public Message handle(final Message request) {
        if (request.getSubject().startsWith("/graph/init")) {
            return initGraph(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance_vector")) {
            return getBranchDistanceVector(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance")) {
            return getBranchDistance(request);
        } else if (request.getSubject().startsWith("/graph/draw")) {
            return drawGraph(request);
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

        final var relevantVertices = graph.getVertices()
                .stream()
                .filter(GraphEndpoint::isRelevantVertex)
                .toArray(Vertex[]::new);
        final var relevantVerticesCount = relevantVertices.length;

        final var branchVerticesCount = branchVertices.size();

        final var relevantVertexToIndex = new HashMap<Vertex, Integer>(relevantVerticesCount);

        // The branch vertices get assigned the ids 0 to n.
        for (int i = 0; i < branchVerticesCount; ++i) {
            relevantVertexToIndex.put(branchVertices.get(i), i);
        }

        // Defines the reverse mapping (index to vertex) for every relevant vertex.
        final var indexToVertex = new Vertex[relevantVerticesCount];

        for (final Vertex vertex : relevantVertices) {
            final int newIndex = relevantVertexToIndex.size();
            // The remaining relevant vertices, i.e. switch and if vertices, get assigned the indices (n+1) onwards.
            final var oldIndex = relevantVertexToIndex.putIfAbsent(vertex, newIndex);
            indexToVertex[oldIndex != null ? oldIndex : newIndex] = vertex;
        }

        final var approachLevels = new char[relevantVerticesCount * branchVerticesCount];
        final var distances = graph.getDistances(Set.of(relevantVertices), Set.copyOf(branchVertices));

        IntStream.range(0, branchVerticesCount).parallel().forEach(i -> {

            final var branchVertex = branchVertices.get(i);
            final var row = i * relevantVerticesCount; // each branch defines an individual row

            for (int j = 0; j < relevantVerticesCount; ++j) { // store the distance to every other vertex

                final var relevantVertex = indexToVertex[j];

                /*
                * To store the distance, which can be -1 if no path exists between two vertices, in an (unsigned) char,
                * we need to add +1 to make it non-negative. Later, upon reading from the cache, we subtract -1 again.
                 */
                final var distance = distances.apply(relevantVertex, branchVertex) + 1;

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
    private String computeApproachLevelAndBranchDistance(final List<Vertex> visitedVertices, final Vertex branchVertex) {

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
        Vertex minDistanceVertex = null;

        for (final Vertex visitedVertex : visitedVertices) {

            final boolean isIfVertex = visitedVertex.isIfVertex();
            final boolean isSwitchVertex = visitedVertex.isSwitchVertex();
            final boolean isBranchVertex = visitedVertex.isBranchVertex();

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
                    minDistanceVertex = visitedVertex;
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
        final var branchVertices = graph.getBranchVertices();
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
                    final var distance = computeApproachLevelAndBranchDistance(visitedVertices, vertex);
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
    private String combineApproachLevelAndBranchDistance(final int approachLevel, final Vertex minDistanceVertex,
                                                         final Vertex branchVertex) {

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
                    = graph.getOutgoingEdges(minDistanceVertex)
                    .stream()
                    .map(Edge::getTarget)
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
                    = graph.getOutgoingEdges(minDistanceVertex)
                    .stream()
                    .map(Edge::getTarget)
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
     * Draws the graph and saves it inside the app directory.
     *
     * @param request The request message.
     * @return Returns a message indicating that the graph could be drawn.
     */
    private Message drawGraph(Message request) {

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        boolean raw = Boolean.parseBoolean(request.getParameter("raw"));

        File appDir = new File(appsDir.toFile(), graph.getAppName());
        File drawDir = new File(appDir, "graph-drawings");
        drawDir.mkdirs();

        if (raw) {
            Log.println("Drawing raw graph...");
            graph.draw(drawDir);
        } else {
            Log.println("Drawing graph...");

            // determine the target vertices (e.g. single branch or all branches)
            Set<Vertex> targetVertices = new HashSet<>();

            if (targetVertex != null) {
                targetVertices.add(targetVertex);
            } else {
                targetVertices.addAll(new HashSet<>(graph.getBranchVertices()));
            }

            // retrieve the visited vertices
            Set<Vertex> visitedVertices = new HashSet<>(getVisitedVertices(appDir));

            // draw the graph where target and visited vertices are marked in different colours
            graph.draw(drawDir, visitedVertices, targetVertices);
        }

        return new Message("/graph/draw");
    }

    /**
     * Retrieves the set (actually a list without duplicates due to performance reasons) of visited vertices by
     * traversing over all traces contained in the app directory.
     *
     * @param appDir The app directory.
     * @return Returns the visited vertices.
     */
    private List<Vertex> getVisitedVertices(File appDir) {

        // get list of traces file
        File tracesDir = new File(appDir, "traces");

        // collect the relevant traces files
        List<File> tracesFiles = getTraceFiles(tracesDir, null);

        // read traces from trace file(s)
        List<String> traces = readTraces(tracesFiles);

        return mapTracesToVertices(traces);
    }

    /**
     * Selects a target vertex based on the given target criterion.
     *
     * @param target Describes how a target should be selected.
     * @return Returns the selected target vertex.
     */
    private Vertex selectTargetVertex(String target) {

        Log.println("Target vertex selection strategy: " + target);

        switch (target) {
            case "no_target":
                return null; // for multiple targets
            case "random_target":
            case "random_branch":
                List<Vertex> targets = target.equals("random_target") ? graph.getVertices() : graph.getBranchVertices();

                final Random rand = new Random();
                while (true) {
                    Vertex randomVertex = targets.get(rand.nextInt(targets.size()));

                    if (graph.isReachable(randomVertex)) {
                        Log.println("Randomly selected target vertex: " + randomVertex + " [" + randomVertex.getMethod() + "]");
                        return randomVertex;
                    }
                }
            default:
                Vertex targetVertex = graph.lookupVertex(target); // look up target by supplied trace

                if (targetVertex == null) {
                    throw new UnsupportedOperationException("Custom target vertex not found: " + target);
                }
                return targetVertex;
        }
    }

    /**
     * Initialises the graph and pre-computes approach levels and branch distances when dealing with multiple targets.
     *
     * @param request The request message.
     * @return Returns a message indicating that the graph could be constructed.
     */
    private Message initGraph(Message request) {

        final String packageName = request.getParameter("packageName");
        final GraphType graphType = GraphType.valueOf(request.getParameter("graph_type"));
        final File apkPath = new File(request.getParameter("apk"));
        final String target = request.getParameter("target");

        if (!apkPath.exists()) {
            throw new IllegalArgumentException("Can't locate APK: " + apkPath.getAbsolutePath() + "!");
        }

        final boolean useBasicBlocks = Boolean.parseBoolean(request.getParameter("basic_blocks"));

        switch (graphType) {
            case INTRA_CFG:
                final String methodName = request.getParameter("method");
                graph = new IntraCFG(apkPath, methodName, useBasicBlocks, appsDir, packageName);
                break;
            case INTER_CFG:
                final boolean excludeARTClasses = Boolean.parseBoolean(request.getParameter("exclude_art_classes"));
                final boolean resolveOnlyAUTClasses
                        = Boolean.parseBoolean(request.getParameter("resolve_only_aut_classes"));
                graph = new InterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses,
                        appsDir, packageName);
                break;
            default:
                throw new UnsupportedOperationException("Graph type not yet supported!");
        }

        targetVertex = selectTargetVertex(target);

        long start = System.currentTimeMillis();
        initBranchDistanceCache(getInstrumentationPoints(packageName));
        initApproachLevelCache(targetVertex != null ? List.of(targetVertex) : graph.getBranchVertices());
        long end = System.currentTimeMillis();
        Log.println("Pre-Computing approach levels and branch distances took: " + (end - start) + "ms");

        return new Message("/graph/init");
    }

    /**
     * Computes the fitness value vector for a given chromosome combining approach level + branch distance.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance fitness vector.
     */
    @SuppressWarnings("unused")
    @Deprecated
    private Message getBranchDistanceVectorOld(final Message request) {

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");

        Log.println("Computing the branch distance vector for the chromosome: " + chromosome);

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        long start = System.currentTimeMillis();
        final List<String> traces = getTraces(packageName, chromosome);
        final List<Vertex> visitedVertices = mapTracesToVertices(traces);
        final var branchVertices = graph.getBranchVertices();
        final List<String> branchDistanceVector = computeBranchDistanceVectorOld(traces, visitedVertices, branchVertices);
        long end = System.currentTimeMillis();
        Log.println("Computing branch distance vector took: " + (end-start) + "ms");
        return new Message.MessageBuilder("/graph/get_branch_distance_vector")
                .withParameter("branch_distance_vector", String.join("+", branchDistanceVector))
                .build();
    }

    /**
     * Computes the branch distance vector combining approach level + branch distance.
     *
     * @param traces The set of traces.
     * @param visitedVertices The visited vertices.
     * @param branchVertices The branch vertices.
     * @return Returns the branch distance vector.
     */
    @Deprecated
    private List<String> computeBranchDistanceVectorOld(final List<String> traces, final List<Vertex> visitedVertices,
                                                        final List<Vertex> branchVertices) {
        final var vector = new String[branchVertices.size()];
        IntStream.range(0, branchVertices.size())
                .parallel()
                .forEach(index -> {
                    final var branchVertex = branchVertices.get(index);
                    final var distance = computeBranchDistanceOld(traces, visitedVertices, branchVertex);
                    vector[index] = distance;
                });
        final var branchDistanceVector = Arrays.asList(vector);
        return Collections.unmodifiableList(branchDistanceVector);
    }

    /**
     * Computes the branch distance (approach level + branch distance) for the given branch.
     *
     * @param traces The set of vertices.
     * @param visitedVertices The visited vertices.
     * @param branchVertex The given branch vertex.
     * @return Returns the branch distance for the given branch.
     */
    @Deprecated
    private String computeBranchDistanceOld(final List<String> traces, final List<Vertex> visitedVertices, Vertex branchVertex) {

        long start = System.currentTimeMillis();
        final var minEntry = computeMinEntry(visitedVertices, branchVertex);
        long end = System.currentTimeMillis();
        Log.println("Computing min entry took: " + (end - start) + "ms");

        if (minEntry == null) {
            // branch not reachable by execution path
            return String.valueOf(1);
        } else {
            final int minDistance = minEntry.getValue();

            if (minDistance == 0) {
                // covered target branch
                return String.valueOf(0);
            } else {
                final Vertex minDistanceVertex = minEntry.getKey();
                return combineApproachLevelAndBranchDistanceOld(traces, minDistance, minDistanceVertex, branchVertex);
            }
        }
    }

    /**
     * Computes the combined approach level + branch distance for the given vertex (if or switch vertex).
     *
     * @param traces The set of traces.
     * @param minDistance The minimal distance (approach level) from the target branch to the if or switch statement.
     * @param minDistanceVertex The closest if or switch statement.
     * @param branchVertex The branch vertex for which the distance should be computed.
     * @return Returns the combined approach level + branch distance metric.
     */
    @Deprecated
    private String combineApproachLevelAndBranchDistanceOld(final List<String> traces, final int minDistance,
                                                            final Vertex minDistanceVertex, final Vertex branchVertex) {
        /*
         * The vertex with the closest distance represents an if or switch stmt, at which the execution path took the wrong
         * direction. We need to find the shortest branch distance value for the given if or switch stmt. Note that the
         * if or switch stmt could have been visited multiple times.
         */
        final Statement stmt = minDistanceVertex.getStatement();

        int minBranchDistance = Integer.MAX_VALUE;

        if (minDistanceVertex.isIfVertex()) {

            // the if stmt is located the last position of the block
            final BasicStatement ifStmt = (BasicStatement) ((BlockStatement) stmt).getLastStatement();

            // find the branch distance trace(s) that describes the if stmt
            final String prefix = minDistanceVertex.getMethod() + "->" + ifStmt.getInstructionIndex() + ":";

            /*
             * We need to look for branch distance traces that refer to the if statement. A branch distance trace is
             * produced for both branches, but we only need to consider those traces that describe the branch that
             * couldn't be covered, otherwise we would have actually taken the right branch. Thus, the relevant
             * distance traces (we may have visited the if statement multiple times) must contain a distance > 0,
             * since a branch with a distance of 0 would have be taken. We simply need to pick the minimum of those
             * distance traces.
             */
            long start1 = System.currentTimeMillis();
            minBranchDistance = traces.stream()
                    .filter(trace -> trace.startsWith(prefix))
                    .map(trace -> Integer.parseInt(trace.split(":")[1]))
                    .filter(distance -> distance > 0)
                    .min(Comparator.naturalOrder())
                    .orElse(Integer.MAX_VALUE);
            long end1 = System.currentTimeMillis();
            Log.println("Computing branch distance took: " + (end1 - start1) + "ms");
        } else if (minDistanceVertex.isSwitchVertex()) {

            /*
            * TODO: Improve the branch distance metric for switch case statements. Right now, the branch distance for
            *  an individual case statement can be only 1, since we only differentiate between covered (0) and not
            *  covered (1), and we already filtered out direct hits.
             */

            // check if the branch vertex is a direct successor of the closest visited switch case statement
            Set<Edge> outgoingEdges = graph.getOutgoingEdges(minDistanceVertex);

            boolean directSuccessor = outgoingEdges.stream()
                    .map(Edge::getTarget)
                    .anyMatch(vertex -> vertex.equals(branchVertex));

            if (directSuccessor) {

                // find the branch distance trace(s) that describe(s) the case stmt
                final BasicStatement caseStmt = (BasicStatement) ((BlockStatement) branchVertex.getStatement()).getFirstStatement();
                final String prefix = minDistanceVertex.getMethod() + "->switch->" + caseStmt.getInstructionIndex() + ":";

                /*
                 * Since we potentially traversed the switch statement multiple times, there are multiple branch distance
                 * traces for each case statement. We need to pick the minimum out of those.
                 */
                long start1 = System.currentTimeMillis();
                minBranchDistance = traces.stream()
                        .filter(trace -> trace.startsWith(prefix))
                        .map(trace -> Integer.parseInt(trace.split(":")[1]))
                        .min(Comparator.naturalOrder())
                        .orElse(Integer.MAX_VALUE);
                long end1 = System.currentTimeMillis();
                Log.println("Computing branch distance took: " + (end1 - start1) + "ms");
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
        }

        // combine and normalise
        final float normalisedBranchDistance = minBranchDistance != Integer.MAX_VALUE
                ? (float) minBranchDistance / (minBranchDistance + 1) : 1.0f;
        final float combined = minDistance + normalisedBranchDistance;
        final float combinedNormalized = combined / (combined + 1);
        return String.valueOf(combinedNormalized);
    }

    /**
     * Computes the vertex with the closest distance to the given branch vertex. Note that a parallelized version using
     * streams is actually slower. In addition, using an early abort in case of a distance of 0 turns out to be slower
     * as well.
     *
     * @param visitedVertices The list of visited vertices.
     * @param branchVertex The given branch vertex.
     * @return Returns the vertex that comes closest to the given branch vertex.
     */
    @Deprecated
    private Map.Entry<Vertex, Integer> computeMinEntry(final List<Vertex> visitedVertices, final Vertex branchVertex) {

        int minDistance = Integer.MAX_VALUE;
        Vertex minEntry = null;

        /*
         * We are only interested in a direct hit (covered branch) or the distance to an if or switch statement.
         * This equals distances of either if or switch statements or branches and excludes distances to visited entry
         * or exit vertices.
         */
        for (final Vertex visitedVertex : visitedVertices) {
            final int distance = graph.getDistance(branchVertex, visitedVertex);
            // TODO: there can be multiple minima having the same approach level (distance)
            if (distance < minDistance
                    && ((distance == 0 && visitedVertex.isBranchVertex())
                    || (distance != -1 && (visitedVertex.isIfVertex() || visitedVertex.isSwitchVertex())))) {
                minDistance = distance;
                minEntry = visitedVertex;
            }
        }

        return minEntry != null ? Map.entry(minEntry, minDistance) : null;
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
     * Computes the fitness value for the given chromosome combining approach level + branch distance.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance information.
     */
    @Deprecated
    private Message getBranchDistance(final Message request) {

        // TODO: Evaluate whether for a single target the pre-computation of approach levels + branch distances make sense.

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance for the chromosome: " + chromosome);

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        final List<String> traces = getTraces(packageName, chromosome);
        final List<Vertex> visitedVertices = mapTracesToVertices(traces);
        final var branchDistance = computeBranchDistanceOld(traces, visitedVertices, targetVertex);
        return new Message.MessageBuilder("/graph/get_branch_distance")
                .withParameter("branch_distance", branchDistance)
                .build();
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
