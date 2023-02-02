package org.mate.graphs.util;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;

import java.util.Objects;

/**
 * Encapsulates two vertices in a pair. Primarily used in the cache for the approach level (shortest path distance)
 * computation. To reduce the size of the cache, {@link #equals(Object)} and {@link #hashCode()} are such constructed
 * that the order of the vertices doesn't matter.
 */
public class VertexPair {

    // TODO: Reduce the memory footprint by considering only the (unique!) hashcode of the vertices.
    private final Vertex source;
    private final Vertex target;

    public VertexPair(Vertex source, Vertex target) {
        this.source = source;
        this.target = target;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VertexPair that = (VertexPair) o;
        return source.equals(that.source) && target.equals(that.target)
                // reversed order
                || source.equals(that.target) && target.equals(that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target) + Objects.hash(target, source);
    }
}
