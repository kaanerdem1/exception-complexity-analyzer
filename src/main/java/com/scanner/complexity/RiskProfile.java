package com.scanner.complexity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** Loaded from YAML; drives {@link RiskCalculator}. */
public record RiskProfile(
        String profileId,
        String modelVersion,
        double compoundFactor,
        double dominantWeight,
        double blendWeight,
        List<ScoredDimension<MethodScanValues>> methodDimensions,
        List<DimensionGroup> methodDimensionGroups,
        List<ScoredDimension<ClassScanValues>> classDimensions,
        Map<String, ThresholdTriple> godMethodThresholds,
        List<MethodLocScoreCap> methodLocScoreCaps) {

    /** Upper bound on final method score when {@code codeLines} is at or below {@link #maxCodeLinesInclusive()}. */
    public record MethodLocScoreCap(int maxCodeLinesInclusive, double maxScore) {
        public MethodLocScoreCap {
            if (maxCodeLinesInclusive < 0) {
                throw new IllegalArgumentException("maxCodeLinesInclusive must be >= 0");
            }
            if (maxScore < 0 || maxScore > RiskCalculator.SCORE_MAX) {
                throw new IllegalArgumentException("maxScore must be in [0, 100]");
            }
        }
    }

    public record DimensionGroup(String id, double weight, List<String> memberIds) {
    }

    public RiskProfile {
        if (dominantWeight <= 0 && blendWeight <= 0) {
            double legacyBlend = compoundFactor > 0 ? compoundFactor : 0.15;
            dominantWeight = 1.0 - legacyBlend;
            blendWeight = legacyBlend;
        }
        double sum = dominantWeight + blendWeight;
        if (sum <= 0) {
            dominantWeight = 0.65;
            blendWeight = 0.35;
            sum = 1.0;
        }
        dominantWeight = dominantWeight / sum;
        blendWeight = blendWeight / sum;
        methodLocScoreCaps = methodLocScoreCaps == null ? List.of() : List.copyOf(methodLocScoreCaps);
    }

    /** Applies all matching tiers; smallest {@code maxScore} wins when several tiers match. */
    public double capMethodScoreByLoc(int codeLines, double score) {
        if (methodLocScoreCaps.isEmpty() || codeLines < 0) {
            return score;
        }
        double capped = score;
        for (MethodLocScoreCap tier : methodLocScoreCaps) {
            if (codeLines <= tier.maxCodeLinesInclusive()) {
                capped = Math.min(capped, tier.maxScore());
            }
        }
        return capped;
    }

    public record ThresholdTriple(int dikkat, int yuksek, int kritik) {
    }

    public record ScoredDimension<T>(String id, String label, double weight, ThresholdTriple thresholds,
                                     ToIntFunction<T> value) {
        int medium() {
            return thresholds.dikkat();
        }

        int high() {
            return thresholds.yuksek();
        }

        int critical() {
            return thresholds.kritik();
        }
    }

    public record ClassScanValues(
            int codeLines,
            int wmc,
            int publicMethodCount,
            int efferentCouplingProxy,
            int efferentTypeCount,
            int lcom3Times100,
            int halsteadEffortRounded,
            int swallowedExceptionSmells,
            int genericExceptionSmells,
            int rawTypeUsage,
            int stringConcatInLoop,
            int hardcodedLiteralCount,
            int godClassIndicator) {

        public static ClassScanValues structuralOnly(int codeLines, int wmc, int publicMethodCount,
                                                     int efferentCouplingProxy) {
            return new ClassScanValues(codeLines, wmc, publicMethodCount, efferentCouplingProxy,
                    0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public Optional<ThresholdTriple> threshold(String dimensionId) {
        return methodDimensions.stream()
                .filter(d -> d.id().equals(dimensionId))
                .findFirst()
                .map(ScoredDimension::thresholds);
    }
}
