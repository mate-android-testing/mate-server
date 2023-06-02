package org.mate.graphs;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;

public class InterCDG extends CFG {


    /**
     * Constructs a wrapper for a given control dependence graph.
     *
     * @param graph   The actual control dependence graph.
     * @param appsDir The path to the apps directory.
     * @param appName The name of the app (the package name).
     */
    public InterCDG(BaseCFG graph, Path appsDir, String appName) {
        super(graph, appsDir, appName);
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
    public InterCDG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName) {
        super(GraphUtils.constructInterCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses), appsDir, packageName);
    }



    @Override
    Map<String, CFGVertex> initTraceToVertexCache() {
        // TODO
        return null;
    }

    @Override
    List<CFGVertex> mapBranchesToVertices(List<String> branches) {
        // TODO
        return null;
    }
}
