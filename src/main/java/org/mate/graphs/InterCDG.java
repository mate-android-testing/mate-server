package org.mate.graphs;

import org.jgrapht.GraphPath;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.InstructionUtils;

public class InterCDG extends CFG {

    /**
     * Constructs an inter-procedural CFG with the given properties.
     *
     * @param apkPath               The path to the APK file.
     * @param useBasicBlocks        Whether basic blocks should be used or not.
     * @param excludeARTClasses     Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param appsDir               The apps directory.
     * @param packageName           The package name of the AUT.
     */
    public InterCDG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses, Path appsDir, String packageName) {
        super(GraphUtils.constructInterCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses), appsDir, packageName);
    }

    /**
     * Computes the approach level by finding the minimum distance between the target vertex and any covered vertex.
     *
     * @param targetVertex    vertex that should be covered.
     * @param coveredVertices set of covered vertices.
     * @return approach level toward the targeted vertex.
     */
    public Pair<CFGVertex, Integer> computeApproachLevel(CFGVertex targetVertex, Set<CFGVertex> coveredVertices) {
        int min = Integer.MAX_VALUE;
        CFGVertex shortestPathVertex = graph.getEntry();
        for (CFGVertex visitedVertex : coveredVertices) {
            GraphPath<CFGVertex, CFGEdge> path = shortestPathAlgorithm.getPath(visitedVertex, targetVertex);

            // Check if there exists a path.
            if (path != null) {
                int length = path.getLength();
                if (length < min) {
                    min = length;
                    shortestPathVertex = (CFGVertex) visitedVertex;
                }
            }
        }

        // The approach level has an offset of -1, since the approach level is zero if a direct parent is covered.
        return new Pair<>(shortestPathVertex, min - 1);
    }

    /**
     * Computes the branch distance on the supplied branching vertex.
     *
     * @param branchVertex vertex on which the branch distance is to be determined.
     * @param traces       collected trace from the executed chromosome.
     * @return branch distance on the supplied branching vertex.
     */
    public double computeBranchDistance(CFGVertex branchVertex, List<String> traces) {
        // In Android control dependencies might not always be related to branches.
        if (!branchVertices.contains(branchVertex)) {
            return 0.0;
        }

        // Find the right trace record for our branching vertex.
        int currIndex = -1;
        double branchValue = -1;
        Set<String> potentialVertices = this.lookupTrace(traces, branchVertex).stream().map(this::transformToComparePattern).collect(Collectors.toSet());

        for (String trace : traces) {
            String tracePattern = transformToComparePattern(trace);
            if (potentialVertices.contains(tracePattern) && trace.contains(":")) {

                // We may have multiple indicators within several branches. Thus, we search for the deepest branch with
                // the highest branchDistance.
                String lastArrowElement = trace.split("->")[2];
                if (branchValue == -1) {
                    currIndex = Integer.parseInt(lastArrowElement.split(":")[0]);
                    branchValue = Double.parseDouble(lastArrowElement.split(":")[1]);
                } else {
                    int traceIndex = Integer.parseInt(lastArrowElement.split(":")[0]);
                    double traceBranchValue = Double.parseDouble(lastArrowElement.split(":")[1]);

                    if (traceIndex > currIndex || (traceIndex == currIndex && traceBranchValue > branchValue)) {
                        currIndex = traceIndex;
                        branchValue = traceBranchValue;
                    }

                }
            }
        }

        return branchValue;
    }

    /**
     * Transform the trace of a vertex into a pattern that can universally be used for comparing traces.
     *
     * @param trace vertex trace to be transformed into a comparison pattern.
     * @return pattern suitable for comparing execution traces.
     */
    private String transformToComparePattern(String trace) {
        String[] splitTrace = trace.split(":")[0].split("->");
        if (splitTrace.length == 3) {
            return String.join("->", splitTrace);
        } else {
            return splitTrace[0] + "->" + splitTrace[1] + "->" + splitTrace[3];
        }
    }


    /**
     * Pre-computes a mapping between certain traces and its vertices in the graph.
     *
     * @return Returns a mapping between a trace and its vertex in the graph.
     */
    public Map<String, CFGVertex> initTraceToVertexCache() {
        long start = System.currentTimeMillis();

        Map<String, CFGVertex> traceToVertexCache = new HashMap<>();

        for (CFGVertex vertex : getVertices()) {
            // Handle entry vertices
            if (vertex.isEntryVertex()) {
                initEntryVertexToVertexCache(vertex, traceToVertexCache);
            }

            // Handle exit vertices
            else if (vertex.isExitVertex()) {
                initExitVertexToVertexCache(vertex, traceToVertexCache);
            }

            // Handle branch vertices
            else if (branchVertices.contains(vertex)) {
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
     * @param entryVertex        entry vertex to be added to the trace to vertex cache.
     * @param traceToVertexCache the mapping to which the given entry vertex is to be added.
     */
    private void initEntryVertexToVertexCache(CFGVertex entryVertex, Map<String, CFGVertex> traceToVertexCache) {
        // exclude global entry vertex
        if (!entryVertex.equals(graph.getEntry())) {

            // virtual entry vertex
            traceToVertexCache.put(entryVertex.getMethod() + "->entry", entryVertex);

            // there are potentially several entry vertices when dealing with try-catch blocks at the beginning
            Set<CFGVertex> entries = graph.getOutgoingEdges(entryVertex).stream()
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

    /**
     * Initialises the trace to vertex mapping for exit vertices.
     *
     * @param exitVertex         exit vertex to be added to the trace to vertex cache.
     * @param traceToVertexCache the mapping to which the given exit vertex is to be added.
     */
    private void initExitVertexToVertexCache(CFGVertex exitVertex, Map<String, CFGVertex> traceToVertexCache) {
        if (!exitVertex.equals(graph.getExit())) {

            // virtual exit vertex
            traceToVertexCache.put(exitVertex.getMethod() + "->exit", exitVertex);

            Set<CFGVertex> exits = graph.getIncomingEdges(exitVertex).stream()
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

    /**
     * Initialises the trace to vertex mapping for branch vertices.
     *
     * @param branchVertex       branch vertex to be added to the trace to vertex cache.
     * @param traceToVertexCache the mapping to which the given branch vertex is to be added.
     */
    private void initBranchVertexToVertexCache(CFGVertex branchVertex, Map<String, CFGVertex> traceToVertexCache) {
        // a branch can potentially have multiple predecessors (shared branch)
        Set<CFGVertex> ifOrSwitchVertices = graph.getIncomingEdges(branchVertex).stream()
                .map(CFGEdge::getSource).filter(CFGVertex::isIfVertex).collect(Collectors.toSet());

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
            Statement firstStatement = ((BlockStatement) statement).getFirstStatement();

            // Find first basic statement in given statement to infer the instruction index.
            BasicStatement basicStatement;
            if (firstStatement.getType() != Statement.StatementType.RETURN_STATEMENT) {
                basicStatement = (BasicStatement) firstStatement;
            } else {
                basicStatement = (BasicStatement) ((BlockStatement) statement).getStatements().get(1);
            }

            traceToVertexCache.put(branchVertex.getMethod() + "->" + basicStatement.getInstructionIndex(), branchVertex);
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
     * Fetches the corresponding trace string for the supplied vertex.
     *
     * @param vertex {@link CFGVertex} whose trace string is to be determined.
     * @return trace string corresponding to the supplied {@link CFGVertex}
     */
    public Set<String> lookupTrace(List<String> traces, CFGVertex vertex) {
        Set<String> vertexTraces = Collections.newSetFromMap(new ConcurrentHashMap<>());
        traces.parallelStream().forEach(trace -> {
            CFGVertex currVertex = lookupVertex(trace);
            if (currVertex != null && currVertex.equals(vertex)) {
                vertexTraces.add(trace);
            }
        });
        return vertexTraces;
    }

    /**
     * Determines which nodes have been covered according to a list of chromosome traces.
     *
     * @param traces resulting from a chromosome execution.
     * @return the set of covered vertices.
     */
    public Set<CFGVertex> getCoveredVertices(Set<String> traces) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        // we need to mark vertices we visited
        Set<CFGVertex> covered = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Set<CFGVertex> notCovered = new HashSet<>(getVertices());

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
                final CFGVertex visitedEntry = lookupVertex(entryTrace);

                if (visitedEntry != null) {
                    covered.add(visitedEntry);
                    notCovered.remove(visitedEntry);
                } else {
                    Log.printWarning("Couldn't derive vertex for entry trace: " + entryTrace);
                }
            }

            // mark virtual exit
            final String exitMarker = "->exit";
            final int exitIndex = trace.indexOf(exitMarker);
            if (exitIndex != -1) {
                final String exitTrace = trace.substring(0, exitIndex + exitMarker.length());
                final CFGVertex visitedExit = lookupVertex(exitTrace);

                if (visitedExit != null) {
                    covered.add(visitedExit);
                    notCovered.remove(visitedExit);
                } else {
                    Log.printWarning("Couldn't derive vertex for exit trace: " + exitTrace);
                }
            }

            // mark actual vertex corresponding to trace
            CFGVertex visitedVertex = lookupVertex(trace);

            if (visitedVertex == null) {
                Log.printWarning("Couldn't derive vertex for trace: " + trace);
            } else {
                covered.add(visitedVertex);
                notCovered.remove(visitedVertex);
            }
        });

        // The mapping from traces to vertices misses virtual nodes. Thus, we now go over virtual entry and exit nodes
        // and check whether one of their predecessors has been covered.
        notCovered.parallelStream().forEach(vertex -> {
            // Virtual Entry and Exit vertices are covered if one of their ancestors has been covered as well.
            if (vertex.isEntryVertex() || vertex.isExitVertex()) {
                Set<CFGVertex> successors = graph.getPredecessors(vertex);
                if (!Collections.disjoint(successors, covered)) {
                    covered.add(vertex);
                }
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping traces to vertices took: " + (end - start) + " ms.");

        Log.println("Number of visited vertices: " + covered.size());
        return covered;
    }
}
