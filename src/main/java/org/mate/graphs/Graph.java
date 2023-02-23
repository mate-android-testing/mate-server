package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Edge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

public interface Graph<V extends Vertex, E extends Edge> {

    // number of vertices
    int size();

    // package name of app
    String getAppName();

    // the actual branch vertices
    List<V> getBranchVertices();

    // get all vertices
    List<V> getVertices();

    // get the distances between the given source and target vertices
    BiFunction<V, V, Integer> getDistances(Set<V> sources, Set<V> targets);

    // get the distance between the target vertex and the source vertex
    int getDistance(V source, V target);

    // searches for a vertex in the graph based on the given trace
    V lookupVertex(String trace);

    // checks whether vertex is reachable in graph from global entry point
    boolean isReachable(V vertex);

    // draws the raw graph if it is not too big
    void draw(File outputPath);

    // draws the graph where target and visited vertices are marked
    void draw(File outputPath, Set<V> visitedVertices, Set<V> targets);

    // get the outgoing edges from the given vertex
    Set<E> getOutgoingEdges(V vertex);

    // get the incoming edges from the given vertex
    Set<E> getIncomingEdges(V vertex);
}

