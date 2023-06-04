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
import java.util.stream.Collectors;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.ReturnStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.InstructionUtils;

public class InterCDG extends CFG {

    /**
     * Maps vertices to their corresponding traces.
     */
    private Map<CFGVertex, String> vertexToTraceMap;


    /**
     * Constructs a wrapper for a given control dependence graph.
     *
     * @param graph   The actual control dependence graph.
     * @param appsDir The path to the apps directory.
     * @param appName The name of the app (the package name).
     */
    public InterCDG(BaseCFG graph, Path appsDir, String appName) {
        super(graph, appsDir, appName);
    }

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
    public InterCDG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName) {
        super(GraphUtils.constructInterCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses), appsDir, packageName);
    }

    /**
     * Computes the approach level by finding the minimum distance between the target vertex and any covered vertex.
     *
     * @param targetVertex    vertex that should be covered.
     * @param coveredVertices set of covered vertices.
     * @return approach level toward the targeted vertex.
     */
    public Pair<CFGVertex, Integer> computeApproachLevel(CFGVertex targetVertex, List<Vertex> coveredVertices) {
        int min = Integer.MAX_VALUE;
        CFGVertex shortestPathVertex = graph.getEntry();
        for (Vertex visitedVertex : coveredVertices) {
            GraphPath<CFGVertex, CFGEdge> path = shortestPathAlgorithm.getPath((CFGVertex) visitedVertex, targetVertex);

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
        // Find the right trace record for our branching vertex.
        Set<String> branchingTraces = new HashSet<>();
        String branchVertexPattern = traceMatchingPattern(this.lookupTrace(branchVertex));
        for (String trace : traces) {
            if (trace.startsWith(branchVertexPattern)) {
                branchingTraces.add(trace);
            }
        }

        if (branchingTraces.isEmpty()) {
            throw new IllegalStateException("Could not find matching trace for branch vertex: " + branchVertex);
        }

        Set<Double> branchDistances = branchingTraces.stream().map(this::getBranchDistanceFromTrace).collect(Collectors.toSet());
        return Collections.max(branchDistances);
    }

    /**
     * Transform the trace of a vertex into a pattern that can be used to match branching traces from a test execution.
     *
     * @param branchVertexTrace vertex trace to be transformed into a comparison pattern suitable for execution traces.
     * @return pattern suitable for comparing execution traces.
     */
    private String traceMatchingPattern(String branchVertexTrace) {
        String[] splitVertexBranch = branchVertexTrace.split("->");
        return splitVertexBranch[0] + "->" + splitVertexBranch[1] + "->" + splitVertexBranch[3];
    }

    private double getBranchDistanceFromTrace(String trace) {
        final int arrow = trace.lastIndexOf('>');
        final int colon = trace.indexOf(':', arrow);

        if (colon != -1) {
            return Integer.parseUnsignedInt(trace, colon + 1, trace.length(), 10);
        } else {
            Log.printWarning("Could not infer branch distance for trace " + trace);
            return 0;
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
        vertexToTraceMap = new HashMap<>();

        // handle entry vertices
        Set<CFGVertex> entryVertices = graph.getVertices().stream().filter(CFGVertex::isEntryVertex).collect(Collectors.toSet());

        for (CFGVertex entryVertex : entryVertices) {
            // exclude global entry vertex
            if (!entryVertex.equals(graph.getEntry())) {

                // virtual entry vertex
                traceToVertexCache.put(entryVertex.getMethod() + "->entry", entryVertex);
                vertexToTraceMap.put(entryVertex, entryVertex.getMethod() + "->entry");

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
                            vertexToTraceMap.put(entry, entry.getMethod() + "->entry->" + basicStatement.getInstructionIndex());
                        }
                    }
                }
            }
        }

        // handle exit vertices
        Set<CFGVertex> exitVertices = graph.getVertices().stream().filter(CFGVertex::isExitVertex).collect(Collectors.toSet());

        for (CFGVertex exitVertex : exitVertices) {
            // exclude global exit vertex
            if (!exitVertex.equals(graph.getExit())) {

                // virtual exit vertex
                traceToVertexCache.put(exitVertex.getMethod() + "->exit", exitVertex);
                vertexToTraceMap.put(exitVertex, exitVertex.getMethod() + "->exit");

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
                            vertexToTraceMap.put(exit, exit.getMethod() + "->exit->" + basicStatement.getInstructionIndex());
                        }
                    }
                }
            }
        }

        // handle branch + if and switch stmt vertices
        for (CFGVertex branchVertex : branchVertices) {

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
                        vertexToTraceMap.put(ifOrSwitchVertex, ifOrSwitchVertex.getMethod()
                                + "->if->" + basicStatement.getInstructionIndex());
                    } else if (InstructionUtils.isSwitchInstruction(basicStatement.getInstruction())) {
                        traceToVertexCache.put(ifOrSwitchVertex.getMethod()
                                + "->switch->" + basicStatement.getInstructionIndex(), ifOrSwitchVertex);
                        vertexToTraceMap.put(ifOrSwitchVertex, ifOrSwitchVertex.getMethod()
                                + "->switch->" + basicStatement.getInstructionIndex());
                    } else {
                        Log.printWarning("Unexpected block statement: " + statement + " for method " + ifOrSwitchVertex.getMethod());
                    }
                }
            }

            Statement statement = branchVertex.getStatement();

            // TODO: handle basic statements
            if (statement instanceof BlockStatement) {
                Statement firstStatement = ((BlockStatement) statement).getFirstStatement();

                // each statement within a block statement is a basic statement
                if (firstStatement.getType() != Statement.StatementType.RETURN_STATEMENT) {
                    BasicStatement basicStatement = (BasicStatement) firstStatement;
                    traceToVertexCache.put(branchVertex.getMethod() + "->" + basicStatement.getInstructionIndex(), branchVertex);
                    vertexToTraceMap.put(branchVertex, branchVertex.getMethod() + "->" + basicStatement.getInstructionIndex());
                }
                // Special handling for Return statements since they have no instruction index.
                else if (firstStatement.getType() == Statement.StatementType.RETURN_STATEMENT) {
                    ReturnStatement returnStatement = (ReturnStatement) firstStatement;
                    traceToVertexCache.put(branchVertex.getMethod() + "->" + returnStatement.getTargetMethod(), branchVertex);
                    vertexToTraceMap.put(branchVertex, branchVertex.getMethod() + "->" + returnStatement.getTargetMethod());
                }
            }
        }

        long end = System.currentTimeMillis();
        Log.println("TraceToVertexCache construction took: " + (end - start) + " ms.");
        Log.println("Size of TraceToVertexCache: " + traceToVertexCache.size());

        return traceToVertexCache;
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
     * @param vertex {@link CFGVertex} whose trace string is to be determined.
     * @return trace string corresponding to the supplied {@link CFGVertex}
     */
    public String lookupTrace(CFGVertex vertex) {
        return vertexToTraceMap.get(vertex);
    }
}
