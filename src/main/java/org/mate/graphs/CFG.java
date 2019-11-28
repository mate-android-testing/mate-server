package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Edge;
import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;
import de.uni_passau.fim.auermich.statement.ExitStatement;
import de.uni_passau.fim.auermich.statement.Statement;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class CFG {

    private final BaseCFG interCFG;
    private final int numberOfBranches;
    private final String packageName;

    // tracks how often branch distance is queried
    public static int branchDistanceRetrievalCounter = 0;

    private Vertex targetVertex;

    private Map<String, Vertex> vertexMap = new ConcurrentHashMap<>();

    // cache computed branch distances to target vertex (single objective case)
    private Map<Vertex, Double> branchDistances = new ConcurrentHashMap<>();

    private Set<Vertex> coveredTargetVertices = new HashSet<>();

    // TODO: may use int -> for coverage sufficient
    private Set<Vertex> coveredBranches = new HashSet<>();

    private ShortestPathAlgorithm<Vertex, Edge> dijkstra;
    private ShortestPathAlgorithm<Vertex, Edge> bfs;

    // track branch coverage per test case (key: test case id, value: covered branches)
    private Map<String, Set<Vertex>> testCaseBranchCoverage = new HashMap<>();

    public CFG(BaseCFG interCFG, String packageName) {
        this.interCFG = interCFG;
        numberOfBranches = interCFG.getBranches().size();
        this.packageName = packageName;
        // init dijkstra
        dijkstra = interCFG.initBidirectionalDijkstraAlgorithm();
        // bfs = interCFG.initBFSAlgorithm();

        /*
        targetVertex = interCFG.getVertices().stream().filter(v
                -> v.containsInstruction("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;", 8)).findFirst().get();
        */

        selectTargetVertex(true);
        init();
    }

    public void markIntermediatePathVertices(Vertex entry, Vertex exit, Set<Vertex> visitedVertices) {

        /*
        *
        * current_vertex = entry;
        *
        * while not reached exit vertex (current_vertex != exit)
        *
        *   if entered new method (reached entry vertex with different method name)
        *       // check outgoing edges of corresponding exit vertex to return to original method
        *       current_vertex = return_vertex;
        *       visitedVertices.add(current_vertex);
        *       // should be inherently done as each entry vertex is part of the traces
        *       call recursively markIntermediatePathVertices(..) for new method
        *       continue;
        *
        *   if outgoingEdges(current_vertex) == 1
        *       current_vertex = successor(current_vertex);
        *       visitedVertices.add(current_vertex);
        *   else
        *       // assuming there are no try catch blocks (for termination we need a boolean which
        *       // indicates whether we found a branch vertex, otherwise we need to abort)
        *       for succ in successors(current_vertex):
        *           if visitedVertices.contain(succ) // this branch was taken
        *               call recursively markIntermediatePathVertices(..) with succ as entry vertex
         */

        Vertex currentVertex = entry;

        while (!currentVertex.equals(exit)) {

            Set<Edge> outgoingEdges = interCFG.getOutgoingEdges(currentVertex);

            if (outgoingEdges.size() == 1) {
                // single successors -> follow the path
                currentVertex = outgoingEdges.stream().findFirst().get().getTarget();
            } else {

                outgoingEdges.parallelStream().forEach(e -> {
                    Vertex targetVertex = e.getTarget();
                    if (targetVertex.isBranchVertex()
                            && visitedVertices.contains(targetVertex)) {
                        markIntermediatePathVertices(targetVertex, exit, visitedVertices);
                    }
                });
                break;
            }

            if (!entry.getMethod().equals(currentVertex.getMethod())) {
                // we entered a new method

                // search for return vertex, which must be a successor of the method's exit vertex
                Vertex exitVertex = new Vertex(new ExitStatement(currentVertex.getMethod()));
                currentVertex = getReturnVertex(currentVertex, exitVertex, visitedVertices);
            }

            visitedVertices.add(currentVertex);
        }
    }

    /**
     * Searches for the return vertex belonging to the invocation stmt included in the
     * given vertex.
     *
     * @param vertex The vertex including the invocation stmt.
     * @param exit The exit vertex of the invoked method.
     * @param visitedVertices The set of currently visited vertices. Needs to be updated
     *                        as a side effect in case no basic blocks are used.
     * @return Returns the corresponding return vertex. In case no basic blocks are used,
     *          the appropriate successor vertex is returned.
     * @throws IllegalStateException If the return vertex couldn't be found.
     */
    private Vertex getReturnVertex(Vertex vertex, Vertex exit, Set<Vertex> visitedVertices) {

        // the vertex contains an invoke instruction as last statement
        Statement stmt = vertex.getStatement();

        // we need to know instruction index of the invoke stmt
        int index = -1;

        if (stmt.getType() == Statement.StatementType.BASIC_STATEMENT) {
            BasicStatement basicStatement = (BasicStatement) stmt;
            index = basicStatement.getInstructionIndex();
        } else if (stmt.getType() == Statement.StatementType.BLOCK_STATEMENT) {
            BlockStatement blockStatement = (BlockStatement) stmt;
            BasicStatement basicStatement = (BasicStatement) blockStatement.getLastStatement();
            index = basicStatement.getInstructionIndex();
        }

        // find the return vertices that return to the original method
        List<Vertex> returnVertices = interCFG.getOutgoingEdges(exit).stream().filter(edge ->
                edge.getTarget().getMethod().equals(vertex.getMethod())).map(Edge::getTarget)
                .collect(Collectors.toList());

        // the return vertex that has index + 1 as next instruction index is the right one
        for (Vertex returnVertex : returnVertices) {

            // the vertex contains an invoke instruction as last statement
            Statement returnStmt = returnVertex.getStatement();

            // we need to know instruction index of the invoke stmt
            int nextIndex = -1;

            if (returnStmt.getType() == Statement.StatementType.BASIC_STATEMENT) {

                // we need to inspect the successor vertices since the return stmt is shared
                List<Vertex> successors = interCFG.getOutgoingEdges(returnVertex).stream().
                        map(Edge::getTarget).collect(Collectors.toList());

                for (Vertex successor : successors) {
                    // each successor is a basic stmt
                    BasicStatement basicStatement = (BasicStatement) successor.getStatement();
                    nextIndex = basicStatement.getInstructionIndex();

                    if (nextIndex == index + 1) {

                        // we can't return the return stmt, but we need to mark it as visited as a side effect
                        visitedVertices.add(returnVertex);

                        // here we return the successor of the return vertex directly
                        // otherwise we don't know again which is the actual successor
                        return successor;
                    }
                }
            } else if (returnStmt.getType() == Statement.StatementType.BLOCK_STATEMENT) {

                BlockStatement blockStatement = (BlockStatement) returnStmt;

                // the stmt after the return stmt is the next actual instruction stmt
                BasicStatement basicStatement = (BasicStatement) blockStatement.getStatements().get(1);
                index = basicStatement.getInstructionIndex();
            }

            if (nextIndex == index + 1) {
                return returnVertex;
            }
        }
        throw new IllegalStateException("Couldn't find corresponding return vertex for " + vertex);
    }

    /**
     * Initialises a vertex map, which basically maps each entry,
     * exit and branch vertex to a unique id. This speeds up the
     * mapping process of a collected trace entry.
     * Since traces only contain entry, exit and branch vertices, we can ommit
     * other vertex types.
     */
    private void init() {

        long start = System.currentTimeMillis();

        interCFG.getVertices().parallelStream().forEach(vertex -> {
            if (vertex.isEntryVertex()) {
                vertexMap.put(vertex.getMethod() + "->entry", vertex);
            } else if (vertex.isExitVertex()) {
                vertexMap.put(vertex.getMethod() + "->exit", vertex);
            } else if (vertex.isBranchVertex()) {
                // get instruction id of first stmt
                Statement statement = vertex.getStatement();

                if (statement.getType() == Statement.StatementType.BASIC_STATEMENT) {
                    BasicStatement basicStmt = (BasicStatement) statement;
                    vertexMap.put(vertex.getMethod() + "->" + basicStmt.getInstructionIndex(), vertex);
                } else {
                    // should be a block stmt, other stmt types shouldn't be branch targets
                    BlockStatement blockStmt = (BlockStatement) statement;
                    // a branch target can only be the first instruction in a basic block since it has to be a leader
                    BasicStatement basicStmt = (BasicStatement) blockStmt.getFirstStatement();
                    // identify a basic block by its first instruction (the branch target)
                    vertexMap.put(vertex.getMethod() + "->" + basicStmt.getInstructionIndex(), vertex);
                }
            }
        });

        long end = System.currentTimeMillis();
        System.out.println("Concurrent VertexMap construction took: " + (end-start));
        System.out.println("Size of VertexMap: " + vertexMap.size());
    }

    public Map<Vertex, Double> getBranchDistances() {
        return branchDistances;
    }

    public Map<String, Vertex> getVertexMap() {
        return vertexMap;
    }

    /**
     * Selects a new target vertex in either a purely random fashion or
     * a yet uncovered target vertex.
     *
     * @param random Whether to perform the selection purely random.
     */
    public void selectTargetVertex(boolean random) {

        if (random) {
            targetVertex = selectRandomTargetVertex();
        } else {

            // select a yet uncovered target vertex
            while (true) {
                Vertex vertex = selectRandomTargetVertex();
                if (!coveredTargetVertices.contains(vertex)) {
                    targetVertex = vertex;
                    break;
                }
            }
        }
        System.out.println("Selected Target Vertex: " + targetVertex + " " + targetVertex.getMethod());
    }

    /**
     * Marks the currently selected target vertex as covered.
     */
    public void updateCoveredTargetVertices() {
        coveredTargetVertices.add(targetVertex);
    }

    /**
     * Selects randomly a target vertex from the set of vertices.
     *
     * @return Returns a randomly selected target vertex.
     */
    private Vertex selectRandomTargetVertex() {

        Set<Vertex> vertices = getVertices();
        Vertex entryVertex = interCFG.getEntry();

        while (true) {

            Random rand = new Random(System.currentTimeMillis());
            int index = rand.nextInt(vertices.size());
            Iterator<Vertex> iter = vertices.iterator();
            for (int i = 0; i < index; i++) {
                iter.next();
            }

            Vertex targetVertex = iter.next();

            // check if target vertex is reachable from global entry point
            if (getShortestDistance(entryVertex, targetVertex) != -1) {
                return targetVertex;
            }
        }
    }

    public ShortestPathAlgorithm<Vertex, Edge> getDijkstra() {
        return dijkstra;
    }

    public ShortestPathAlgorithm<Vertex, Edge> getBFS() { return bfs; }

    public Vertex getTargetVertex() {
        return targetVertex;
    }

    public List<Vertex> getBranches() {
        return interCFG.getBranches();
    }

    public Set<Vertex> getVertices() {
        return interCFG.getVertices();
    }

    public int getNumberOfBranches() {
        return numberOfBranches;
    }

    public void addCoveredBranches(Set<Vertex> branches) {
        coveredBranches.addAll(branches);
    }

    public void addCoveredBranches(String testCase, Set<Vertex> branches) {
        testCaseBranchCoverage.put(testCase, branches);
    }

    public int getShortestDistance(Vertex src, Vertex dest) {

        // assert bfs != null;
        assert dijkstra != null;

        GraphPath<Vertex, Edge> path = dijkstra.getPath(src, dest);
        return path != null ? path.getLength() : -1;
    }

    public double getBranchCoverage() {
        return ((double) coveredBranches.size()) / numberOfBranches;
    }

    public double getBranchCoverage(String testCase) {
        if (!testCaseBranchCoverage.containsKey(testCase)) {
            return 0;
        } else {
            return ((double) testCaseBranchCoverage.get(testCase).size()) / numberOfBranches;
        }
    }

    public String getPackageName() {
        return packageName;
    }

    public void drawGraph(Set<Vertex> visitedVertices, File outputPath) {
        interCFG.drawGraph(visitedVertices, targetVertex, outputPath);
    }
}
