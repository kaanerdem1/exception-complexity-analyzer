package com.scanner.complexity;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;

final class CatchClauseMetrics {

    private CatchClauseMetrics() {
    }

    static boolean isEmpty(CatchClause clause) {
        return clause.getBody().getStatements().isEmpty();
    }

    static boolean catchesExceptionOrThrowable(CatchClause clause) {
        String type = baseTypeName(clause.getParameter().getType().asString());
        return "Exception".equals(type) || "Throwable".equals(type);
    }

    static boolean isPrintStackTraceOnly(CatchClause clause) {
        BlockStmt body = clause.getBody();
        if (body.getStatements().size() != 1) {
            return false;
        }
        Statement statement = body.getStatement(0);
        if (!(statement instanceof ExpressionStmt exprStmt)) {
            return false;
        }
        Expression expression = exprStmt.getExpression();
        if (!(expression instanceof MethodCallExpr call)) {
            return false;
        }
        return "printStackTrace".equals(call.getNameAsString());
    }

    private static String baseTypeName(String type) {
        int generic = type.indexOf('<');
        String raw = generic >= 0 ? type.substring(0, generic) : type;
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }
}
