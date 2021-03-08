package org.mate.graphs;

import de.uni_passau.fim.auermich.utility.Utility;

import java.io.File;
import java.nio.file.Path;

public class IntraCFG extends CFG {

    public IntraCFG(File apkPath, String method, boolean useBasicBlocks, Path appsDir, String packageName) {
        super(Utility.constructIntraCFG(apkPath, method, useBasicBlocks), appsDir, packageName);
    }
}
