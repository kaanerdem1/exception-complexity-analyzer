package com.scanner.complexity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns raw metrics into a normalised 0–100 technical risk score (YAML profile-driven).
 */
public final class RiskCalculator {

    /** Method risk scores use 0–100 with millisecond-style decimals (e.g. 68.374). */
    public static final double SCORE_MAX = 100.0;
    private static final double WORST_METHOD_INFLUENCE = 0.65;

    /** Size thresholds for aggregate (class / file) assessment. */
    public enum Scope {
        CLASS(200, 500, 1000, 30, 50, 100),
        FILE(400, 800, 1500, 60, 100, 200);

        private final int locMedium, locHigh, locCritical;
        private final int wmcMedium, wmcHigh, wmcCritical;

        Scope(int locMedium, int locHigh, int locCritical, int wmcMedium, int wmcHigh, int wmcCritical) {
            this.locMedium = locMedium;
            this.locHigh = locHigh;
            this.locCritical = locCritical;
            this.wmcMedium = wmcMedium;
            this.wmcHigh = wmcHigh;
            this.wmcCritical = wmcCritical;
        }
    }

    private final RiskProfile profile;

    /** Varsayılan: {@link RiskProfileLoader#DEFAULT_PROFILE_ID} (YAML). */
    public RiskCalculator() {
        this(RiskProfileLoader.loadDefaultProfile());
    }

    public RiskCalculator(RiskProfile profile) {
        this.profile = profile;
    }

    public RiskProfile profile() {
        return profile;
    }

    public record Assessment(double score, RiskLevel level, List<String> factors, RiskBreakdown breakdown) {

        Assessment(double score, RiskLevel level, List<String> factors) {
            this(score, level, factors, null);
        }
    }

    public Assessment assessMethod(MethodScanValues values) {
        List<String> factors = new ArrayList<>();
        Map<String, Double> subById = new LinkedHashMap<>();
        double ccSub = 0;
        double locSub = 0;
        double nestSub = 0;
        double paramsSub = 0;
        double cognitiveSub = 0;
        double outboundSub = 0;
        double exitSub = 0;
        Map<String, Double> extraSubScores = new LinkedHashMap<>();

        for (RiskProfile.ScoredDimension<MethodScanValues> dimension : profile.methodDimensions()) {
            int raw = dimension.value().applyAsInt(values);
            double sub = subScore(raw, dimension.medium(), dimension.high(), dimension.critical());
            subById.put(dimension.id(), sub);
            describe(factors, dimension.label(), raw, sub, dimension.high(), dimension.critical());
            switch (dimension.id()) {
                case "branching" -> ccSub = sub;
                case "length" -> locSub = sub;
                case "nesting" -> nestSub = sub;
                case "parameters" -> paramsSub = sub;
                case "cognitive" -> cognitiveSub = sub;
                case "outboundDistinctCalls" -> outboundSub = sub;
                case "exitPoints" -> exitSub = sub;
                default -> extraSubScores.put(dimension.id(), roundScore(sub));
            }
        }

        MixResult mix = blendFromSubScores(subById);
        double dominant = mix.dominant();
        double blend = mix.blend();

        double mixedCore = profile.dominantWeight() * dominant + profile.blendWeight() * blend;
        double fine = discriminativeFine(values);
        double preClamp = mixedCore + fine;
        double preCapScore = clampScore(preClamp);
        double finalScore = profile.capMethodScoreByLoc(values.codeLines(), preCapScore);
        if (finalScore + 0.001 < preCapScore) {
            factors.add(String.format(java.util.Locale.ROOT,
                    "Short-method score cap: %d code line(s) → max %.0f (uncapped %.1f)",
                    values.codeLines(), finalScore, preCapScore));
        }
        Assessment assessment = new Assessment(roundScore(finalScore), RiskLevel.fromScore(finalScore),
                List.copyOf(factors));
        double appliedBonus = roundScore(assessment.score() - mixedCore);
        RiskBreakdown breakdown = RiskBreakdown.legacyV1(
                values.cyclomaticComplexity(), values.codeLines(), values.maxNestingDepth(), values.parameterCount(),
                roundScore(ccSub), roundScore(locSub), roundScore(nestSub), roundScore(paramsSub),
                roundScore(cognitiveSub), roundScore(outboundSub), roundScore(exitSub), extraSubScores,
                roundScore(dominant), roundScore(blend), appliedBonus, assessment.score());
        return new Assessment(assessment.score(), assessment.level(), assessment.factors(), breakdown);
    }

    /** Backward-compatible entry (legacy tests). */
    public Assessment assessMethod(int cyclomatic, int codeLines, int nesting, int parameters) {
        return assessMethod(MethodScanValues.zeros(cyclomatic, codeLines, nesting, parameters));
    }

    public AnalysisReport.RiskModel buildRiskModel() {
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, List<Integer>> thresholds = new LinkedHashMap<>();
        for (RiskProfile.ScoredDimension<MethodScanValues> dimension : profile.methodDimensions()) {
            weights.put(dimension.id(), dimension.weight());
            thresholds.put(dimension.id(), List.of(
                    dimension.medium(), dimension.high(), dimension.critical()));
        }
        String formula = "finalScore = clamp(0,100, "
                + profile.dominantWeight() + "*max(subScores) + "
                + profile.blendWeight() + "*weightedBlend + fine); profile=" + profile.profileId();
        return new AnalysisReport.RiskModel(
                profile.modelVersion(),
                formula,
                weights,
                thresholds);
    }

    public static String consoleModelLine(RiskCalculator calculator) {
        return "Profile " + calculator.profile.profileId() + " ("
                + calculator.profile.modelVersion() + "): score 0–100 = "
                + (int) (calculator.profile.dominantWeight() * 100) + "% max(sub) + "
                + (int) (calculator.profile.blendWeight() * 100) + "% weighted blend + fine tie-break.";
    }

    public boolean isGodMethod(MethodScanValues values) {
        RiskProfile.ThresholdTriple cc = profile.godMethodThresholds().get("branching");
        RiskProfile.ThresholdTriple loc = profile.godMethodThresholds().get("length");
        RiskProfile.ThresholdTriple params = profile.godMethodThresholds().get("parameters");
        if (cc == null || loc == null || params == null) {
            return false;
        }
        return values.codeLines() > loc.kritik()
                || values.cyclomaticComplexity() > cc.kritik()
                || (values.codeLines() > loc.yuksek()
                && (values.cyclomaticComplexity() > cc.yuksek() || values.parameterCount() > params.yuksek()));
    }

    public boolean isGodMethod(int cyclomatic, int codeLines, int parameters) {
        return isGodMethod(MethodScanValues.zeros(cyclomatic, codeLines, 0, parameters));
    }

    /** Class-level aggregate (includes optional class dimensions from profile). */
    public Assessment assessClassAggregate(
            List<MethodMetric> methods, int codeLines, int wmc, int publicMethodCount, int efferentCoupling) {
        return assessClassAggregate(methods, RiskProfile.ClassScanValues.structuralOnly(
                codeLines, wmc, publicMethodCount, efferentCoupling));
    }

    public Assessment assessClassAggregate(List<MethodMetric> methods, RiskProfile.ClassScanValues classValues) {
        return assessAggregateInternal(methods, classValues, Scope.CLASS);
    }

    /** File-level aggregate (no class coupling dimensions). */
    public Assessment assessAggregate(List<MethodMetric> methods, int codeLines, int wmc, Scope scope) {
        return assessAggregateInternal(methods,
                RiskProfile.ClassScanValues.structuralOnly(codeLines, wmc, 0, 0), scope);
    }

    private Assessment assessAggregateInternal(
            List<MethodMetric> methods, RiskProfile.ClassScanValues classValues, Scope scope) {
        int codeLines = classValues.codeLines();
        int wmc = classValues.wmc();
        double sSize = subScore(codeLines, scope.locMedium, scope.locHigh, scope.locCritical);
        double sWmc = subScore(wmc, scope.wmcMedium, scope.wmcHigh, scope.wmcCritical);
        double worstMethod = methods.stream().mapToDouble(MethodMetric::riskScore).max().orElse(0.0);
        double avgMethod = methods.stream().mapToDouble(MethodMetric::riskScore).average().orElse(0.0);

        double dominant = Math.max(Math.max(sSize, sWmc), WORST_METHOD_INFLUENCE * worstMethod);
        double classBlend = 0.0;

        if (scope == Scope.CLASS) {
            for (RiskProfile.ScoredDimension<RiskProfile.ClassScanValues> dimension : profile.classDimensions()) {
                int raw = dimension.value().applyAsInt(classValues);
                double sub = subScore(raw, dimension.medium(), dimension.high(), dimension.critical());
                dominant = Math.max(dominant, sub);
                classBlend += dimension.weight() * sub;
            }
        }

        List<String> factors = new ArrayList<>();
        long critical = methods.stream().filter(m -> m.riskLevel() == RiskLevel.CRITICAL).count();
        long high = methods.stream().filter(m -> m.riskLevel() == RiskLevel.HIGH).count();
        if (critical + high > 0) {
            factors.add("Contains " + critical + " critical and " + high + " high-risk method(s)");
        }
        describe(factors, "Code size (lines)", codeLines, sSize, scope.locHigh, scope.locCritical);
        describe(factors, "Total cyclomatic complexity (WMC)", wmc, sWmc, scope.wmcHigh, scope.wmcCritical);

        if (scope == Scope.CLASS) {
            for (RiskProfile.ScoredDimension<RiskProfile.ClassScanValues> dimension : profile.classDimensions()) {
                int raw = dimension.value().applyAsInt(classValues);
                double sub = subScore(raw, dimension.medium(), dimension.high(), dimension.critical());
                describe(factors, dimension.label(), raw, sub, dimension.high(), dimension.critical());
            }
        }

        double blend = scope == Scope.CLASS && !profile.classDimensions().isEmpty()
                ? (avgMethod + classBlend) / 2.0
                : avgMethod;
        double mixed = profile.dominantWeight() * dominant + profile.blendWeight() * blend;
        return composeFromRawScore(mixed, factors);
    }

    private Assessment composeFromRawScore(double rawScore, List<String> factors) {
        double score = clampScore(rawScore);
        RiskLevel level = RiskLevel.fromScore(score);
        return new Assessment(roundScore(score), level, List.copyOf(factors));
    }

    /**
     * Deterministic micro-offset from all raw counters so identical vectors tie, otherwise scores differ
     * (up to ~0.05 on the 0–100 scale, before rounding to 3 decimals).
     */
    static double discriminativeFine(MethodScanValues values) {
        long fingerprint = 0;
        fingerprint = mix(fingerprint, values.cyclomaticComplexity());
        fingerprint = mix(fingerprint, values.codeLines());
        fingerprint = mix(fingerprint, values.maxNestingDepth());
        fingerprint = mix(fingerprint, values.parameterCount());
        fingerprint = mix(fingerprint, values.cognitiveComplexity());
        fingerprint = mix(fingerprint, values.logicalStatements());
        fingerprint = mix(fingerprint, values.exitPoints());
        fingerprint = mix(fingerprint, values.catchClauses());
        fingerprint = mix(fingerprint, values.switchCases());
        fingerprint = mix(fingerprint, values.outboundDistinctCalls());
        fingerprint = mix(fingerprint, values.lambdaCount());
        fingerprint = mix(fingerprint, values.maxTryNestingDepth());
        fingerprint = mix(fingerprint, values.localVariableCount());
        fingerprint = mix(fingerprint, values.maxMethodCallChainLength());
        fingerprint = mix(fingerprint, values.emptyCatchBlocks());
        fingerprint = mix(fingerprint, values.catchExceptionOrThrowable());
        fingerprint = mix(fingerprint, values.catchWithOnlyPrintStackTrace());
        fingerprint = mix(fingerprint, values.primitiveObsessionIndex());
        fingerprint = mix(fingerprint, values.maxBooleanOperatorsInCondition());
        fingerprint = mix(fingerprint, values.halsteadDifficultyRounded());
        fingerprint = mix(fingerprint, values.halsteadEffortRounded());
        fingerprint = mix(fingerprint, values.rawTypeUsage());
        fingerprint = mix(fingerprint, values.stringConcatInLoop());
        fingerprint = mix(fingerprint, values.hardcodedLiteralCount());
        fingerprint = mix(fingerprint, values.swallowedExceptionSmells());
        fingerprint = mix(fingerprint, values.genericExceptionSmells());
        double unit = (fingerprint % 50_000) / 1_000_000.0;
        return unit * SCORE_MAX;
    }

    private static long mix(long acc, int value) {
        return acc * 31L + (value & 0xFFFFL);
    }

    private record MixResult(double dominant, double blend) {
    }

    private MixResult blendFromSubScores(Map<String, Double> subById) {
        if (profile.methodDimensionGroups().isEmpty()) {
            double dominant = 0.0;
            double blend = 0.0;
            for (RiskProfile.ScoredDimension<MethodScanValues> dimension : profile.methodDimensions()) {
                double sub = subById.getOrDefault(dimension.id(), 0.0);
                dominant = Math.max(dominant, sub);
                blend += dimension.weight() * sub;
            }
            return new MixResult(dominant, blend);
        }
        Map<String, RiskProfile.ScoredDimension<MethodScanValues>> dimById = new LinkedHashMap<>();
        for (RiskProfile.ScoredDimension<MethodScanValues> d : profile.methodDimensions()) {
            dimById.put(d.id(), d);
        }
        double dominant = 0.0;
        double blend = 0.0;
        for (RiskProfile.DimensionGroup group : profile.methodDimensionGroups()) {
            double groupMax = 0.0;
            double groupBlend = 0.0;
            double weightSum = 0.0;
            for (String memberId : group.memberIds()) {
                RiskProfile.ScoredDimension<MethodScanValues> dimension = dimById.get(memberId);
                if (dimension == null) {
                    continue;
                }
                double sub = subById.getOrDefault(memberId, 0.0);
                groupMax = Math.max(groupMax, sub);
                groupBlend += dimension.weight() * sub;
                weightSum += dimension.weight();
            }
            if (weightSum > 0) {
                groupBlend /= weightSum;
            }
            dominant = Math.max(dominant, groupMax);
            blend += group.weight() * groupBlend;
        }
        return new MixResult(dominant, blend);
    }

    /** Sub-score on 0–100 (piecewise linear bands). */
    static double subScore(int value, int medium, int high, int critical) {
        double unit;
        if (value <= 0) {
            unit = 0.0;
        } else if (value <= medium) {
            unit = 0.29 * value / medium;
        } else if (value <= high) {
            unit = 0.30 + 0.19 * (value - medium) / (high - medium);
        } else if (value <= critical) {
            unit = 0.50 + 0.29 * (value - high) / (critical - high);
        } else {
            unit = 0.80 + 0.20 * Math.min(1.0, (double) (value - critical) / critical);
        }
        return toDisplayScale(unit);
    }

    static double toDisplayScale(double unitIntervalSubScore) {
        return unitIntervalSubScore * SCORE_MAX;
    }

    static double clampScore(double score) {
        if (score <= 0) {
            return 0;
        }
        return Math.min(SCORE_MAX, score);
    }

    private static void describe(List<String> factors, String label, int value, double subScore,
                                 int high, int critical) {
        RiskLevel level = RiskLevel.fromScore(subScore);
        if (level == RiskLevel.CRITICAL) {
            factors.add(label + " " + value + " exceeds critical threshold (>" + critical + ")");
        } else if (level == RiskLevel.HIGH) {
            factors.add(label + " " + value + " exceeds high-risk threshold (>" + high + ")");
        }
    }

    /** Round to 3 decimals on the 0–100 scale (e.g. 68.374). */
    public static double roundScore(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** @deprecated use {@link #roundScore} */
    public static double round3(double value) {
        return roundScore(value);
    }
}
