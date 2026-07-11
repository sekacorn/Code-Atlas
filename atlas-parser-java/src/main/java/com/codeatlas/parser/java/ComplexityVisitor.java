package com.codeatlas.parser.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;

/**
 * Computes McCabe cyclomatic complexity for a method body.
 *
 * <p>Complexity starts at 1 and increments for each decision point: {@code if},
 * {@code for}, {@code while}, {@code do}, each non-default {@code switch} label,
 * each {@code catch}, each ternary, and each {@code &&}/{@code ||}. This is the
 * standard, deterministic definition &mdash; no heuristics, no AI.
 */
final class ComplexityVisitor {

    private ComplexityVisitor() {
    }

    static int complexity(Node body) {
        int complexity = 1;
        complexity += body.findAll(IfStmt.class).size();
        complexity += body.findAll(ForStmt.class).size();
        complexity += body.findAll(ForEachStmt.class).size();
        complexity += body.findAll(WhileStmt.class).size();
        complexity += body.findAll(DoStmt.class).size();
        complexity += body.findAll(CatchClause.class).size();
        complexity += body.findAll(ConditionalExpr.class).size();

        // Each non-default switch label is a branch.
        for (SwitchEntry entry : body.findAll(SwitchEntry.class)) {
            complexity += Math.max(1, entry.getLabels().size());
        }

        for (BinaryExpr bin : body.findAll(BinaryExpr.class)) {
            if (bin.getOperator() == BinaryExpr.Operator.AND
                    || bin.getOperator() == BinaryExpr.Operator.OR) {
                complexity++;
            }
        }
        return complexity;
    }
}
