package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ManyToManyShortestPathsAlgorithm;
import org.mate.graphs.util.VertexPair;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class CFG implements Graph<CFGVertex, CFGEdge> {

    /**
     * The underlying CFG.
     */
    protected final BaseCFG baseCFG;

    /**
     * The package name of the AUT, e.g. com.zola.bmi.
     */
    protected final String appName;

    // cache the list of branches (the order must be consistent when requesting the branch distance vector)
    protected List<CFGVertex> branchVertices;

    /**
     * The employed shortest path algorithm. For individual vertices the bi-directional dijkstra seems to be the fastest
     * option, while for resolving the shortest paths between many vertices, the CH many-to-many shortest path algorithm
     * appears to be the best option.
     */
    private final ManyToManyShortestPathsAlgorithm<CFGVertex, CFGEdge> shortestPathAlgorithm;

    /**
     * The path to the 'apps' folder.
     */
    protected final Path appsDir;

    /**
     * Contains the instrumented branches of the AUT. This also includes case statements belonging to switch instructions.
     */
    private static final String BRANCHES_FILE = "branches.txt";

    /**
     * Caches a mapping from trace to vertex for the most relevant vertices, e.g. branch, case, if and switch vertices.
     */
    private Map<String, CFGVertex> traceToVertexCache;

    /**
     * Caches already computed distances between two arbitrary vertices. Note that the order of the vertex pair doesn't
     * matter.
     */
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
        this.traceToVertexCache = new HashMap<>(); // pre-init for initBranchVertices()!
        branchVertices = initBranchVertices();
        shortestPathAlgorithm = baseCFG.initCHManyToManyShortestPathAlgorithm();
        traceToVertexCache = initTraceToVertexCache();
    }

    /**
     * Retrieves the list of branch vertices, those that could be actually instrumented.
     *
     * @return Returns the branch vertices.
     */
    protected List<CFGVertex> initBranchVertices() {

        // TODO: Read from blocks.txt if branches.txt is not present.

        final Path appDir = appsDir.resolve(appName);
        final File branchesFile = appDir.resolve(BRANCHES_FILE).toFile();

        final List<String> branches = new ArrayList<>();

        try (Stream<String> stream = Files.lines(branchesFile.toPath(), StandardCharsets.UTF_8)) {
            // hopefully this preserves the order (remove blank line at end)
            branches.addAll(stream.filter(line -> line.length() > 0).collect(Collectors.toList()));
        } catch (IOException e) {
            Log.printError("Reading " + BRANCHES_FILE + " failed!");
            throw new IllegalStateException(e);
        }

        return mapBranchesToVertices(branches);
    }

    /**
     * Pre-computes a mapping between certain traces and its vertices in the graph.
     *
     * @return Returns a mapping between a trace and its vertex in the graph.
     */
    abstract Map<String, CFGVertex> initTraceToVertexCache();

    /**
     * Maps the given list of branches to the corresponding vertices in the graph.
     *
     * @param branches The list of branches that should be mapped to vertices.
     * @return Returns the branch vertices.
     */
    abstract List<CFGVertex> mapBranchesToVertices(List<String> branches);

    /**
     * Checks whether the given vertex is reachable from the global entry point.
     *
     * @param vertex The vertex to be checked for reachability.
     * @return Returns whether the given vertex is reachable or not.
     */
    @Override
    public boolean isReachable(CFGVertex vertex) {
        return shortestPathAlgorithm.getPath(baseCFG.getEntry(), vertex) != null;
    }

    /**
     * Returns the vertices contained in the graph.
     *
     * @return Returns all vertices in the graph.
     */
    @Override
    public List<CFGVertex> getVertices() {
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
     *
     * The visited vertices get marked in green.
     * The uncovered target vertices get marked in red.
     * The covered target vertices get marked in orange.
     *
     * @param outputPath      The output directory.
     * @param visitedVertices The list of visited vertices.
     * @param targets         The list of target vertices.
     */
    @Override
    public void draw(File outputPath, Set<CFGVertex> visitedVertices, Set<CFGVertex> targets) {
        baseCFG.drawGraph(outputPath, visitedVertices, targets);
    }

    /**
     * Gets the outgoing edges from the given vertex.
     *
     * @param vertex The vertex for which the outgoing edges should be derived.
     * @return Returns the outgoing edges from the given vertex.
     */
    @Override
    public Set<CFGEdge> getOutgoingEdges(CFGVertex vertex) {
        return baseCFG.getOutgoingEdges(vertex);
    }

    /**
     * Gets the incoming edges from the given vertex.
     *
     * @param vertex The vertex for which the incoming edges should be derived.
     * @return Returns the incoming edges from the given vertex.
     */
    @Override
    public Set<CFGEdge> getIncomingEdges(CFGVertex vertex) {
        return baseCFG.getIncomingEdges(vertex);
    }

    /**
     * Returns the branch vertices that could be instrumented. This includes case statements.
     *
     * @return Returns the branch vertices.
     */
    @Override
    public List<CFGVertex> getBranchVertices() {
        return Collections.unmodifiableList(branchVertices);
    }

    /**
     * Looks up a trace corresponding to a vertex in the graph.
     *
     * @param trace The trace describing the vertex.
     * @return Returns the vertex corresponding to the trace or {@code null} if no vertex matches the trace.
     */
    @Override
    public CFGVertex lookupVertex(String trace) {
        if (traceToVertexCache.containsKey(trace)) {
            return traceToVertexCache.get(trace);
        } else {
            try {
                return baseCFG.lookUpVertex(trace);
            } catch (Exception e) {
                Log.printWarning(e.getMessage());
                return null;
            }
        }
    }

    /**
     * Returns the shortest path distance between the given source and target vertex.
     *
     * @param source The given source vertex.
     * @param target The given target vertex.
     * @return Returns the shortest path distance between the given source and target vertex. If no such path exists,
     *          a negative distance of {@code -1} is returned.
     */
    @Override
    public int getDistance(CFGVertex source, CFGVertex target) {

        VertexPair distancePair = new VertexPair(source, target);

        if (cachedDistances.containsKey(distancePair)) {
            return cachedDistances.get(distancePair);
        }

        GraphPath<CFGVertex, CFGEdge> path = shortestPathAlgorithm.getPath(source, target);

        // a negative path length indicates that there is no path between the given vertices
        int distance = path != null ? path.getLength() : -1;

        // update cache
        cachedDistances.put(distancePair, distance);

        return distance;
    }

    /**
     * Returns the shortest path distances between the given source and target vertices.
     *
     * @param sources The set of source vertices.
     * @param targets The set of target vertices.
     * @return Returns the shortest path distances between the given source and target vertices. If no path between a
     *          source and target vertex exists, a negative distance of {@code -1} is returned.
     */
    @Override
    public BiFunction<CFGVertex, CFGVertex, Integer> getDistances(final Set<CFGVertex> sources, final Set<CFGVertex> targets) {
        final var distances
                = shortestPathAlgorithm.getManyToManyPaths(sources, targets);
        return (s, t) -> {
            final var path = distances.getPath(s, t);
            return path != null ? path.getLength() : -1;
        };
    }

    /**
     * Returns the size of the CFG in terms of the number of vertices.
     *
     * @return Returns the number of vertices of the CFG.
     */
    @Override
    public int size() {
        return baseCFG.size();
    }

    /**
     * Returns the package name of the AUT.
     *
     * @return Returns the package name of the AUT.
     */
    @Override
    public String getAppName() {
        return appName;
    }
}
