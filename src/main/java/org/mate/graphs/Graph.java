package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Edge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

public interface Graph {

    // number of vertices
    int size();

    // package name of app
    String getAppName();

    // the actual branch vertices
    List<Vertex> getBranchVertices();

    // get all vertices
    List<Vertex> getVertices();

    // get the distances between the given source and target vertices
    BiFunction<Vertex, Vertex, Integer> getDistances(Set<Vertex> sources, Set<Vertex> targets);

    // get the distance between the target vertex and the source vertex
    int getDistance(Vertex source, Vertex target);

    // searches for a vertex in the graph based on the given trace
    Vertex lookupVertex(String trace);

    // checks whether vertex is reachable in graph from global entry point
    boolean isReachable(Vertex vertex);

    // draws the raw graph if it is not too big
    void draw(File outputPath);

    // draws the graph where target and visited vertices are marked
    void draw(File outputPath, Set<Vertex> visitedVertices, Set<Vertex> targets);

    // get the outgoing edges from the given vertex
    Set<Edge> getOutgoingEdges(Vertex vertex);

    // get the incoming edges from the given vertex
    Set<Edge> getIncomingEdges(Vertex vertex);
}
