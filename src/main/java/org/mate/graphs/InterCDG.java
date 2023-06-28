package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.InstructionUtils;
import org.jgrapht.GraphPath;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents an inter-procedural CDG.
 */
public class InterCDG extends CFG {

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
     * Computes the approach level by finding the minimum distance between the branch vertex (target) and the closest
     * covered if or switch vertex.
     *
     * @param branchVertex The branch vertex (target).
     * @param coveredVertices The set of covered vertices.
     * @return Returns the approach level between the target vertex and the closest if or switch vertex as well as the
     *         closest if or switch vertex itself. If no if or switch vertex has been covered toward the target vertex
     *         {@code null} is returned.
     */
    public Pair<CFGVertex, Integer> computeApproachLevel(final CFGVertex branchVertex, final Set<CFGVertex> coveredVertices) {

        int min = Integer.MAX_VALUE;
        CFGVertex missedBranchVertex = null;

        // Find the closest covered if or switch vertex.
        for (CFGVertex visitedVertex : coveredVertices) {

            GraphPath<CFGVertex, CFGEdge> path = shortestPathAlgorithm.getPath(visitedVertex, branchVertex);

            // Check if there exists a path.
            if (path != null) {
                int length = path.getLength();
                if (length < min) {

                    if (path.getStartVertex().isSwitchVertex()) {
                        // The branch distance (trace) of switch vertices is actually attached to the case statement,
                        // which corresponds to the second vertex on the path list, right after the switch vertex itself.
                        missedBranchVertex = path.getVertexList().get(1);
                        min = length;
                    } else if (path.getStartVertex().isIfVertex()) {
                        // The branch distance (trace) of if vertices is directly attached to the if statement, which
                        // corresponds to the first vertex on the path list.
                        missedBranchVertex = path.getStartVertex();
                        min = length;
                    }
                }
            }
        }

        // The approach level has an offset of -1, since the approach level is zero if a direct parent is covered.
        return new Pair<>(missedBranchVertex, min - 1);
    }

    /**
     * Computes the branch distance for the given if or switch vertex.
     *
     * @param ifOrSwitchVertex The nearest covered if or switch vertex.
     * @param traces The collected traces.
     * @return Returns the branch distance of the missed branching vertex.
     */
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
     * Pre-computes a mapping between certain traces and its vertices in the graph.
     *
     * @return Returns a mapping between a trace and its vertex in the graph.
     */
    protected Map<String, CFGVertex> initTraceToVertexCache() {

        long start = System.currentTimeMillis();

        final Map<String, CFGVertex> traceToVertexCache = new HashMap<>();

        for (CFGVertex vertex : getVertices()) {

            if (vertex.isEntryVertex()) { // Handle entry vertices
                initEntryVertexToVertexCache(vertex, traceToVertexCache);
            } else if (vertex.isExitVertex()) { // Handle exit vertices
                initExitVertexToVertexCache(vertex, traceToVertexCache);
            } else if (branchVertices.contains(vertex)) { // Handle branch vertices
                initBranchVertexToVertexCache(vertex, traceToVertexCache);
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
    private void initEntryVertexToVertexCache(CFGVertex entryVertex, Map<String, CFGVertex> traceToVertexCache) {

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
                        int index = getInstructionIndexFromBlockStatement(statement);
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
                        int index = getInstructionIndexFromBlockStatement(statement);
                        traceToVertexCache.put(exit.getMethod() + "->entry->" + index, exit);
                    }
                }
            }
        }
    }

    /**
     * Initialises the trace to vertex mapping for branch vertices.
     *
     * @param branchVertex The branch vertex that will be added to the trace to vertex cache mapping.
     * @param traceToVertexCache The trace to vertex mapping.
     */
    private void initBranchVertexToVertexCache(final CFGVertex branchVertex, final Map<String, CFGVertex> traceToVertexCache) {

        // a branch can potentially have multiple predecessors (shared branch)
        final Set<CFGVertex> ifOrSwitchVertices = graph.getIncomingEdges(branchVertex).stream()
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
                } else {
                    Log.printWarning("Unexpected block statement: " + statement + " for method " + ifOrSwitchVertex.getMethod());
                }
            }
        }

        Statement statement = branchVertex.getStatement();

        // TODO: handle basic statements
        if (statement instanceof BlockStatement) {
            int index = getInstructionIndexFromBlockStatement(statement);
            traceToVertexCache.put(branchVertex.getMethod() + "->entry->" + index, branchVertex);
        }
    }


    /**
     * Maps the given list of branches to the corresponding vertices in the graph.
     *
     * @param branches The list of branches that should be mapped to vertices.
     * @return Returns the branch vertices.
     */
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
     * Extracts the instruction index from a given {@link Statement}.
     *
     * @param statement The statement from which the instruction index is to be extracted.
     * @return The instruction index of the given statement.
     */
    private int getInstructionIndexFromBlockStatement(final Statement statement) {
        final Statement firstStatement = ((BlockStatement) statement).getFirstStatement();
        BasicStatement basicStatement;
        if (firstStatement.getType() != Statement.StatementType.RETURN_STATEMENT) {
            basicStatement = (BasicStatement) firstStatement;
        } else {
            basicStatement = (BasicStatement) ((BlockStatement) statement).getStatements().get(1);
        }
        return basicStatement.getInstructionIndex();
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
