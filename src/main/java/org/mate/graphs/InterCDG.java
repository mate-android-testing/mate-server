package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.EntryStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import org.jgrapht.GraphPath;
import org.mate.graphs.util.Util;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Represents an inter-procedural CDG.
 */
public class InterCDG extends CDG {

    /**
     * Constructs an inter-procedural CDG with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param useBasicBlocks Whether basic blocks should be used or not.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     */
    public InterCDG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName) {
        super(GraphUtils.constructInterCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses),
                appsDir, packageName);
    }

    /**
     * Maps the given set of traces to vertices in the graph.
     *
     * @param traces The set of traces that should be mapped to vertices.
     * @return Returns the vertices described by the given set of traces.
     */
    @Override
    public List<CFGVertex> lookupVertices(final List<String> traces) {

        long start = System.currentTimeMillis();

        // we need to mark vertices we visited
        final Set<CFGVertex> visitedVertices = Collections.newSetFromMap(new ConcurrentHashMap<CFGVertex, Boolean>());

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

                // mark additional vertices described by the basic block trace (caused through splitting of basic blocks)
                if (!visitedVertex.isEntryVertex() && !visitedVertex.isExitVertex()) {
                    lookupBasicBlockVertices(visitedVertices, trace);
                }
            }
        });

        // markCoveredEntriesAndExits(visitedVertices);

        long end = System.currentTimeMillis();
        Log.println("Mapping traces to vertices took: " + (end - start) + " ms.");

        Log.println("Number of visited vertices: " + visitedVertices.size());
        return new ArrayList<>(visitedVertices);
    }

    /**
     * The (basic block) traces provided through the instrumentation can potentially diverge from the basic blocks in
     * the graph. This happens because we split basic blocks upon certain invoke instructions into smaller basic blocks.
     * In order to mark not only a single vertex but all vertices described through the basic block trace we need to
     * check the instruction indices falling into the range of the basic block.
     *
     * @param visitedVertices The set of visited vertices.
     * @param trace The execution trace that could not be mapped to a vertex.
     * @return Returns the number of added vertices to the set of visited vertices.
     */
    private int lookupBasicBlockVertices(final Set<CFGVertex> visitedVertices, final String trace) {

        int foundVertices = 0;
        final String[] tokens = trace.split("->"); // className->methodName->basicBlockPosition->basicBlockSize->...

        // Derive boundaries of basic block.
        int startIndex = Integer.parseInt(tokens[2]);
        int endIndex = startIndex + Integer.parseInt(tokens[3]) - 1;

        // Derive the subgraph (set of vertices) described by the given trace.
        final String methodSignature = tokens[0] + "->" + tokens[1];
        final Set<CFGVertex> subGraph = getVertices()
                .stream()
                .filter(vertex -> vertex.getMethod().contains(methodSignature))
                .collect(Collectors.toSet());

        // Mark the vertices described through the basic block as visited.
        for (final CFGVertex vertex : subGraph) {
            final Statement statement = vertex.getStatement();
            if (statement instanceof BlockStatement) {
                final List<Statement> statements = ((BlockStatement) statement).getStatements();
                int currStart = Util.getInstructionIndexFromBlockStatement(statement);
                int currEnd = ((BasicStatement) statements.get(statements.size() - 1)).getInstructionIndex();
                if (currStart >= startIndex && currEnd <= endIndex) {
                    visitedVertices.add(vertex);
                    foundVertices++;
                }
            } else if (statement instanceof EntryStatement && startIndex == 0) {
                visitedVertices.add(vertex);
                foundVertices++;
            }
        }
        return foundVertices;
    }

    // TODO: Re-use computeApproachLevel & computeBranchDistance from base class.

    /**
     * Computes the approach level by finding the minimum distance between the branch vertex (target) and the closest
     * covered vertex. Also keeps track of the closest covered if or switch vertex, which is relevant for the branch
     * distance computation.
     *
     * @param branchVertex The branch vertex (target).
     * @param coveredVertices The set of covered vertices.
     * @return Returns the approach level between the target vertex and the closest covered vertex as well as the
     *         closest if or switch vertex. If no if or switch vertex has been covered toward the target vertex
     *         {@code null} is returned.
     */
    @Override
    public Pair<CFGVertex, Integer> computeApproachLevel(final CFGVertex branchVertex, final Set<CFGVertex> coveredVertices) {

        int minPathLength = Integer.MAX_VALUE;
        CFGVertex closestIfOrSwitchVertex = null;

        // Find the closest covered if or switch vertex if any exists.
        for (CFGVertex visitedVertex : coveredVertices) {

            GraphPath<CFGVertex, CFGEdge> path = shortestPathAlgorithm.getPath(visitedVertex, branchVertex);

            // Check if there exists a path.
            if (path != null) {
                int length = path.getLength();
                if (length < minPathLength) {

                    if (path.getStartVertex().isSwitchVertex()) {
                        // The branch distance (trace) of switch vertices is actually attached to the case statement,
                        // which corresponds to the second vertex on the path list, right after the switch vertex itself.
                        closestIfOrSwitchVertex = path.getVertexList().get(1);
                        minPathLength = length;
                    } else if (path.getStartVertex().isIfVertex()) {
                        // The branch distance (trace) of if vertices is directly attached to the if statement, which
                        // corresponds to the first vertex on the path list.
                        closestIfOrSwitchVertex = path.getStartVertex();
                        minPathLength = length;
                    } else {
                        // We just came closer to the target vertex but since it doesn't represent an if or switch vertex
                        // it is irrelevant for the branch distance computation.
                        minPathLength = length;
                    }
                }
            }
        }

        // The approach level has an offset of -1, since the approach level is zero if a direct parent is covered.
        return new Pair<>(closestIfOrSwitchVertex, minPathLength - 1);
    }

    /**
     * Computes the branch distance for the given if or switch vertex.
     *
     * @param ifOrSwitchVertex The nearest covered if or switch vertex.
     * @param traces The collected traces.
     * @return Returns the branch distance of the missed branching vertex.
     */
    @Override
    public double computeBranchDistance(final CFGVertex ifOrSwitchVertex, final List<String> traces) {

        final Set<String> branchDistanceTraces = traces.stream()
                .filter(trace -> trace.contains(":"))
                .collect(Collectors.toSet());

        final Set<Double> branchDistances = new HashSet<>();

        // Search for the right branch trace and extract the corresponding branch distance if found.
        for (String branchDistanceTrace : branchDistanceTraces) {
            // A token looks as follows: className->methodName->instructionIndex:branchDistance
            final String[] tokens = branchDistanceTrace.split(":");
            if (lookupVertex(tokens[0]).equals(ifOrSwitchVertex)) {
                double branchDistance = Double.parseDouble(tokens[1]);
                if (branchDistance > 0) { // A branch distance of 0 represents the covered branch.
                    branchDistances.add(branchDistance);
                }
            }
        }

        // An if statement might have been visited multiple times and the branch distance could have changed, so we need
        // to pick the minimal branch distance > 0.
        return normalise(Collections.min(branchDistances));
    }

    /**
     * Normalises the given double into the range [0, 1].
     *
     * @param value The double to be normalised.
     * @return The normalised double value.
     */
    private double normalise(double value) {
        return value / (value + 1);
    }
}
