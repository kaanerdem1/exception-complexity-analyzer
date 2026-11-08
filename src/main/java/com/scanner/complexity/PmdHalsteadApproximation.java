package com.scanner.complexity;

import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTInfixExpression;
import net.sourceforge.pmd.lang.java.ast.ASTLiteral;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;

import java.util.LinkedHashMap;
import java.util.Map;

/** Halstead yaklasimi — yalnizca PMD AST (JavaParser yok). */
final class PmdHalsteadApproximation {

    /** Parser LegacyAstMetricsVisitor effort ile mock kalibrasyonu (~1.28). */
    static final double EFFORT_CALIBRATION = 1.52;

    private PmdHalsteadApproximation() {
    }

    static HalsteadMetrics analyze(ASTExecutableDeclaration exec) {
        Map<String, Integer> operators = new LinkedHashMap<>();
        Map<String, Integer> operands = new LinkedHashMap<>();
        exec.descendants().forEach(node -> {
            if (node instanceof ASTMethodCall) {
                bump(operators, "()");
            } else if (node instanceof ASTInfixExpression infix) {
                bump(operators, infix.getOperator().toString());
            } else if (node instanceof ASTLiteral lit) {
                bump(operands, "L:" + truncate(lit.getLiteralText().toString()));
            } else if (node instanceof ASTVariableAccess access) {
                bump(operands, "V:" + access.getName());
            }
        });
        HalsteadMetrics raw = HalsteadMetrics.from(operators, operands);
        if (raw.effort() <= 0.0) {
            return raw;
        }
        double effort = Math.round(raw.effort() * EFFORT_CALIBRATION * 100.0) / 100.0;
        double difficulty = Math.round(raw.difficulty() * Math.sqrt(EFFORT_CALIBRATION) * 100.0) / 100.0;
        return new HalsteadMetrics(raw.distinctOperators(), raw.totalOperators(), raw.distinctOperands(),
                raw.totalOperands(), raw.vocabulary(), raw.length(), raw.volume(), difficulty, effort);
    }

    private static void bump(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 24 ? text.substring(0, 24) : text;
    }
}
