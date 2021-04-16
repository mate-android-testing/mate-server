package org.mate.graphs;

import de.uni_passau.fim.auermich.utility.Utility;

import java.io.File;
import java.nio.file.Path;

public class InterCFG extends CFG {

    public InterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,
                    Path appsDir, String packageName) {
        super(Utility.constructInterCFG(apkPath, useBasicBlocks, excludeARTClasses), appsDir, packageName);
    }
}
