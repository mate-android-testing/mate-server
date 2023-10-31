package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;

import java.io.File;
import java.nio.file.Path;

public class ModularCDG extends CDG {

    /**
     * Constructs a modular CDG with the given properties.
     *
     * @param apkPath               The path to the APK file.
     * @param useBasicBlocks        Whether basic blocks should be used or not.
     * @param excludeARTClasses     Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param appsDir               The apps directory.
     * @param packageName           The package name of the AUT.
     */
    public ModularCDG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                           Path appsDir, String packageName) {
        super(GraphUtils.constructModularCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses),
                appsDir, packageName);
    }
}

