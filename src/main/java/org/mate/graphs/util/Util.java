package org.mate.graphs.util;

import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.ReturnStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;

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

        // TODO: Reformulate condition to be easier to understand.

        final Statement firstStatement = ((BlockStatement) statement).getFirstStatement();
        BasicStatement basicStatement;
        if (firstStatement.getType() != Statement.StatementType.RETURN_STATEMENT) {
            basicStatement = (BasicStatement) firstStatement;
        } else if (((BlockStatement) statement).getStatements().size() > 1) { // return statement
            basicStatement = (BasicStatement) ((BlockStatement) statement).getStatements().get(1);
        } else {
            // Unused methods may only have the virtual return statement in their statements list.
            return ((ReturnStatement) firstStatement).getId();
        }
        return basicStatement.getInstructionIndex();
    }

}
