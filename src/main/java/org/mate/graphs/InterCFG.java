package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;

import java.io.File;
import java.nio.file.Path;

public class InterCFG extends CFG {

    public InterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName) {
        super(GraphUtils.constructInterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses),
                appsDir, packageName);
    }
}
