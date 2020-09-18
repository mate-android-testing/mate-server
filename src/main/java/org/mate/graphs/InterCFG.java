package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.utility.Utility;

import java.io.File;

public class InterCFG implements Graph {

    private final BaseCFG interCFG;
    private final String appName;

    public InterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,  String packageName) {
        interCFG = Utility.constructInterCFG(apkPath, useBasicBlocks, excludeARTClasses);
        appName = packageName;
    }

    @Override
    public int size() {
        return interCFG.size();
    }

    @Override
    public String getAppName() {
        return appName;
    }
}
