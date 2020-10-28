package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Edge;
import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;
import de.uni_passau.fim.auermich.statement.Statement;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.BidirectionalDijkstraShortestPath;
import org.mate.util.Log;

import java.util.*;
import java.util.stream.Collectors;

public abstract class CFG implements Graph {

    protected final BaseCFG baseCFG;
    private final String appName;

    // the target is either a single branch or all branches (MIO/MOSA)
    private List<Vertex> target = new ArrayList<>();

    // cache the list of branches (the order must be consistent when requesting the branch distance vector)
    protected final List<Vertex> branchVertices;

    // the search algorithm (bi-directional dijkstra seems to be the fastest one)
    private final ShortestPathAlgorithm<Vertex, Edge> dijkstra;

    /**
     * Contains a mapping between a trace and its vertex within the graph. The mapping
     * is only defined for traces describing branches, if statements and entry/exit statements.
     * A look up of single vertices is quite expensive and this map should speed up the mapping process.
     */
    private final Map<String, Vertex> vertexMap;

    /**
     * Constructs a wrapper for a given control-flow graph.
     *
     * @param baseCFG The actual control flow graph.
     * @param appName The name of the app (the package name).
     */
    public CFG(BaseCFG baseCFG, String appName) {
        this.baseCFG = baseCFG;
        this.appName = appName;
        branchVertices = baseCFG.getBranches();
        dijkstra = baseCFG.initBidirectionalDijkstraAlgorithm();
        // TODO: init target(s) depending on additional param (boolean singleObjective?)
        selectTargetVertex();
        vertexMap = initVertexMap();
    }

    /**
     * Selects a branch vertex as target in a random fashion.
     */
    private void selectTargetVertex() {
        // TODO: check that selected vertex/vertices is/are reachable, otherwise re-select!
        Random rand = new Random();
        Vertex randomBranch = branchVertices.get(rand.nextInt(branchVertices.size()));
        Log.println("Randomly selected target vertex: " + randomBranch);
        target.add(randomBranch);
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

                // there are potentially several entry vertices when dealing with try-catch blocks at the beginning
                Set<Vertex> entries = baseCFG.getOutgoingEdges(entryVertex).stream()
                        .map(Edge::getTarget).collect(Collectors.toSet());

                for (Vertex entry : entries) {
                    // exclude dummy CFGs solely consisting of entry and exit vertex
                    if (!entry.isReturnVertex()) {
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

        // TODO: handle exit vertices


        // TODO: handle branch + if stmt vertices

        long end = System.currentTimeMillis();
        Log.println("VertexMap construction took: " + (end-start) + " seconds");
        Log.println("Size of VertexMap: " + vertexMap.size());

        return vertexMap;
    }

    @Override
    public Vertex lookupVertex(String trace) {
        return vertexMap.get(trace);
    }

    @Override
    public int getDistance(Vertex source) {
        // TODO: adjust path search algorithm (dijkstra, bfs, ...)
        GraphPath<Vertex, Edge> path = dijkstra.getPath(source, target.get(0));
        // a negative path length indicates that there is no path between the given vertices
        return path != null ? path.getLength() : -1;
    }

    @Override
    public int getDistance(Vertex source, Vertex target) {
        // TODO: adjust path search algorithm (dijkstra, bfs, ...)
        GraphPath<Vertex, Edge> path = dijkstra.getPath(source, target);
        // a negative path length indicates that there is no path between the given vertices
        return path != null ? path.getLength() : -1;
    }

    @Override
    public int size() {
        return baseCFG.size();
    }

    @Override
    public String getAppName() {
        return appName;
    }

    @Override
    public List<String> getBranches() {

        List<String> branches = new LinkedList<>();

        for (Vertex branchVertex : branchVertices) {
            Integer branchID = null;
            if (branchVertex.getStatement() instanceof BasicStatement) {
                branchID = ((BasicStatement) branchVertex.getStatement()).getInstructionIndex();
            } else if (branchVertex.getStatement() instanceof BlockStatement) {
                branchID = ((BasicStatement) ((BlockStatement) branchVertex.getStatement()).getFirstStatement()).getInstructionIndex();
            }

            if (branchID != null) {
                // convert a branch (vertex) to its trace
                branches.add(branchVertex.getMethod() + "->" + branchID);
            }
        }
        return branches;
    }
}
