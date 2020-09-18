package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.utility.Utility;

import java.io.File;

public class IntraCFG implements Graph {

    private final BaseCFG intraCFG;
    private final String appName;


    public IntraCFG(File apkPath, String method, boolean useBasicBlocks, String packageName) {
        this.appName = packageName;
        intraCFG = Utility.constructIntraCFG(apkPath, method, useBasicBlocks);
    }

    @Override
    public int size() {
        return intraCFG.size();
    }

    @Override
    public String getAppName() {
        return appName;
    }
}
