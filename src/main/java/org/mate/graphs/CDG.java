package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import org.jgrapht.GraphPath;
import org.mate.graphs.util.Util;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * An abstract control dependence graph (CDG).
 */
public abstract class CDG extends CFG {

    /**
     * Constructs a control dependence graph (CDG).
     *
     * @param graph The actual control dependence graph.
     * @param appsDir The path to the apps directory.
     * @param appName The name of the app (the package name).
     */
    public CDG(BaseCFG graph, Path appsDir, String appName) {
        super(graph, appsDir, appName);
    }

    /**
     * Maps the given list of branches to the corresponding vertices in the graph.
     *
     * @param branches The list of branches that should be mapped to vertices.
     * @return Returns the branch vertices.
     */
    @Override
    protected List<CFGVertex> mapBranchesToVertices(List<String> branches) {

        long start = System.currentTimeMillis();

        List<CFGVertex> branchVertices = Collections.synchronizedList(new ArrayList<>());

        branches.forEach(branch -> {

            CFGVertex branchVertex = lookupVertex(branch);

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
     * Pre-computes a mapping between certain traces and its vertices in the graph.
     *
     * @return Returns a mapping between a trace and its vertex in the graph.
     */
    @Override
    protected Map<String, CFGVertex> initTraceToVertexCache() {

        long start = System.currentTimeMillis();

        final Map<String, CFGVertex> traceToVertexCache = new HashMap<>();

        for (CFGVertex vertex : getVertices()) {

            if (vertex.isEntryVertex()) { // Handle entry vertices
                initEntryVertexToVertexCache(vertex, traceToVertexCache);
            } else if (vertex.isExitVertex()) { // Handle exit vertices
                initExitVertexToVertexCache(vertex, traceToVertexCache);
            } else { // Handle branch vertices
                initStatementVertexToVertexCache(vertex, traceToVertexCache);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("TraceToVertexCache construction took: " + (end - start) + " ms.");
        Log.println("Size of TraceToVertexCache: " + traceToVertexCache.size());

        return traceToVertexCache;
    }

    /**
     * Initialises the trace to vertex mapping for entry vertices.
     *
     * @param entryVertex The entry vertex that will be added to the trace to vertex cache mapping.
     * @param traceToVertexCache The trace to vertex mapping.
     */
    private void initEntryVertexToVertexCache(final CFGVertex entryVertex, final Map<String, CFGVertex> traceToVertexCache) {

        // exclude global entry vertex
        if (!entryVertex.equals(graph.getEntry())) {

            // virtual entry vertex
            traceToVertexCache.put(entryVertex.getMethod() + "->entry", entryVertex);

            // there are potentially several entry vertices when dealing with try-catch blocks at the beginning
            final Set<CFGVertex> entries = graph.getOutgoingEdges(entryVertex).stream()
                    .map(CFGEdge::getTarget).collect(Collectors.toSet());

            for (CFGVertex entry : entries) {

                // Exclude dummy CFGs solely consisting of entry and exit vertex.
                if (!entry.isExitVertex()) {
                    final Statement statement = entry.getStatement();

                    // TODO: handle basic statements
                    if (statement instanceof BlockStatement) {
                        int index = Util.getInstructionIndexFromBlockStatement(statement);
                        traceToVertexCache.put(entry.getMethod() + "->entry->" + index, entry);
                    }
                }
            }
        }
    }

    /**
     * Initialises the trace to vertex mapping for exit vertices.
     *
     * @param exitVertex The exit vertex that will be added to the trace to vertex cache mapping.
     * @param traceToVertexCache The trace to vertex mapping.
     */
    private void initExitVertexToVertexCache(final CFGVertex exitVertex, final Map<String, CFGVertex> traceToVertexCache) {

        if (!exitVertex.equals(graph.getExit())) {

            // virtual exit vertex
            traceToVertexCache.put(exitVertex.getMethod() + "->exit", exitVertex);

            final Set<CFGVertex> exits = graph.getIncomingEdges(exitVertex).stream()
                    .map(CFGEdge::getSource).collect(Collectors.toSet());

            for (CFGVertex exit : exits) {

                // Exclude dummy CFGs solely consisting of entry and exit vertex.
                if (!exit.isEntryVertex()) {
                    final Statement statement = exit.getStatement();

                    // TODO: handle basic statements
                    if (statement instanceof BlockStatement) {
                        int index = Util.getInstructionIndexFromBlockStatement(statement);
                        traceToVertexCache.put(exit.getMethod() + "->exit->" + index, exit);
                    }
                }
            }
        }
    }

    /**
     * Initialises the trace to vertex mapping for branch vertices.
     *
     * @param vertex The statement vertex that will be added to the trace to vertex cache mapping.
     * @param traceToVertexCache The trace to vertex mapping.
     */
    private void initStatementVertexToVertexCache(final CFGVertex vertex, final Map<String, CFGVertex> traceToVertexCache) {
        BlockStatement statement = (BlockStatement) vertex.getStatement();
        int index = Util.getInstructionIndexFromBlockStatement(statement);
        int operationCount = statement.getStatements().size();
        String branchSuffix = vertex.isBranchVertex() ? "isBranch" : "noBranch";
        traceToVertexCache.put(vertex.getMethod() + "->" + index + "->" + operationCount + "->" + branchSuffix, vertex);
    }

    /**
     * Computes the approach level by finding the minimum distance between the target vertex and the closest
     * covered if or switch vertex.
     *
     * @param targetVertex The target vertex.
     * @param coveredVertices The set of covered vertices.
     * @return Returns the approach level between the targeted vertex and the closest if or switch vertex.
     */
    public Pair<CFGVertex, Integer> computeApproachLevel(final CFGVertex targetVertex, final Set<CFGVertex> coveredVertices) {

        final Queue<CFGVertex> parentQueue = new LinkedList<>(graph.getPredecessors(targetVertex));
        final Set<CFGVertex> visited = new HashSet<>();
        CFGVertex curr = parentQueue.poll();

        // Iterate over parents that are increasingly further away from the targetVertex.
        while (curr != null) {
            visited.add(curr);

            // Since we traverse over increasingly further away vertices, we can stop at the first covered vertex.
            if (coveredVertices.contains(curr)) {
                GraphPath<CFGVertex, CFGEdge> path = shortestPathAlgorithm.getPath(curr, targetVertex);
                // The approach level has an offset of -1, since the approach level is zero if a direct parent is covered.
                int approachLevel = path.getLength() - 1;

                if (path.getStartVertex().isSwitchVertex()) {
                    // The branch distance (trace) of switch vertices is actually attached to the case statement,
                    // which corresponds to the second vertex on the path list, right after the switch vertex itself.
                    return new Pair<>(path.getVertexList().get(1), approachLevel);
                } else if (path.getStartVertex().isIfVertex()) {
                    // The branch distance (trace) of if vertices is directly attached to the if statement, which
                    // corresponds to the first vertex on the path list.
                    return new Pair<>(path.getStartVertex(), approachLevel);
                } else if (curr.toString().startsWith("Connect->")) {
                    // Last vertex corresponds to virtual disconnect vertex added to the graph due to loose subgraphs.
                    return new Pair<>(curr, Integer.MAX_VALUE);
                } else {
                    // regular basic block
                    return new Pair<>(path.getStartVertex(), approachLevel);
                }
            } else {

                // Update the queue with all non-visited predecessors of the current vertex.
                for (CFGVertex predecessors : graph.getPredecessors(curr)) {
                    if (!visited.contains(predecessors)) {
                        parentQueue.offer(predecessors);
                    }
                }
                curr = parentQueue.poll();
            }
        }

        // We were unable to find a predecessor that was covered.
        Log.printWarning("Unable to find missed branch vertex for target vertex in method " + targetVertex.getMethod());
        return new Pair<>(null, Integer.MAX_VALUE);
    }

    /**
     * Computes the branch distance for the given if or switch vertex.
     *
     * @param missedBranchVertex The nearest covered vertex where we took a wrong branch towards our target.
     * @param traces The collected traces.
     * @return Returns the branch distance of the missed branching vertex.
     */
    public double computeBranchDistance(final CFGVertex missedBranchVertex, final List<String> traces) {

        // Return a default value of 1.0 if we were not able to find an appropriate missedBranchVertex.
        if (missedBranchVertex == null
                || missedBranchVertex.getStatement().toString().equals("entry global")
                || !(missedBranchVertex.getStatement() instanceof BlockStatement)) {
            return 1.0;
        }

        final Set<String> branchDistanceTraces = traces.stream()
                .filter(trace -> trace.contains(":"))
                .filter(trace -> trace.startsWith(missedBranchVertex.getMethod()))
                .collect(Collectors.toSet());

        final Set<Double> branchDistances = new HashSet<>();
        int index;
        int maxIndex;

        BlockStatement blockStatement = (BlockStatement) missedBranchVertex.getStatement();
        index = Util.getInstructionIndexFromBlockStatement(blockStatement);
        maxIndex = index + blockStatement.getStatements().size() - 1;

        // Search for the right branch trace and extract the corresponding branch distance if found.
        for (String branchDistanceTrace : branchDistanceTraces) {
            // A token looks as follows: className->methodName->instructionIndex:branchDistance
            final String[] tokens = branchDistanceTrace.split("->");
            int traceIndex = Integer.parseInt(tokens[2].split(":")[0]);
            double branchDistance = Double.parseDouble(tokens[2].split(":")[1]);
            if (traceIndex >= index && traceIndex <= maxIndex && branchDistance > 0) {
                branchDistances.add(branchDistance);
            }
        }

        // TODO: Why cause virtual return statements problems? They are not branching vertices?
        if (branchDistances.isEmpty()) {
            return 1.0;
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
