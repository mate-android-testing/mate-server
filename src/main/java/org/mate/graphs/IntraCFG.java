package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import org.mate.graphs.util.Util;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents an intra-procedural CFG. Note that the construction assumes the AUT have been instrumented with the
 * basic block coverage instrumentation module and the blocks.txt file is present.
 */
public class IntraCFG extends CFG {

    /**
     * The method that is represented by the intraCFG.
     */
    private final String method;

    public IntraCFG(File apkPath, String method, boolean useBasicBlocks, Path appsDir, String packageName) {
        super(GraphUtils.constructIntraCFG(apkPath, method, useBasicBlocks), appsDir, packageName);
        this.method = method;
    }

    /**
     * Returns the method that is represented by the intraCFG.
     *
     * @return Returns the method that is represented by the intraCFG.
     */
    public String getMethod() {
        return method;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<String, CFGVertex> initTraceToVertexCache() {

        long start = System.currentTimeMillis();

        final Map<String, CFGVertex> traceToVertexCache = new HashMap<>();

        for (CFGVertex vertex : getVertices()) {

            if (!vertex.isEntryVertex() && !vertex.isExitVertex()) { // only for basic blocks
                initStatementVertexToVertexCache(vertex, traceToVertexCache);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("TraceToVertexCache construction took: " + (end - start) + " ms.");
        Log.println("Size of TraceToVertexCache: " + traceToVertexCache.size());

        return traceToVertexCache;
    }

    /**
     * Initialises the trace to vertex mapping for branch vertices.
     *
     * @param vertex The statement vertex that will be added to the trace to vertex cache mapping.
     * @param traceToVertexCache The trace to vertex mapping.
     */
    private void initStatementVertexToVertexCache(final CFGVertex vertex, final Map<String, CFGVertex> traceToVertexCache) {
        BlockStatement statement = (BlockStatement) vertex.getStatement();
        int index = Util.getInstructionIndexFromBlockStatement(statement);
        int operationCount = statement.getStatements().size();
        String branchSuffix = vertex.isBranchVertex() ? "isBranch" : "noBranch";
        traceToVertexCache.put(vertex.getMethod() + "->" + index + "->" + operationCount + "->" + branchSuffix, vertex);
    }

    /**
     * Retrieves the list of branch vertices, those that could be actually instrumented.
     *
     * @return Returns the branch vertices.
     */
    @Override
    protected List<CFGVertex> initBranchVertices() {

        // TODO: The branch vertices of the intraCFG are likely irrelevant and should be better dropped at all!

        final Path appDir = appsDir.resolve(appName);
        final File blocksFile = appDir.resolve(BLOCKS_FILE).toFile();
        final List<String> branches = new ArrayList<>();
        final String method = graph.getMethodName();

        // Extract branches from blocks.txt file
        try (Stream<String> stream = Files.lines(blocksFile.toPath(), StandardCharsets.UTF_8)) {
            // hopefully this preserves the order (remove blank line at end)
            branches.addAll(stream.filter(line -> line.startsWith(method)
                    && line.endsWith("->isBranch")).collect(Collectors.toList()));
        } catch (IOException e) {
            throw new IllegalStateException("Error occurred during processing of " + BLOCKS_FILE + " file!", e);
        }

        return mapBranchesToVertices(branches);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<CFGVertex> mapBranchesToVertices(final List<String> branches) {

        long start = System.currentTimeMillis();

        List<CFGVertex> branchVertices = Collections.synchronizedList(new ArrayList<>());

        branches.parallelStream().forEach(branch -> {

            final CFGVertex branchVertex = lookupVertex(branch);

            if (branchVertex == null) {
                Log.printWarning("Couldn't derive vertex for branch: " + branch);
            } else {
                branchVertices.add(branchVertex);
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping branches to vertices took: " + (end - start) + " ms.");

        Log.println("Number of branch vertices: " + branchVertices.size());

        return branchVertices;
    }
}
