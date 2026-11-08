package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.RiskHotspot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * SUMMARY modunda metod ağacını bellekte biriktirmeden özet + top-N hotspot toplar.
 */
final class ScanAccumulator {

    private final int topN;
    private final ModuleRootIndex moduleRoots;
    private final ProjectSummaryStats summaryStats = new ProjectSummaryStats();
    private final LongAdder classCount = new LongAdder();
    private final LongAdder totalCodeLines = new LongAdder();
    private final PriorityQueue<RiskHotspot> hotspotHeap;
    private final Object statsLock = new Object();

    ScanAccumulator(int topN, ModuleRootIndex moduleRoots) {
        this.topN = Math.max(1, topN);
        this.moduleRoots = moduleRoots;
        this.hotspotHeap = new PriorityQueue<>(Comparator
                .comparingDouble(RiskHotspot::riskScore)
                .thenComparingInt(RiskHotspot::cyclomaticComplexity));
    }

    void ingestFile(String relativePath, String packageName, List<ClassMetric> classes, int fileCodeLines) {
        totalCodeLines.add(fileCodeLines);
        classCount.add(classes.size());
        String moduleRoot = moduleRoots.moduleRoot(relativePath);
        for (ClassMetric type : classes) {
            for (MethodMetric method : type.methods()) {
                summaryStats.addMethod(moduleRoot, method);
                if (MethodAccessorFilter.isSimpleGetterOrSetter(method)) {
                    continue;
                }
                offerHotspot(new RiskHotspot(
                        relativePath, packageName, type.name(), method.signature(), method.startLine(),
                        method.riskScore(), method.riskLevel(), method.cyclomaticComplexity(),
                        method.codeLines(), method.maxNestingDepth(), method.parameterCount(),
                        method.cognitiveComplexity(), method.outboundDistinctCalls(),
                        RiskBreakdownUtil.dominantDriver(method.riskBreakdown()), method.riskFactors()));
            }
        }
    }

    private void offerHotspot(RiskHotspot hotspot) {
        synchronized (statsLock) {
            if (hotspotHeap.size() < topN) {
                hotspotHeap.offer(hotspot);
                return;
            }
            RiskHotspot weakest = hotspotHeap.peek();
            if (weakest != null && compareHotspot(hotspot, weakest) > 0) {
                hotspotHeap.poll();
                hotspotHeap.offer(hotspot);
            }
        }
    }

    private static int compareHotspot(RiskHotspot a, RiskHotspot b) {
        int byScore = Double.compare(a.riskScore(), b.riskScore());
        return byScore != 0 ? byScore : Integer.compare(a.cyclomaticComplexity(), b.cyclomaticComplexity());
    }

    AnalysisReport.Summary toSummary(int filesScanned, int filesParsed, int filesFailed) {
        return summaryStats.toSummary(filesScanned, filesParsed, filesFailed,
                (int) classCount.sum(), (int) totalCodeLines.sum());
    }

    List<RiskHotspot> topHotspots() {
        synchronized (statsLock) {
            List<RiskHotspot> list = new ArrayList<>(hotspotHeap);
            list.sort((a, b) -> compareHotspot(b, a));
            return list;
        }
    }
}
