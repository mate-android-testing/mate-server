package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.InstructionUtils;
import org.mate.util.Log;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class IntraCFG extends CFG {

    public IntraCFG(File apkPath, String method, boolean useBasicBlocks, Path appsDir, String packageName) {
        super(GraphUtils.constructIntraCFG(apkPath, method, useBasicBlocks), appsDir, packageName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<String, CFGVertex> initTraceToVertexCache() {

        long start = System.currentTimeMillis();

        final Map<String, CFGVertex> traceToVertexCache = new HashMap<>();

        // virtual entry and exit vertex
        traceToVertexCache.put(baseCFG.getEntry().getMethod() + "->entry", baseCFG.getEntry());
        traceToVertexCache.put(baseCFG.getExit().getMethod() + "->exit", baseCFG.getExit());

        // handle branch + if and switch stmt vertices
        for (CFGVertex branchVertex : branchVertices) {

            // a branch can potentially have multiple predecessors (shared branch)
            Set<CFGVertex> ifOrSwitchVertices = baseCFG.getIncomingEdges(branchVertex).stream()
                    .map(CFGEdge::getSource).filter(CFGVertex::isIfVertex).collect(Collectors.toSet());

            // if or switch vertex
            for (CFGVertex ifOrSwitchVertex : ifOrSwitchVertices) {

                Statement statement = ifOrSwitchVertex.getStatement();

                // TODO: handle basic statements
                if (statement instanceof BlockStatement) {
                    // the last statement is always a basic statement of an if vertex
                    BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getLastStatement();
                    if (InstructionUtils.isBranchingInstruction(basicStatement.getInstruction())) {
                        traceToVertexCache.put(ifOrSwitchVertex.getMethod()
                                + "->if->" + basicStatement.getInstructionIndex(), ifOrSwitchVertex);
                    } else if (InstructionUtils.isSwitchInstruction(basicStatement.getInstruction())) {
                        traceToVertexCache.put(ifOrSwitchVertex.getMethod()
                                + "->switch->" + basicStatement.getInstructionIndex(), ifOrSwitchVertex);
                    }
                    else {
                        Log.printWarning("Unexpected block statement: " + statement + " for method " + ifOrSwitchVertex.getMethod());
                    }
                }
            }

            Statement statement = branchVertex.getStatement();

            // TODO: handle basic statements
            if (statement instanceof BlockStatement) {
                // each statement within a block statement is a basic statement
                BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getFirstStatement();
                traceToVertexCache.put(branchVertex.getMethod() + "->" + basicStatement.getInstructionIndex(), branchVertex);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("TraceToVertexCache construction took: " + (end - start) + " ms.");
        Log.println("Size of TraceToVertexCache: " + traceToVertexCache.size());

        return traceToVertexCache;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<CFGVertex> mapBranchesToVertices(final List<String> branches) {

        long start = System.currentTimeMillis();

        List<CFGVertex> branchVertices = Collections.synchronizedList(new ArrayList<>());

        branches.parallelStream().forEach(branch -> {

            String[] tokens = branch.split("->");

            // retrieve fully qualified method name (class name + method name)
            final String method = tokens[0] + "->" + tokens[1];

            if (method.equals(baseCFG.getMethodName())) {

                final CFGVertex branchVertex = lookupVertex(branch);

                if (branchVertex == null) {
                    Log.printWarning("Couldn't derive vertex for branch: " + branch);
                } else {
                    branchVertices.add(branchVertex);
                }
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping branches to vertices took: " + (end - start) + " ms.");

        Log.println("Number of branch vertices: " + branchVertices.size());

        return branchVertices;
    }
}
