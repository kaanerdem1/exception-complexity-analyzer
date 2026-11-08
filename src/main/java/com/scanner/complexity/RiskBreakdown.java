package com.scanner.complexity;

import java.util.Map;

/**
 * Transparent numeric decomposition of method risk scoring.
 *
 * <p>Core four dimensions keep dedicated fields for Markdown/PMD parity; further profile
 * dimensions appear in {@link #extraSubScores}.
 */
public record RiskBreakdown(
        int cyclomaticComplexity,
        int codeLines,
        int maxNestingDepth,
        int parameterCount,
        double ccSubScore,
        double locSubScore,
        double nestingSubScore,
        double paramsSubScore,
        double cognitiveSubScore,
        double outboundSubScore,
        double exitSubScore,
        Map<String, Double> extraSubScores,
        double dominantSubScore,
        double weightedBlend,
        double compoundBonus,
        double finalScore) {

    public RiskBreakdown {
        extraSubScores = extraSubScores == null ? Map.of() : Map.copyOf(extraSubScores);
    }

    static RiskBreakdown legacyV1(
            int cc, int loc, int nest, int params,
            double ccSub, double locSub, double nestSub, double paramsSub,
            double cognitiveSub, double outboundSub, double exitSub,
            Map<String, Double> extra,
            double dominant, double blend, double bonus, double finalScore) {
        return new RiskBreakdown(cc, loc, nest, params,
                ccSub, locSub, nestSub, paramsSub,
                cognitiveSub, outboundSub, exitSub, extra,
                dominant, blend, bonus, finalScore);
    }
}
