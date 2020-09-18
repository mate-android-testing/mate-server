package org.mate.graphs;

import de.uni_passau.fim.auermich.utility.Utility;

import java.io.File;

public class IntraCFG extends CFG {

    public IntraCFG(File apkPath, String method, boolean useBasicBlocks, String packageName) {
        super(Utility.constructIntraCFG(apkPath, method, useBasicBlocks), packageName);
    }
}
