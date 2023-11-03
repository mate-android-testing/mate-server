package org.mate.graphs;

import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.iface.Method;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.InstructionUtils;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.groupingBy;

/**
 * Represents an inter-procedural CFG.
 */
public class InterCFG extends CFG {

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
    private Map<String, Integer> methodNameIndex = null;

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
    private short[] branchDistances = null;

    /**
     * A steadily decreasing generation number. This is required to control when branch distance values need to be reset
     * to its default value.
     */
    private short generation = Short.MAX_VALUE;

    /**
     * Constructs an inter-procedural CFG.
     *
     * @param interCFG The inter-procedural CFG returned from the graph library.
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     */
    public InterCFG(BaseCFG interCFG, Path appsDir, String packageName) {
        super(interCFG, appsDir, packageName);
    }

    /**
     * Constructs an inter-procedural CFG with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param useBasicBlocks Whether basic blocks should be used or not.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     */
    public InterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName) {
        super(GraphUtils.constructInterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses),
                appsDir, packageName);

        long start = System.currentTimeMillis();
        initApproachLevelCache(branchVertices);
        initBranchDistanceCache(getInstrumentationPoints(packageName));
        long end = System.currentTimeMillis();
        Log.println("Pre-Computing approach levels and branch distances took: " + (end - start) + "ms");
    }

    /**
     * Describes whether the given vertex is a relevant vertex, i.e. a branch, case, if or switch vertex.
     *
     * @param vertex The given vertex.
     * @return Returns {@code true} if the given vertex describes a relevant vertex, otherwise {@code false} is returned.
     */
    private boolean isRelevantVertex(final CFGVertex vertex) {
        return vertex.isBranchVertex() || vertex.isIfVertex() || vertex.isSwitchVertex();
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
     * Retrieves the method name from the given instrumentation point. Each instrumentation point is described by a
     * unique trace consisting of the following form: package->class->method->instruction.
     *
     * @param instrumentationPoint The given instrumentation point.
     * @return Returns the fully-qualified method name belonging to the instrumentation point.
     */
    private String instrumentationPointToMethodName(final String instrumentationPoint) {
        return instrumentationPoint.substring(0, instrumentationPoint.lastIndexOf('>') - 1);
    }

    /**
     * Retrieves the instruction index of the given instrumentation point. Each instrumentation point is described by a
     * unique trace consisting of the following form: package->class->method->instruction.
     *
     * @param instrumentationPoint The given instrumentation point.
     * @return Returns the instruction index belonging to the instrumentation point.
     */
    private short instrumentationPointToIndex(final String instrumentationPoint) {
        return (short) Integer.parseUnsignedInt(instrumentationPoint,
                instrumentationPoint.lastIndexOf('>') + 1, instrumentationPoint.length(), 10);
    }

    /**
     * Pre-computes the approach levels between every pair of relevant vertices and branch vertices.
     *
     * @param branchVertices The list of branch vertices (targets).
     */
    private void initApproachLevelCache(final List<CFGVertex> branchVertices) {

        final var relevantVertices = getVertices()
                .stream()
                .filter(this::isRelevantVertex)
                .toArray(CFGVertex[]::new);
        final var relevantVerticesCount = relevantVertices.length;

        final var branchVerticesCount = branchVertices.size();

        final var relevantVertexToIndex = new HashMap<CFGVertex, Integer>(relevantVerticesCount);

        // The branch vertices get assigned the ids 0 to n.
        for (int i = 0; i < branchVerticesCount; ++i) {
            relevantVertexToIndex.put(branchVertices.get(i), i);
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
        final BiFunction<CFGVertex, CFGVertex, Integer> distances
                = getDistances(Set.of(relevantVertices), Set.copyOf(branchVertices));

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
    public String computeApproachLevelAndBranchDistance(final List<CFGVertex> visitedVertices, final CFGVertex branchVertex) {

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

        for (final CFGVertex visitedVertex : visitedVertices) {

            final boolean isIfVertex = visitedVertex.isIfVertex();
            final boolean isSwitchVertex = visitedVertex.isSwitchVertex();
            final boolean isBranchVertex = visitedVertex.isBranchVertex();

            /*
             * We are only interested in a direct hit (covered branch) or the distance to an if or switch statement.
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

                // TODO: Exit loop upon reaching an approach level of 0.

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
     * Retrieves the cached branch distance for a particular vertex (described by a trace).
     *
     * @param method The method name contained in the trace.
     * @param instruction The instruction index contained in the trace.
     * @param isSwitchStatement Whether we deal with a switch instruction.
     * @return Returns the cached branch distance.
     */
    private int getBranchDistance(final String method, final int instruction, final boolean isSwitchStatement) {

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
    private void initBranchDistanceCache(final List<String> instrumentationPoints) {

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
                        this::instrumentationPointToMethodName,
                        Collectors.mapping(this::instrumentationPointToIndex, Collectors.toSet())));

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
    public void precomputeBranchDistances(final List<String> traces) {

        final short g = generation--;

        for (final String trace : traces) {

            final int arrow = trace.lastIndexOf('>');
            final int colon = trace.indexOf(':', arrow);

            if (colon != -1) {

                short distance;
                try {
                    distance = (short) Integer.parseUnsignedInt(trace, colon + 1, trace.length(), 10);
                    if (distance < 0) { // overflow may occur from int to short conversion
                        distance = Short.MAX_VALUE;
                    }
                } catch (NumberFormatException e) {
                    distance = Short.MAX_VALUE;
                }

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
                    = getOutgoingEdges(minDistanceVertex)
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
                    = getOutgoingEdges(minDistanceVertex)
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
     * {@inheritDoc}
     */
    @Override
    protected List<CFGVertex> mapBranchesToVertices(List<String> branches) {

        long start = System.currentTimeMillis();

        List<CFGVertex> branchVertices = Collections.synchronizedList(new ArrayList<>());

        branches.parallelStream().forEach(branch -> {

            final CFGVertex branchVertex = lookupVertex(branch);

            if (branchVertex == null) {
                Log.printWarning("Couldn't derive vertex for branch: " + branch);
            } else {
                branchVertices.add(branchVertex);
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping branches to vertices took: " + (end - start) + " ms.");

        Log.println("Number of actual branches: " + branches.size());
        Log.println("Number of branch vertices: " + branchVertices.size());

        if (branchVertices.size() != branches.size()) {
            throw new IllegalStateException("Couldn't derive for certain branches the corresponding branch vertices!");
        }

        return branchVertices;
    }

    /**
     * Adds a mapping in the cache for method entries.
     *
     * @param traceToVertexCache The trace to vertex cache.
     */
    @SuppressWarnings("unused")
    private void handleMethodEntries(final Map<String, CFGVertex> traceToVertexCache) {

        // handle entry vertices
        Set<CFGVertex> entryVertices = getVertices().stream().filter(CFGVertex::isEntryVertex).collect(Collectors.toSet());

        for (CFGVertex entryVertex : entryVertices) {
            // exclude global entry vertex
            if (!entryVertex.equals(graph.getEntry())) {

                // virtual entry vertex
                traceToVertexCache.put(entryVertex.getMethod() + "->entry", entryVertex);

                // there are potentially several entry vertices when dealing with try-catch blocks at the beginning
                Set<CFGVertex> entries = getOutgoingEdges(entryVertex).stream()
                        .map(CFGEdge::getTarget).collect(Collectors.toSet());

                for (CFGVertex entry : entries) {
                    // exclude dummy CFGs solely consisting of entry and exit vertex
                    if (!entry.isExitVertex()) {
                        Statement statement = entry.getStatement();

                        // TODO: handle basic statements
                        if (statement instanceof BlockStatement) {
                            // each statement within a block statement is a basic statement
                            BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getFirstStatement();
                            traceToVertexCache.put(entry.getMethod() + "->entry->" + basicStatement.getInstructionIndex(), entry);
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds a mapping in the cache for method exits.
     *
     * @param traceToVertexCache The trace to vertex cache.
     */
    @SuppressWarnings("unused")
    private void handleMethodExits(Map<String, CFGVertex> traceToVertexCache) {

        // handle exit vertices
        Set<CFGVertex> exitVertices = getVertices().stream().filter(CFGVertex::isExitVertex).collect(Collectors.toSet());

        for (CFGVertex exitVertex : exitVertices) {
            // exclude global exit vertex
            if (!exitVertex.equals(graph.getExit())) {

                // virtual exit vertex
                traceToVertexCache.put(exitVertex.getMethod() + "->exit", exitVertex);

                Set<CFGVertex> exits = getIncomingEdges(exitVertex).stream()
                        .map(CFGEdge::getSource).collect(Collectors.toSet());

                for (CFGVertex exit : exits) {
                    // exclude dummy CFGs solely consisting of entry and exit vertex
                    if (!exit.isEntryVertex()) {
                        Statement statement = exit.getStatement();

                        // TODO: handle basic statements
                        if (statement instanceof BlockStatement) {
                            // each statement within a block statement is a basic statement
                            BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getLastStatement();
                            traceToVertexCache.put(exit.getMethod() + "->exit->" + basicStatement.getInstructionIndex(), exit);
                        }
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<String, CFGVertex> initTraceToVertexCache() {

        long start = System.currentTimeMillis();

        final Map<String, CFGVertex> traceToVertexCache = new HashMap<>();

        // handleMethodEntries(traceToVertexCache);
        // handleMethodExits(traceToVertexCache);

        // handle branch + if and switch stmt vertices
        for (CFGVertex branchVertex : branchVertices) {

            // a branch can potentially have multiple predecessors (shared branch)
            Set<CFGVertex> ifOrSwitchVertices = getIncomingEdges(branchVertex).stream()
                    .map(CFGEdge::getSource)
                    .filter(vertex -> vertex.isIfVertex() || vertex.isSwitchVertex())
                    .collect(Collectors.toSet());

            // if or switch vertex
            for (CFGVertex ifOrSwitchVertex : ifOrSwitchVertices) {

                Statement statement = ifOrSwitchVertex.getStatement();

                // TODO: handle basic statements
                if (statement instanceof BlockStatement) {
                    // the last statement is always a basic statement of an if vertex
                    BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getLastStatement();
                    if (InstructionUtils.isBranchingInstruction(basicStatement.getInstruction())) {
                        traceToVertexCache.put(ifOrSwitchVertex.getMethod()
                                + "->if->" + basicStatement.getInstructionIndex(), ifOrSwitchVertex);
                    } else if (InstructionUtils.isSwitchInstruction(basicStatement.getInstruction())) {
                        traceToVertexCache.put(ifOrSwitchVertex.getMethod()
                                + "->switch->" + basicStatement.getInstructionIndex(), ifOrSwitchVertex);
                    }
                    else {
                        Log.printWarning("Unexpected block statement: " + statement + " for method " + ifOrSwitchVertex.getMethod());
                    }
                }
            }

            Statement statement = branchVertex.getStatement();

            // TODO: handle basic statements
            if (statement instanceof BlockStatement) {
                // each statement within a block statement is a basic statement
                BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getFirstStatement();
                traceToVertexCache.put(branchVertex.getMethod() + "->" + basicStatement.getInstructionIndex(), branchVertex);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("TraceToVertexCache construction took: " + (end - start) + " ms.");
        Log.println("Size of TraceToVertexCache: " + traceToVertexCache.size());

        return traceToVertexCache;
    }

    /**
     * Looks up a vertex by method and instruction.
     *
     * @param method The method containing the instruction.
     * @param builderInstruction The instruction to be looked up.
     * @return Returns the vertex wrapping the given instruction.
     */
    public CFGVertex findVertexByInstruction(final Method method, final BuilderInstruction builderInstruction) {

        final Set<CFGVertex> vertices = getVertices().stream()
                .filter(vertex -> vertex.containsInstruction(method.toString(),
                        builderInstruction.getLocation().getIndex()))
                .collect(Collectors.toSet());

        if (vertices.size() == 1) {
            return vertices.stream().findAny().orElseThrow();
        } else {
            throw new NoSuchElementException("Instruction not resolvable in graph!");
        }
    }
}
