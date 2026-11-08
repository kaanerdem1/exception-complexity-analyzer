package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileError;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.RiskHotspot;
import com.scanner.complexity.AnalysisReport.Summary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Proje özeti ve hotspot listesi (parser + PMD taraması ortak). */
final class ReportAggregates {

    private ReportAggregates() {
    }

    static Summary buildSummary(int scanned, List<FileMetric> files, List<FileError> errors, Path scanRoot) {
        ModuleRootIndex moduleRoots = ModuleRootIndex.forScanRoot(scanRoot);
        ProjectSummaryStats stats = new ProjectSummaryStats();
        int classCount = 0;
        int totalLoc = 0;
        for (FileMetric file : files) {
            totalLoc += file.codeLines();
            classCount += file.classCount();
            String moduleRoot = moduleRoots.moduleRoot(file.path());
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    stats.addMethod(moduleRoot, method);
                }
            }
        }
        return stats.toSummary(scanned, files.size(), errors.size(), classCount, totalLoc);
    }

    static List<RiskHotspot> buildHotspots(List<FileMetric> files, int topN) {
        List<RiskHotspot> hotspots = new ArrayList<>();
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric m : type.methods()) {
                    if (MethodAccessorFilter.isSimpleGetterOrSetter(m)) {
                        continue;
                    }
                    hotspots.add(new RiskHotspot(file.path(), file.packageName(), type.name(), m.signature(),
                            m.startLine(), m.riskScore(), m.riskLevel(), m.cyclomaticComplexity(), m.codeLines(),
                            m.maxNestingDepth(), m.parameterCount(), m.cognitiveComplexity(),
                            m.outboundDistinctCalls(), RiskBreakdownUtil.dominantDriver(m.riskBreakdown()),
                            m.riskFactors()));
                }
            }
        }
        hotspots.sort((a, b) -> {
            int byScore = Double.compare(b.riskScore(), a.riskScore());
            return byScore != 0 ? byScore : Integer.compare(b.cyclomaticComplexity(), a.cyclomaticComplexity());
        });
        return hotspots.size() > topN ? new ArrayList<>(hotspots.subList(0, topN)) : hotspots;
    }
}
