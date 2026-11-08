package com.scanner.complexity;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Metod akışından proje özeti: dağılım, LOC-ağırlıklı ortalama, kuyruk (p95/p99) metrikleri. */
final class ProjectSummaryStats {

    private static final int SCORE_BUCKETS = 100;

    private final Map<RiskLevel, LongAdder> distribution = new EnumMap<>(RiskLevel.class);
    private final LongAdder[] scoreBuckets = new LongAdder[SCORE_BUCKETS];
    private final LongAdder highPlusCriticalLoc = new LongAdder();
    private final LongAdder totalMethodLoc = new LongAdder();
    private final LongAdder criticalMethodCount = new LongAdder();
    private final LongAdder methodCount = new LongAdder();
    private final LongAdder totalCyclomatic = new LongAdder();
    private final LongAdder godMethodCount = new LongAdder();
    private double weightedScoreSum;
    private long weightedLocSum;
    private int maxCyclomatic;
    private final ModuleSummaryStats moduleSummaries = new ModuleSummaryStats();

    ProjectSummaryStats() {
        for (RiskLevel level : RiskLevel.values()) {
            distribution.put(level, new LongAdder());
        }
        for (int i = 0; i < SCORE_BUCKETS; i++) {
            scoreBuckets[i] = new LongAdder();
        }
    }

    synchronized void addMethod(String moduleRoot, MethodMetric method) {
        moduleSummaries.addMethod(moduleRoot, method);
        methodCount.increment();
        totalCyclomatic.add(method.cyclomaticComplexity());
        maxCyclomatic = Math.max(maxCyclomatic, method.cyclomaticComplexity());
        distribution.get(method.riskLevel()).increment();
        if (method.godMethod()) {
            godMethodCount.increment();
        }
        long loc = Math.max(0, method.codeLines());
        totalMethodLoc.add(loc);
        if (method.riskLevel() == RiskLevel.HIGH || method.riskLevel() == RiskLevel.CRITICAL) {
            highPlusCriticalLoc.add(loc);
        }
        if (method.riskLevel() == RiskLevel.CRITICAL) {
            criticalMethodCount.increment();
        }
        long weight = Math.max(1, loc);
        weightedScoreSum += method.riskScore() * weight;
        weightedLocSum += weight;
        int bucket = Math.min(SCORE_BUCKETS - 1, (int) method.riskScore());
        scoreBuckets[bucket].increment();
    }

    AnalysisReport.Summary toSummary(int filesScanned, int filesParsed, int filesFailed, int classCount,
                                       int totalFileCodeLines) {
        long methods = methodCount.sum();
        double avgCc = methods == 0 ? 0.0 : Math.round(totalCyclomatic.sum() * 100.0 / methods) / 100.0;
        double projectScore = weightedLocSum == 0 ? 0.0 : RiskCalculator.round3(weightedScoreSum / weightedLocSum);
        long methodLoc = totalMethodLoc.sum();
        double highLocRatio = methodLoc == 0 ? 0.0
                : RiskCalculator.round3(highPlusCriticalLoc.sum() / (double) methodLoc);
        double criticalPerKloc = methodLoc == 0 ? 0.0
                : RiskCalculator.round3(criticalMethodCount.sum() * 1000.0 / methodLoc);
        Map<RiskLevel, Long> dist = new EnumMap<>(RiskLevel.class);
        for (RiskLevel level : RiskLevel.values()) {
            dist.put(level, distribution.get(level).sum());
        }
        return new AnalysisReport.Summary(
                filesScanned, filesParsed, filesFailed,
                classCount, (int) methods, totalFileCodeLines,
                avgCc, maxCyclomatic, (int) godMethodCount.sum(),
                projectScore, RiskLevel.fromScore(projectScore), dist,
                highLocRatio, criticalPerKloc, percentile(0.95), percentile(0.99),
                moduleSummaries.toSummaries());
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
