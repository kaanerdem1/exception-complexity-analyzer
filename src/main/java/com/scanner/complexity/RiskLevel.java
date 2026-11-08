package com.scanner.complexity;

/**
 * Risk bands on the 0–100 score scale (see {@link RiskCalculator#SCORE_MAX}).
 */
public enum RiskLevel {
    LOW(0.0, 30.0),
    MEDIUM(30.0, 50.0),
    HIGH(50.0, 80.0),
    CRITICAL(80.0, 100.0);

    private final double lowerBound;
    private final double upperBound;

    RiskLevel(double lowerBound, double upperBound) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    public static RiskLevel fromScore(double score) {
        if (score >= CRITICAL.lowerBound) return CRITICAL;
        if (score >= HIGH.lowerBound) return HIGH;
        if (score >= MEDIUM.lowerBound) return MEDIUM;
        return LOW;
    }

    /** Upper display bound for this band (100 for CRITICAL; no score flattening). */
    public double bandUpper() {
        return upperBound;
    }
}
