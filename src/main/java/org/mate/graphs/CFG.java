package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;

import java.util.*;

public class CFG {

    private final BaseCFG interCFG;
    private final int numberOfBranches;

    // TODO: may use int -> for coverage sufficient
    private Set<Vertex> coveredBranches = new HashSet<>();

    // track branch coverage per test case (key: test case id, value: covered branches)
    private Map<String, Set<Vertex>> testCaseBranchCoverage = new HashMap<>();

    public CFG(BaseCFG interCFG) {
        this.interCFG = interCFG;
        numberOfBranches = interCFG.getBranches().size();
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
