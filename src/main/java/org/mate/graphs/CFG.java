package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public abstract class CFG implements Graph {

    protected final BaseCFG baseCFG;
    private final String appName;

    // the target is either a single branch or all branches (MIO/MOSA)
    private List<Vertex> target = new ArrayList<>();

    // cache the list of branches (the order must be consistent when requesting the branch distance vector)
    protected final List<Vertex> branchVertices;

    public CFG(BaseCFG baseCFG, String appName) {
        this.baseCFG = baseCFG;
        this.appName = appName;
        branchVertices = baseCFG.getBranches();
        // TODO: init target depending on additional param (boolean singleObjective?)
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
