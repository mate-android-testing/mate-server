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
     * The bytecode lines (basic block vertices in the intraCFG) that map to the encoded source code line number of
     * the stack trace line.
     */
    private final Set<CFGVertex> sourceCodeLineNumberIntraCFGVertices;

    /**
     * The intraCFG corresponding to the method encoded in the stack trace line.
     */
    private final IntraCFG intraCFG;

    /**
     * The required constructors to properly invoke the method in the stack trace line. This includes the class
     * constructors or the static constructor of the method encoded in the stack trace line plus the constructors
     * required to properly initialise the parameters of the method. Furthermore, class or static constructors are
     * considered of invoked method within the target method (described by the source code line number).
     */
    private final Set<String> requiredConstructorCalls;

    // TODO: Add reference to original stack trace line?

    /**
     * Creates an analyzed stack trace line.
     *
     * @param intraCFG The intraCFG referring to the method encoded in the stack trace line.
     * @param sourceCodeLineNumberIntraCFGVertices The intraCFG vertices referring to the source code line number encoded
     *         in the stack trace line.
     * @param requiredConstructorCalls The set of required constructor calls such that the stack trace line can be covered.
     */
    public AnalyzedStackTraceLine(final IntraCFG intraCFG,
                                  final Set<CFGVertex> sourceCodeLineNumberIntraCFGVertices,
                                  final Set<String> requiredConstructorCalls) {
        this.intraCFG = intraCFG;
        this.sourceCodeLineNumberIntraCFGVertices = sourceCodeLineNumberIntraCFGVertices;
        this.requiredConstructorCalls = requiredConstructorCalls;
    }

    /**
     * Retrieves the set of basic block vertices in the intraCFG that map to the encoded source code line number of
     * the stack trace line.
     *
     * @return Returns the set of intraCFG vertices that map to the source code line number in the stack trace line.
     */
    public Set<CFGVertex> getSourceCodeLineNumberIntraCFGVertices() {
        return Collections.unmodifiableSet(sourceCodeLineNumberIntraCFGVertices);
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
