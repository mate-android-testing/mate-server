package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;

import java.util.*;

public class CFG {

    private final BaseCFG interCFG;
    private final int numberOfBranches;

    private Vertex targetVertex;

    // TODO: may use int -> for coverage sufficient
    private Set<Vertex> coveredBranches = new HashSet<>();

    // track branch coverage per test case (key: test case id, value: covered branches)
    private Map<String, Set<Vertex>> testCaseBranchCoverage = new HashMap<>();

    public CFG(BaseCFG interCFG) {
        this.interCFG = interCFG;
        numberOfBranches = interCFG.getBranches().size();
        targetVertex = selectTargetVertex();
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
    private Vertex selectTargetVertex() {

        // TODO: provide access to intra-CFGs
        //  entry vertices are not modified, thus always identical to the vertices of the intra-CFGs

        int instructionIndex = 17;
        String targetMethod = "Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;";
        // Vertex entryVertex = interCFG.getIntraCFG(targetMethod);

        Vertex entryVertex = getVertices().stream().filter(v -> v.isEntryVertex()
                && v.getMethod().equals(targetMethod))
                .findFirst().get();

        Vertex targetVertex;

        /*
        while (true) {

            if (entryVertex.containsInstruction(targetMethod,17)) {
                targetVertex = entryVertex;
                break;
            } else {
                // TODO: this should be actually a recursive call, since multiple outgoing edges are present
                entryVertex = interCFG.getOutgoingEdges(entryVertex).iterator().next().getTarget();
            }

        }
        */

        // evaluate against a pre-defined target vertex
        targetVertex = getVertices().stream().filter(v -> v.isEntryVertex()
                && v.getMethod().equals("Lcom/zola/bmi/BMIMain;->onStart()V")).findFirst().get();

        return targetVertex;
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
