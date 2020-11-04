package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Vertex;

import java.util.List;

public interface Graph {

    // number of vertices
    int size();

    // package name of app
    String getAppName();

    // a string encoding of the branches
    List<String> getBranches();

    // the actual branch vertices
    List<Vertex> getBranchVertices();

    // get the distance between the target vertex and the source vertex
    int getDistance(Vertex source, Vertex target);

    // searches for a vertex in the graph based on the given trace
    Vertex lookupVertex(String trace);
}
