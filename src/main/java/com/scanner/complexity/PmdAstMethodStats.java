package com.scanner.complexity;

import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.ast.ASTCatchClause;
import net.sourceforge.pmd.lang.java.ast.ASTDoStatement;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTForStatement;
import net.sourceforge.pmd.lang.java.ast.ASTIfStatement;
import net.sourceforge.pmd.lang.java.ast.ASTInfixExpression;
import net.sourceforge.pmd.lang.java.ast.ASTLambdaExpression;
import net.sourceforge.pmd.lang.java.ast.ASTLocalVariableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchLabel;
import net.sourceforge.pmd.lang.java.ast.ASTWhileStatement;

/** PMD AST uzerinden metod sayaclari (JavaParser yok). */
final class PmdAstMethodStats {

    record Stats(
            int maxNestingDepth,
            int logicalStatements,
            int exitPoints,
            int catchClauses,
            int emptyCatchBlocks,
            int catchExceptionOrThrowable,
            int lambdaCount,
            int localVariableCount,
            int maxTryNestingDepth,
            int switchCases,
            int maxBooleanOperatorsInCondition) {
    }

    private PmdAstMethodStats() {
    }

    static Stats analyze(ASTExecutableDeclaration exec) {
        Counter counter = new Counter();
        counter.walk(exec, 0, 0);
        return counter.toStats();
    }

    private static final class Counter {
        int maxNest;
        int statements;
        int exits;
        int catches;
        int emptyCatch;
        int genericCatch;
        int lambdas;
        int locals;
        int maxTryNest;
        int switchCases;
        int maxBoolOps;

        void walk(Node node, int nestDepth, int tryDepth) {
            String name = node.getXPathNodeName();
            int nextNest = nestDepth;
            int nextTry = tryDepth;
            if (isControlFlow(name)) {
                nextNest = nestDepth + 1;
                maxNest = Math.max(maxNest, nextNest);
            }
            if ("TryStatement".equals(name)) {
                nextTry = tryDepth + 1;
                maxTryNest = Math.max(maxTryNest, nextTry);
            }
            switch (name) {
                case "Statement", "ExpressionStatement", "SynchronizedStatement", "AssertStatement" -> statements++;
                case "IfStatement" -> {
                    statements++;
                    if (node instanceof ASTIfStatement stmt) {
                        noteConditionBooleanOps(stmt.getCondition());
                    }
                }
                case "WhileStatement" -> {
                    statements++;
                    if (node instanceof ASTWhileStatement stmt) {
                        noteConditionBooleanOps(stmt.getCondition());
                    }
                }
                case "DoStatement" -> {
                    statements++;
                    if (node instanceof ASTDoStatement stmt) {
                        noteConditionBooleanOps(stmt.getCondition());
                    }
                }
                case "ForStatement" -> {
                    statements++;
                    if (node instanceof ASTForStatement stmt) {
                        noteConditionBooleanOps(stmt.getCondition());
                    }
                }
                case "ForeachStatement" -> statements++;
                case "ReturnStatement", "ThrowStatement", "YieldStatement" -> exits++;
                case "CatchClause" -> {
                    catches++;
                    if (node instanceof ASTCatchClause catchClause) {
                        if (catchClause.getBody().size() == 0) {
                            emptyCatch++;
                        }
                        String type = catchClause.getParameter().getTypeNode().toString();
                        if (type.contains("Exception") || type.contains("Throwable") || type.contains("Error")) {
                            genericCatch++;
                        }
                    }
                }
                case "LambdaExpression" -> lambdas++;
                case "LocalVariableDeclaration" -> locals++;
                case "SwitchLabel" -> {
                    if (node instanceof ASTSwitchLabel label && !label.isDefault()) {
                        switchCases++;
                    }
                }
                default -> { }
            }
            for (Node child : node.children()) {
                walk(child, nextNest, nextTry);
            }
        }

        void noteConditionBooleanOps(Node condition) {
            if (condition == null) {
                return;
            }
            maxBoolOps = Math.max(maxBoolOps, peakBooleanOpsInExpression(condition));
        }

        Stats toStats() {
            return new Stats(maxNest, statements, exits, catches, emptyCatch, genericCatch, lambdas, locals,
                    maxTryNest, switchCases, maxBoolOps);
        }
    }

    private static boolean isControlFlow(String xpathName) {
        return switch (xpathName) {
            case "IfStatement", "WhileStatement", "ForStatement", "ForeachStatement", "DoStatement",
                    "TryStatement", "SwitchStatement", "SynchronizedStatement" -> true;
            default -> false;
        };
    }

    /** Peak {@code &&}/{@code ||} in one if/loop condition (aligned with {@link MethodQualityMetrics}). */
    private static int peakBooleanOpsInExpression(Node root) {
        int peak = 0;
        for (Node node : root.descendants()) {
            if (node instanceof ASTInfixExpression infix) {
                String op = infix.getOperator().toString();
                if ("&&".equals(op) || "||".equals(op)) {
                    peak = Math.max(peak, countBooleanOps(infix));
                }
            }
        }
        if (root instanceof ASTInfixExpression infix) {
            String op = infix.getOperator().toString();
            if ("&&".equals(op) || "||".equals(op)) {
                peak = Math.max(peak, countBooleanOps(infix));
            }
        }
        return peak;
    }

    private static int countBooleanOps(ASTInfixExpression infix) {
        int count = 0;
        if (infix.getOperator().toString().equals("&&") || infix.getOperator().toString().equals("||")) {
            count = 1;
        }
        for (Node child : infix.children()) {
            if (child instanceof ASTInfixExpression nested) {
                count += countBooleanOps(nested);
            }
        }
        return count;
    }
}
