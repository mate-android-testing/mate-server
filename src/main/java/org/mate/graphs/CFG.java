package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;

import java.util.*;

public class CFG {

    private final BaseCFG interCFG;
    private final int numberOfBranches;

    private Vertex targetVertex;

    private Set<Vertex> coveredTargetVertices = new HashSet<>();

    // TODO: may use int -> for coverage sufficient
    private Set<Vertex> coveredBranches = new HashSet<>();

    // track branch coverage per test case (key: test case id, value: covered branches)
    private Map<String, Set<Vertex>> testCaseBranchCoverage = new HashMap<>();

    public CFG(BaseCFG interCFG) {
        this.interCFG = interCFG;
        numberOfBranches = interCFG.getBranches().size();
        selectTargetVertex(true);
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

        Random rand = new Random(System.currentTimeMillis());
        int index = rand.nextInt(vertices.size());
        Iterator<Vertex> iter = vertices.iterator();
        for (int i = 0; i < index; i++) {
            iter.next();
        }

        return iter.next();
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
}
