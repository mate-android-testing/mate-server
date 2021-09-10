package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;

import java.io.File;
import java.nio.file.Path;

public class IntraCFG extends CFG {

    public IntraCFG(File apkPath, String method, boolean useBasicBlocks, Path appsDir, String packageName) {
        super(GraphUtils.constructIntraCFG(apkPath, method, useBasicBlocks), appsDir, packageName);
    }
}
