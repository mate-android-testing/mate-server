package org.mate.crash_reproduction;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import org.mate.graphs.IntraCFG;

import java.util.Set;

public class AnalyzedStackTraceLine {
    private final Set<Vertex> targetInterVertices;
    private final IntraCFG intraCFG;
    private final Set<Vertex> targetMethodVertices;

    public AnalyzedStackTraceLine(Set<Vertex> targetInterVertices, IntraCFG intraCFG, Set<Vertex> targetMethodVertices) {
        this.targetInterVertices = targetInterVertices;
        this.intraCFG = intraCFG;
        this.targetMethodVertices = targetMethodVertices;
    }

    public Set<Vertex> getTargetInterVertices() {
        return targetInterVertices;
    }

    public IntraCFG getIntraCFG() {
        return intraCFG;
    }

    public Set<Vertex> getTargetMethodVertices() {
        return targetMethodVertices;
    }
}
