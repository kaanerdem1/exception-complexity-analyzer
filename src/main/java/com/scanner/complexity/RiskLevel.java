package com.scanner.complexity;

/**
 * Four labels on the 0–100 complexity score (high = bad).
 * Cuts follow the PDF band edges: çok iyi 0–24, zayıf 75–89, kritik 90–100.
 * İyi (25–49) and orta (50–74) share MEDIUM. The five names are {@code complexityBand}.
 */
public enum RiskLevel {
    LOW(0.0, 25.0),
    MEDIUM(25.0, 75.0),
    HIGH(75.0, 90.0),
    CRITICAL(90.0, 100.0);

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
