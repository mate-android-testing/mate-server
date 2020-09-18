package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;

import java.util.LinkedList;
import java.util.List;

public abstract class CFG implements Graph {

    protected final BaseCFG baseCFG;
    private final String appName;

    public CFG(BaseCFG baseCFG, String appName) {
        this.baseCFG = baseCFG;
        this.appName = appName;
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

        List<Vertex> branchVertices = baseCFG.getBranches();
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
