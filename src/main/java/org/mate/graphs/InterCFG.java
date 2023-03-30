package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.InstructionUtils;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.iface.Method;
import org.mate.util.Log;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents an inter-procedural CFG.
 */
public class InterCFG extends CFG {

    /**
     * Constructs an inter-procedural CFG.
     *
     * @param interCFG The inter-procedural CFG returned from the graph library.
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     */
    public InterCFG(BaseCFG interCFG, Path appsDir, String packageName) {
        super(interCFG, appsDir, packageName);
    }

    /**
     * Constructs an inter-procedural CFG with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param useBasicBlocks Whether basic blocks should be used or not.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     */
    public InterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName) {
        super(GraphUtils.constructInterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses),
                appsDir, packageName);
    }

    /**
     * {@inheritDoc}
     */
    protected List<CFGVertex> mapBranchesToVertices(List<String> branches) {

        long start = System.currentTimeMillis();

        List<CFGVertex> branchVertices = Collections.synchronizedList(new ArrayList<>());

        branches.parallelStream().forEach(branch -> {

            CFGVertex branchVertex = lookupVertex(branch);

            if (branchVertex == null) {
                Log.printWarning("Couldn't derive vertex for branch: " + branch);
            } else {
                branchVertices.add(branchVertex);
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping branches to vertices took: " + (end - start) + " ms.");

        Log.println("Number of actual branches: " + branches.size());
        Log.println("Number of branch vertices: " + branchVertices.size());

        if (branchVertices.size() != branches.size()) {
            throw new IllegalStateException("Couldn't derive for certain branches the corresponding branch vertices!");
        }

        return branchVertices;
    }

    /**
     * {@inheritDoc}
     */
    protected Map<String, CFGVertex> initTraceToVertexCache() {

        long start = System.currentTimeMillis();

        Map<String, CFGVertex> traceToVertexCache = new HashMap<>();

        // handle entry vertices
        Set<CFGVertex> entryVertices = baseCFG.getVertices().stream().filter(CFGVertex::isEntryVertex).collect(Collectors.toSet());

        for (CFGVertex entryVertex : entryVertices) {
            // exclude global entry vertex
            if (!entryVertex.equals(baseCFG.getEntry())) {

                // virtual entry vertex
                traceToVertexCache.put(entryVertex.getMethod() + "->entry", entryVertex);

                // there are potentially several entry vertices when dealing with try-catch blocks at the beginning
                Set<CFGVertex> entries = baseCFG.getOutgoingEdges(entryVertex).stream()
                        .map(CFGEdge::getTarget).collect(Collectors.toSet());

                for (CFGVertex entry : entries) {
                    // exclude dummy CFGs solely consisting of entry and exit vertex
                    if (!entry.isExitVertex()) {
                        Statement statement = entry.getStatement();

                        // TODO: handle basic statements
                        if (statement instanceof BlockStatement) {
                            // each statement within a block statement is a basic statement
                            BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getFirstStatement();
                            traceToVertexCache.put(entry.getMethod() + "->entry->" + basicStatement.getInstructionIndex(), entry);
                        }
                    }
                }
            }
        }

        // handle exit vertices
        Set<CFGVertex> exitVertices = baseCFG.getVertices().stream().filter(CFGVertex::isExitVertex).collect(Collectors.toSet());

        for (CFGVertex exitVertex : exitVertices) {
            // exclude global exit vertex
            if (!exitVertex.equals(baseCFG.getExit())) {

                // virtual exit vertex
                traceToVertexCache.put(exitVertex.getMethod() + "->exit", exitVertex);

                Set<CFGVertex> exits = baseCFG.getIncomingEdges(exitVertex).stream()
                        .map(CFGEdge::getSource).collect(Collectors.toSet());

                for (CFGVertex exit : exits) {
                    // exclude dummy CFGs solely consisting of entry and exit vertex
                    if (!exit.isEntryVertex()) {
                        Statement statement = exit.getStatement();

                        // TODO: handle basic statements
                        if (statement instanceof BlockStatement) {
                            // each statement within a block statement is a basic statement
                            BasicStatement basicStatement = (BasicStatement) ((BlockStatement) statement).getLastStatement();
                            traceToVertexCache.put(exit.getMethod() + "->exit->" + basicStatement.getInstructionIndex(), exit);
                        }
                    }
                }
            }
        }

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
     * Looks up a vertex by method and instruction.
     *
     * @param method The method containing the instruction.
     * @param builderInstruction The instruction to be looked up.
     * @return Returns the vertex wrapping the given instruction.
     */
    public CFGVertex findVertexByInstruction(final Method method, final BuilderInstruction builderInstruction) {

        final Set<CFGVertex> vertices = getVertices().stream()
                .filter(vertex -> vertex.containsInstruction(method.toString(),
                        builderInstruction.getLocation().getIndex()))
                .collect(Collectors.toSet());

        if (vertices.size() == 1) {
            return vertices.stream().findAny().orElseThrow();
        } else {
            throw new NoSuchElementException("Instruction not resolvable in graph!");
        }
    }
}
