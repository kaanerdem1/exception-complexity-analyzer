package com.scanner.complexity;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Code-health proxies: primitive obsession and complex conditionals (AST-only). */
final class MethodQualityMetrics {

    private MethodQualityMetrics() {
    }

    /**
     * Weighted index from signature (primitive / String / boolean flags) and body (literals, primitive locals).
     * Higher = more primitive obsession smell.
     */
    static int primitiveObsessionIndex(CallableDeclaration<?> declaration, Node bodyRoot) {
        int score = 0;
        if (declaration != null) {
            for (Parameter parameter : declaration.getParameters()) {
                score += parameterWeight(parameter);
            }
        }
        if (bodyRoot != null) {
            score += bodyPrimitiveObsession(bodyRoot);
        }
        return score;
    }

    /** Peak {@code &&} / {@code ||} count in any single loop/if condition in the method. */
    static int maxBooleanOperatorsInCondition(Node bodyRoot) {
        if (bodyRoot == null) {
            return 0;
        }
        AtomicInteger peak = new AtomicInteger(0);
        bodyRoot.walk(node -> {
            Expression condition = conditionOf(node);
            if (condition != null) {
                peak.updateAndGet(current -> Math.max(current, countBooleanOperators(condition)));
            }
        });
        return peak.get();
    }

    private static int parameterWeight(Parameter parameter) {
        Type type = parameter.getType();
        int w = 0;
        if (type.isPrimitiveType()) {
            w += 2;
            if (type.asPrimitiveType().getType() == PrimitiveType.Primitive.BOOLEAN) {
                w += 2;
            }
        } else if (isStringType(type)) {
            w += 2;
        } else if (isBoxedBoolean(type)) {
            w += 3;
        }
        String name = parameter.getNameAsString();
        if (looksLikeBooleanFlag(name) && (type.isPrimitiveType()
                || isBoxedBoolean(type))) {
            w += 2;
        }
        return w;
    }

    private static int bodyPrimitiveObsession(Node bodyRoot) {
        int stringLiterals = 0;
        int primitiveLocals = 0;
        for (Node node : bodyRoot.findAll(Node.class)) {
            if (node instanceof StringLiteralExpr) {
                stringLiterals++;
            } else if (node instanceof VariableDeclarator declarator) {
                Type type = declarator.getType();
                if (type != null && (type.isPrimitiveType() || isStringType(type))) {
                    primitiveLocals++;
                }
            }
        }
        return Math.min(stringLiterals, 12) + Math.min(primitiveLocals, 8);
    }

    private static boolean isStringType(Type type) {
        if (!type.isClassOrInterfaceType()) {
            return false;
        }
        String name = type.asClassOrInterfaceType().getNameAsString();
        return "String".equals(name);
    }

    private static boolean isBoxedBoolean(Type type) {
        return type.isClassOrInterfaceType()
                && "Boolean".equals(type.asClassOrInterfaceType().getNameAsString());
    }

    private static boolean looksLikeBooleanFlag(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("is") || lower.startsWith("has") || lower.startsWith("can")
                || lower.startsWith("should") || lower.startsWith("enable") || lower.startsWith("disable");
    }

    private static Expression conditionOf(Node node) {
        if (node instanceof IfStmt ifStmt) {
            return ifStmt.getCondition();
        }
        if (node instanceof WhileStmt whileStmt) {
            return whileStmt.getCondition();
        }
        if (node instanceof DoStmt doStmt) {
            return doStmt.getCondition();
        }
        if (node instanceof ForStmt forStmt) {
            return forStmt.getCompare().orElse(null);
        }
        return null;
    }

    static int countBooleanOperators(Expression expression) {
        if (expression == null) {
            return 0;
        }
        AtomicInteger count = new AtomicInteger(0);
        expression.walk(expr -> {
            if (expr instanceof BinaryExpr binary) {
                BinaryExpr.Operator op = binary.getOperator();
                if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
                    count.incrementAndGet();
                }
            }
        });
        return count.get();
    }
}
