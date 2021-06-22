package org.mate.graphs.util;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;

public class VertexPair {

    private final Vertex source;
    private final Vertex target;

    public VertexPair(Vertex source, Vertex target) {
        this.source = source;
        this.target = target;
    }

    public Vertex getSource() {
        return source;
    }

    public Vertex getTarget() {
        return target;
    }
}
