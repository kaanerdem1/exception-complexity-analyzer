package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.ModuleRiskSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-module-root aggregates (p95 risk, KRİTİK/KLOC). */
final class ModuleSummaryStats {

    private final Map<String, ModuleBucket> buckets = new HashMap<>();

    void addMethod(String moduleRoot, MethodMetric method) {
        buckets.computeIfAbsent(moduleRoot == null || moduleRoot.isBlank() ? "." : moduleRoot,
                k -> new ModuleBucket()).add(method);
    }

    List<ModuleRiskSummary> toSummaries() {
        List<ModuleRiskSummary> list = new ArrayList<>();
        for (Map.Entry<String, ModuleBucket> entry : buckets.entrySet()) {
            list.add(entry.getValue().toSummary(entry.getKey()));
        }
        list.sort(Comparator.comparingDouble(ModuleRiskSummary::methodRiskScoreP95).reversed());
        return List.copyOf(list);
    }

    private static final class ModuleBucket {
        private static final int SCORE_BUCKETS = 100;
        private final LongAdderCompat methodCount = new LongAdderCompat();
        private final LongAdderCompat codeLines = new LongAdderCompat();
        private final LongAdderCompat criticalCount = new LongAdderCompat();
        private final LongAdderCompat[] scoreBuckets = new LongAdderCompat[SCORE_BUCKETS];
        private double weightedScoreSum;
        private long weightedLocSum;

        ModuleBucket() {
            for (int i = 0; i < SCORE_BUCKETS; i++) {
                scoreBuckets[i] = new LongAdderCompat();
            }
        }

        void add(MethodMetric method) {
            methodCount.add(1);
            long loc = Math.max(0, method.codeLines());
            codeLines.add(loc);
            if (method.riskLevel() == RiskLevel.CRITICAL) {
                criticalCount.add(1);
            }
            long weight = Math.max(1, loc);
            weightedScoreSum += method.riskScore() * weight;
            weightedLocSum += weight;
            int bucket = Math.min(SCORE_BUCKETS - 1, (int) method.riskScore());
            scoreBuckets[bucket].add(1);
        }

        ModuleRiskSummary toSummary(String moduleRoot) {
            long methods = methodCount.sum();
            long loc = codeLines.sum();
            double locWeightedScore = weightedLocSum == 0 ? 0.0
                    : RiskCalculator.roundScore(weightedScoreSum / weightedLocSum);
            double criticalPerKloc = loc == 0 ? 0.0
                    : RiskCalculator.roundScore(criticalCount.sum() * 1000.0 / loc);
            return new ModuleRiskSummary(moduleRoot, (int) methods, (int) loc,
                    locWeightedScore, percentile(0.95), criticalPerKloc);
        }

        private double percentile(double p) {
            long total = methodCount.sum();
            if (total == 0) {
                return 0.0;
            }
            long target = Math.max(1, (long) Math.ceil(p * total));
            long seen = 0;
            for (int bucket = 0; bucket < SCORE_BUCKETS; bucket++) {
                seen += scoreBuckets[bucket].sum();
                if (seen >= target) {
                    return RiskCalculator.roundScore(bucket + 0.5);
                }
            }
            return RiskCalculator.SCORE_MAX;
        }
    }

    /** Minimal long adder without pulling extra concurrency types into bucket. */
    private static final class LongAdderCompat {
        private long sum;

        synchronized void add(long delta) {
            sum += delta;
        }

        synchronized long sum() {
            return sum;
        }
    }
}
