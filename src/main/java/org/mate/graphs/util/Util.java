package org.mate.graphs.util;

import com.android.tools.smali.dexlib2.analysis.AnalyzedInstruction;
import com.android.tools.smali.dexlib2.builder.BuilderDebugItem;
import com.android.tools.smali.dexlib2.iface.debug.LineNumber;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;

import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class Util {

    private Util() {
        throw new UnsupportedOperationException("Utility constructor!");
    }

    /**
     * Extracts the instruction index from a given {@link Statement}.
     *
     * @param statement The statement from which the instruction index is to be extracted.
     * @return Returns the instruction index of the given statement.
     */
    public static int getInstructionIndexFromBlockStatement(final Statement statement) {

        final Statement firstStatement = ((BlockStatement) statement).getFirstStatement();
        BasicStatement basicStatement;

        if (firstStatement.getType() != Statement.StatementType.RETURN_STATEMENT) {
            basicStatement = (BasicStatement) firstStatement;
        } else {
            basicStatement = (BasicStatement) ((BlockStatement) statement).getStatements().get(1);
        }
        return basicStatement.getInstructionIndex();
    }

    /**
     * Retrieves the fully-qualified method name from the given trace.
     *
     * @param trace The given trace.
     * @return Returns the method name encapsulated in the trace.
     */
    public static String traceToMethod(final String trace) {
        final String[] parts = trace.split("->");
        return parts[0] + "->" + parts[1];
    }

    /**
     * Computes the traces for the given statement. A trace encodes the full-qualified method name and the instruction
     * index, e.g. Lcom/zola/bmi/onStop()V->3.
     *
     * @param statement The given statement.
     * @return Returns the traces for the statement.
     */
    public static Stream<String> tracesForStatement(final Statement statement) {
        return getInstructions(statement)
                .map(instruction -> statement.getMethod() + "->" + instruction.getInstructionIndex());
    }

    /**
     * Retrieves the instructions of the given statement.
     *
     * @param statement The given statement.
     * @return Returns the instructions belonging to the statement.
     */
    public static Stream<AnalyzedInstruction> getInstructions(final Statement statement) {
        if (statement instanceof BasicStatement) {
            return Stream.of(((BasicStatement) statement).getInstruction());
        } else if (statement instanceof BlockStatement) { // basic block, unroll instructions
            return ((BlockStatement) statement).getStatements().stream().flatMap(Util::getInstructions);
        } else {
            return Stream.empty();
        }
    }

    /**
     * Returns the optional line number from the debug items if present.
     *
     * @param debugItems The debug items attached to an instruction.
     * @return Returns the (source code) line number if present in the debug items.
     */
    public static Optional<Integer> getLineNumber(final Set<BuilderDebugItem> debugItems) {
        return debugItems.stream().map(a -> {
            if (a instanceof LineNumber) {
                return Optional.of((LineNumber) a);
            } else {
                return Optional.<LineNumber>empty();
            }
        }).flatMap(Optional::stream)
                .findAny().map(LineNumber::getLineNumber);
    }

    /**
     * Checks whether the given collection contains exactly one element.
     *
     * @param collection The collection to be verified.
     * @param <T> The element type of the collection entries.
     * @return Returns the single element in the collection or throws an exception otherwise.
     */
    public static <T> T expectOne(final Collection<T> collection) {
        if (collection.isEmpty()) {
            throw new NoSuchElementException("Empty collection!");
        } else if (collection.size() > 1) {
            throw new IllegalArgumentException("Collection contains more than one element!");
        } else {
            return collection.stream().findAny().orElseThrow();
        }
    }

    /**
     * Checks whether the given class represents a primitive class.
     *
     * @param className The class to be checked.
     * @return Returns {@code true} if the class is a primitive class, otherwise {@code false}.
     */
    public static boolean isPrimitiveClass(final String className) {
        if (className.startsWith("[")) { // array type
            return false;
        } else {
            return !className.startsWith("L"); // no object type
        }
    }
}
