package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Edge;
import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;
import de.uni_passau.fim.auermich.statement.Statement;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;

import java.util.*;

public class CFG {

    private final BaseCFG interCFG;
    private final int numberOfBranches;
    private final String packageName;

    private Vertex targetVertex;

    private Map<String, Vertex> vertexMap = new HashMap<>();

    // pre-compute distances to target vertex
    private Map<Vertex, Double> branchDistances = new HashMap<>();

    private Set<Vertex> coveredTargetVertices = new HashSet<>();

    // TODO: may use int -> for coverage sufficient
    private Set<Vertex> coveredBranches = new HashSet<>();

    // track branch coverage per test case (key: test case id, value: covered branches)
    private Map<String, Set<Vertex>> testCaseBranchCoverage = new HashMap<>();

    public CFG(BaseCFG interCFG, String packageName) {
        this.interCFG = interCFG;
        numberOfBranches = interCFG.getBranches().size();
        this.packageName = packageName;
        selectTargetVertex(true);
        init();
    }

    private void init() {

        // pre-compute dijkstra
        ShortestPathAlgorithm<Vertex, Edge> dijkstra = interCFG.initDijkstraAlgorithm();

        Set<Vertex> vertices = interCFG.getVertices();

        for (Vertex vertex : vertices) {

            GraphPath<Vertex, Edge> path = dijkstra.getPath(vertex, targetVertex);

            int distance;

            if (path != null) {
                distance = path.getLength();
            } else {
                distance = -1;
            }

            // pre-compute branch distance
            branchDistances.put(vertex, Double.valueOf(distance));

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
                    BasicStatement basicStmt = (BasicStatement) blockStmt.getFirstStatement();
                    vertexMap.put(vertex.getMethod() + "->" + basicStmt.getInstructionIndex(), vertex);
                }
            }
        }
        System.out.println("Size of VertexMap: " + vertexMap.size());
    }

    public Map<Vertex, Double> getBranchDistances() {
        return branchDistances;
    }

    public Map<String, Vertex> getVertexMap() {
        return vertexMap;
    }

    /*
    * TODO: enhance target vertex selection
    * Initially, we should probably start with a random selected target vertex. Whenever
    * the target vertex has been covered by some test, i.e. an execution path, a new
    * target vertex has to be chosen. To deeper explore the AUT, we should select a
    * yet uncovered vertex. In addition, we should integrate some upper bound, e.g.
    * a maximal number of tries, which causes the re-selection of a target vertex
    * when being reached.
     */

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
        System.out.println("Selected Target Vertex: " + targetVertex);
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
        return interCFG.getShortestDistance(src, dest);
    }

    public double getBranchCoverage() {
        return ((double) coveredBranches.size()) / numberOfBranches;
    }

    public double getBranchCoverage(String testCase) {
        return ((double) testCaseBranchCoverage.get(testCase).size()) / numberOfBranches;
    }

    public String getPackageName() {
        return packageName;
    }
}
