package org.mate.crash_reproduction;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import org.mate.graphs.IntraCFG;

import java.util.Collections;
import java.util.Set;

/**
 * Encodes a stack trace line at the basic block (intraCFG) and method level (call tree).
 */
public class AnalyzedStackTraceLine {

    /**
     * The bytecode lines (basic block vertices in the interCFG) that map to the encoded source code line number of
     * the stack trace line.
     */
    private final Set<CFGVertex> interCFGVertices;

    // TODO: might be redundant to the intraCFG vertices...

    /**
     * The bytecode lines (basic block vertices in the intraCFG) that map to the encoded source code line number of
     * the stack trace line.
     */
    private final Set<CFGVertex> IntraCFGVertices;

    /**
     * The intra CFG corresponding to the method encoded in the stack trace line.
     */
    private final IntraCFG intraCFG;

    /**
     * The required constructors to properly invoke the method in the stack trace line. This includes the class
     * constructors or the static constructor of the method encoded in the stack trace line plus the constructors
     * required to properly initialise the parameters of the method.
     */
    private final Set<String> requiredConstructorCalls;

    // TODO: Add reference to original stack trace line?
    public AnalyzedStackTraceLine(Set<CFGVertex> interCFGVertices,
                                  IntraCFG intraCFG,
                                  Set<CFGVertex> IntraCFGVertices,
                                  Set<String> requiredConstructorCalls) {
        this.interCFGVertices = interCFGVertices;
        this.intraCFG = intraCFG;
        this.IntraCFGVertices = IntraCFGVertices;
        this.requiredConstructorCalls = requiredConstructorCalls;
    }

    /**
     * Retrieves the set of basic block vertices in the interCFG that map to the encoded source code line number of
     * the stack trace line.
     *
     * @return Returns the set of interCFG vertices that map to the source code line number in the stack trace line.
     */
    public Set<CFGVertex> getInterCFGVertices() {
        return Collections.unmodifiableSet(interCFGVertices);
    }

    /**
     * Retrieves the set of basic block vertices in the intraCFG that map to the encoded source code line number of
     * the stack trace line.
     *
     * @return Returns the set of intraCFG vertices that map to the source code line number in the stack trace line.
     */
    public Set<CFGVertex> getIntraCFGVertices() {
        return Collections.unmodifiableSet(IntraCFGVertices);
    }

    /**
     * Retrieves the intra-procedural CFG corresponding to the method encoded in the stack trace line.
     *
     * @return Returns the intra-procedural CFG corresponding to the method encoded in the stack trace line.
     */
    public IntraCFG getIntraCFG() {
        return intraCFG;
    }

    /**
     * Retrieves the set of required constructors to properly invoke the method encoded in the stack trace line.
     *
     * @return Returns the set of required constructors.
     */
    public Set<String> getRequiredConstructorCalls() {
        return Collections.unmodifiableSet(requiredConstructorCalls);
    }
}
