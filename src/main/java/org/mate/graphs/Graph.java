package org.mate.graphs;

import de.uni_passau.fim.auermich.graphs.Vertex;

import java.util.List;

public interface Graph {

    // number of vertices
    int size();

    // package name of app
    String getAppName();

    List<String> getBranches();

    // get the distance between the target vertex and the source vertex
    int getDistance(Vertex source, Vertex target);

    // get the distance between the given vertex and the pre-defined target vertex
    int getDistance(Vertex source);

    // searches for a vertex in the graph based on the given trace
    Vertex lookupVertex(String trace);
}
