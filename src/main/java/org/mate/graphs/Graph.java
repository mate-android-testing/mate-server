package org.mate.graphs;

import java.util.List;

public interface Graph {

    // number of vertices
    int size();

    // package name of app
    String getAppName();

    List<String> getBranches();
}
