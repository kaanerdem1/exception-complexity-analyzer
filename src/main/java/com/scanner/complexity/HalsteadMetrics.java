package com.scanner.complexity;

import java.util.Map;

/**
 * Practical Halstead approximation from JavaParser AST (see Downloads / legacy analyzer docs).
 */
public record HalsteadMetrics(
        int distinctOperators,
        int totalOperators,
        int distinctOperands,
        int totalOperands,
        int vocabulary,
        int length,
        double volume,
        double difficulty,
        double effort) {

    static final HalsteadMetrics EMPTY = new HalsteadMetrics(0, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0);

    static HalsteadMetrics from(Map<String, Integer> operators, Map<String, Integer> operands) {
        int n1 = operators.size();
        int n2 = operands.size();
        if (n1 == 0 && n2 == 0) {
            return EMPTY;
        }
        int bigN1 = operators.values().stream().mapToInt(Integer::intValue).sum();
        int bigN2 = operands.values().stream().mapToInt(Integer::intValue).sum();
        int vocabulary = n1 + n2;
        int length = bigN1 + bigN2;
        double volume = vocabulary <= 1 ? 0.0 : length * (Math.log(vocabulary) / Math.log(2));
        double difficulty = n2 == 0 ? 0.0 : (n1 / 2.0) * (bigN2 / (double) n2);
        double effort = difficulty * volume;
        return new HalsteadMetrics(n1, bigN1, n2, bigN2, vocabulary, length,
                round2(volume), round2(difficulty), round2(effort));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
