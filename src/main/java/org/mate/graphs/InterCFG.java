package org.mate.graphs;

import de.uni_passau.fim.auermich.utility.Utility;

import java.io.File;

public class InterCFG extends CFG {

    public InterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,  String packageName) {
        super(Utility.constructInterCFG(apkPath, useBasicBlocks, excludeARTClasses), packageName);
    }
}
