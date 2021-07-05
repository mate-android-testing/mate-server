package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Edge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.InstructionUtils;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.mate.graphs.util.VertexPair;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class CFG implements Graph {

    protected final BaseCFG baseCFG;
    private final String appName;

    // cache the list of branches (the order must be consistent when requesting the branch distance vector)
    protected List<Vertex> branchVertices;

    // the search algorithm (bi-directional dijkstra seems to be the fastest one)
    private final ShortestPathAlgorithm<Vertex, Edge> dijkstra;

    // the path to the apps dir
    protected final Path appsDir;

    private static final String BRANCHES_FILE = "branches.txt";

    /**
     * Contains a mapping between a trace and its vertex within the graph. The mapping
     * is only defined for traces describing branches, if statements and entry/exit statements.
     * A look up of single vertices is quite expensive and this map should speed up the mapping process.
     */
    private Map<String, Vertex> vertexMap;

    // cache already computed distances to the target vertex
    private final Map<VertexPair, Integer> cachedDistances = new ConcurrentHashMap<>();

    /**
     * Constructs a wrapper for a given control-flow graph.
     *
     * @param baseCFG The actual control flow graph.
     * @param appsDir The path to the apps directory.
     * @param appName The name of the app (the package name).
     */
    public CFG(BaseCFG baseCFG, Path appsDir, String appName) {
        this.baseCFG = baseCFG;
        this.appName = appName;
        this.appsDir = appsDir;
        this.vertexMap = new HashMap<>();
        branchVertices = initBranchVertices();
        dijkstra = baseCFG.initBidirectionalDijkstraAlgorithm();
        vertexMap = initVertexMap();
    }

    /**
     * Retrieves the list of branch vertices, those that could be actually instrumented.
     *
     * @return Returns the branch vertices.
     */
    private List<Vertex> initBranchVertices() {

        Path appDir = appsDir.resolve(appName);
        File branchesFile = appDir.resolve(BRANCHES_FILE).toFile();

        List<String> branches = new ArrayList<>();

        try (Stream<String> stream = Files.lines(branchesFile.toPath(), StandardCharsets.UTF_8)) {
            // hopefully this preserves the order (remove blank line at end)
            branches.addAll(stream.filter(line -> line.length() > 0).collect(Collectors.toList()));
        } catch (IOException e) {
            Log.printError("Reading branches.txt failed!");
            throw new IllegalStateException(e);
        }

        return mapBranchesToVertices(branches);
    }

    /**
     * Maps the given list of branches to the corresponding vertices in the graph.
     *
     * @param branches The list of branches that should be mapped to vertices.
     * @return Returns the branch vertices.
     */
    private List<Vertex> mapBranchesToVertices(List<String> branches) {

        long start = System.currentTimeMillis();

        List<Vertex> branchVertices = Collections.synchronizedList(new ArrayList<>());

        branches.parallelStream().forEach(branch -> {

            Vertex branchVertex = lookupVertex(branch);

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
     * Checks whether the given vertex is reachable from the global entry point.
     *
     * @param vertex The vertex to be checked for reachability.
     * @return Returns whether the given vertex is reachable or not.
     */
    @Override
    public boolean isReachable(Vertex vertex) {
        return dijkstra.getPath(baseCFG.getEntry(), vertex) != null;
    }

    /**
     * Returns the vertices contained in the graph.
     *
     * @return Returns all vertices in the graph.
     */
    @Override
    public List<Vertex> getVertices() {
        return new ArrayList<>(baseCFG.getVertices());
    }

    /**
     * Draws the graph if it is not too big.
     */
    @Override
    public void draw(File outputPath) {
        baseCFG.drawGraph(outputPath);
    }

    /**
     * Draws the graph where target and visited vertices are marked in different colors:
     * The visited vertices get marked in green.
     * The uncovered target vertices get marked in red.
     * The covered target vertices get marked in orange.
     *
     * @param outputPath      The output directory.
     * @param visitedVertices The list of visited vertices.
     * @param targets         The list of target vertices.
     */
    @Override
    public void draw(File outputPath, Set<Vertex> visitedVertices, Set<Vertex> targets) {
        baseCFG.drawGraph(outputPath, visitedVertices, targets);
    }

    /**
     * Pre-computes a mapping between certain traces and its vertices in the graph.
     *
     * @return Returns a mapping between a trace and its vertex in the graph.
     */
    private Map<String, Vertex> initVertexMap() {

        long start = System.currentTimeMillis();

        Map<String, Vertex> vertexMap = new HashMap<>();

        // handle entry vertices
        Set<Vertex> entryVertices = baseCFG.getVertices().stream().filter(Vertex::isEntryVertex).collect(Collectors.toSet());

        for (Vertex entryVertex : entryVertices) {
            // exclude global entry vertex
            if (!entryVertex.equals(baseCFG.getEntry())) {

                // virtual entry vertex
                vertexMap.put(entryVertex.getMethod() + "->entry", entryVertex);

                // there are potentially several entry vertices when dealing with try-catch blocks at the beginning
                Set<Vertex> entries = baseCFG.getOutgoingEdges(entryVertex).stream()
                        .map(Edge::getTarget).collect(Collectors.toSet());

                for (Vertex entry : entries) {
                    // exclude dummy CFGs solely consisting of entry and exit vertex
                    if (!entry.isExitVertex()) {
                        Statement statement = entry.getStatement();

                        // TODO: handle basic statements
                        if (statement instanceof BlockStatement) {
                            // each statement within a block statement is a basic statement
                            BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getFirstStatement();
                            vertexMap.put(entry.getMethod() + "->entry->" + basicStatement.getInstructionIndex(), entry);
                        }
                    }
                }
            }
        }

        // handle exit vertices
        Set<Vertex> exitVertices = baseCFG.getVertices().stream().filter(Vertex::isExitVertex).collect(Collectors.toSet());

        for (Vertex exitVertex : exitVertices) {
            // exclude global exit vertex
            if (!exitVertex.equals(baseCFG.getExit())) {

                // virtual exit vertex
                vertexMap.put(exitVertex.getMethod() + "->exit", exitVertex);

                Set<Vertex> exits = baseCFG.getIncomingEdges(exitVertex).stream()
                        .map(Edge::getSource).collect(Collectors.toSet());

                for (Vertex exit : exits) {
                    // exclude dummy CFGs solely consisting of entry and exit vertex
                    if (!exit.isEntryVertex()) {
                        Statement statement = exit.getStatement();

                        // TODO: handle basic statements
                        if (statement instanceof BlockStatement) {
                            // each statement within a block statement is a basic statement
                            BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getLastStatement();
                            vertexMap.put(exit.getMethod() + "->exit->" + basicStatement.getInstructionIndex(), exit);
                        }
                    }
                }
            }
        }

        // handle branch + if stmt vertices
        for (Vertex branchVertex : branchVertices) {

            // a branch can potentially have multiple predecessors (shared branch)
            Set<Vertex> ifVertices = baseCFG.getIncomingEdges(branchVertex).stream()
                    .map(Edge::getSource).filter(Vertex::isIfVertex).collect(Collectors.toSet());

            for (Vertex ifVertex : ifVertices) {

                Statement statement = ifVertex.getStatement();

                // TODO: handle basic statements
                if (statement instanceof BlockStatement) {
                    // the last statement is always a basic statement of an if vertex
                    BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getLastStatement();
                    if (InstructionUtils.isBranchingInstruction(basicStatement.getInstruction())) {
                        vertexMap.put(ifVertex.getMethod() + "->if->" + basicStatement.getInstructionIndex(), ifVertex);
                    }
                    else {
                        Log.printWarning("Unexpected block statement: " + statement + " for method " + ifVertex.getMethod());
                    }
                }
            }

            Statement statement = branchVertex.getStatement();

            // TODO: handle basic statements
            if (statement instanceof BlockStatement) {
                // each statement within a block statement is a basic statement
                BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getFirstStatement();
                vertexMap.put(branchVertex.getMethod() + "->" + basicStatement.getInstructionIndex(), branchVertex);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("VertexMap construction took: " + (end - start) + " ms.");
        Log.println("Size of VertexMap: " + vertexMap.size());

        return vertexMap;
    }

    /**
     * Returns the branch vertices that could be instrumented.
     *
     * @return Returns the branch vertices.
     */
    @Override
    public List<Vertex> getBranchVertices() {
        return Collections.unmodifiableList(branchVertices);
    }

    /**
     * Looks up a trace corresponding to a vertex in the graph.
     *
     * @param trace The trace describing the vertex.
     * @return Returns the vertex corresponding to the trace
     * or {@code null} if no vertex matches the trace.
     */
    @Override
    public Vertex lookupVertex(String trace) {
        if (vertexMap.containsKey(trace)) {
            return vertexMap.get(trace);
        } else {
            try {
                Log.println("Non cached vertex lookup for trace: " + trace);
                return baseCFG.lookUpVertex(trace);
            } catch (Exception e) {
                Log.printWarning(e.getMessage());
                return null;
            }
        }
    }

    @Override
    public int getDistance(Vertex source, Vertex target) {

        assert baseCFG.containsVertex(source)
                && baseCFG.containsVertex(target) : "source and target vertex must be part of graph!";

        VertexPair distancePair = new VertexPair(source, target);

        if (cachedDistances.containsKey(distancePair)) {
            return cachedDistances.get(distancePair);
        }

        // TODO: adjust path search algorithm (dijkstra, bfs, ...)
        GraphPath<Vertex, Edge> path = dijkstra.getPath(source, target);

        // a negative path length indicates that there is no path between the given vertices
        int distance = path != null ? path.getLength() : -1;

        // update cache
        cachedDistances.put(distancePair, distance);

        return distance;
    }

    @Override
    public int size() {
        return baseCFG.size();
    }

    @Override
    public String getAppName() {
        return appName;
    }
}
