package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.RiskCalculator.Assessment;
import com.scanner.complexity.ScanOptions.ReportDetail;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

/** Tek bir {@link CompilationUnit} için {@link FileMetric} üretir (enterprise + legacy katman). */
final class FileMetricsBuilder {

    private FileMetricsBuilder() {
    }

    static FileMetric build(String relativePath, String sourceText,
                            ParserConfiguration.LanguageLevel languageLevel, CompilationUnit cu,
                            RiskCalculator riskCalculator, Map<String, MethodMetric> previousMethods,
                            ScanAccumulator summaryAccumulator, String fileHash, boolean fullyReusedFromCache,
                            boolean pmdMetricAlignment) {
        BitSet codeLines = ComplexityVisitor.computeCodeLines(cu);
        Map<String, SupplementalMetrics.LegacyMethodMetrics> legacyIndex = LegacyMetricsIndex.fromCompilationUnit(cu);
        ComplexityVisitor visitor = new ComplexityVisitor(riskCalculator, codeLines, previousMethods, legacyIndex);
        cu.accept(visitor, null);
        List<ClassMetric> classes = SupplementalMetrics.enrich(cu, visitor.getClassMetrics(), riskCalculator,
                previousMethods);
        if (pmdMetricAlignment && sourceText != null && !sourceText.isEmpty()) {
            classes = PmdMetricsAligner.align(sourceText, relativePath, languageLevel, classes, riskCalculator);
        }

        List<MethodMetric> methods = classes.stream().flatMap(c -> c.methods().stream()).toList();
        int wmc = methods.stream().mapToInt(MethodMetric::cyclomaticComplexity).sum();
        int maxCc = methods.stream().mapToInt(MethodMetric::cyclomaticComplexity).max().orElse(0);
        int loc = codeLines.cardinality();
        Assessment risk = riskCalculator.assessAggregate(methods, loc, wmc, RiskCalculator.Scope.FILE);

        String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        int physicalLines = cu.getRange().map(r -> r.end.line).orElse(0);

        int methodsReused = (int) methods.stream().filter(MethodMetric::analysisReused).count();
        int swallowed = classes.stream().mapToInt(ClassMetric::swallowedExceptionCount).sum();
        int generic = classes.stream().mapToInt(ClassMetric::genericExceptionCatchCount).sum();
        int raw = classes.stream().mapToInt(ClassMetric::rawTypeUsageCount).sum();
        int concat = classes.stream().mapToInt(ClassMetric::stringConcatInLoopCount).sum();
        int hardcoded = classes.stream().mapToInt(ClassMetric::hardcodedLiteralCount).sum();
        HalsteadMetrics fileHalstead = classes.stream().map(ClassMetric::halstead)
                .filter(h -> h != HalsteadMetrics.EMPTY)
                .max(java.util.Comparator.comparingDouble(HalsteadMetrics::effort))
                .orElse(HalsteadMetrics.EMPTY);

        if (summaryAccumulator != null) {
            summaryAccumulator.ingestFile(relativePath, packageName, classes, loc);
            return new FileMetric(relativePath, packageName, physicalLines, loc, classes.size(), methods.size(),
                    wmc, maxCc, risk.score(), risk.level(), risk.factors(), List.of(),
                    fileHalstead, swallowed, generic, raw, concat, hardcoded,
                    fileHash, fullyReusedFromCache, methodsReused);
        }

        return new FileMetric(relativePath, packageName, physicalLines, loc, classes.size(), methods.size(),
                wmc, maxCc, risk.score(), risk.level(), risk.factors(), classes,
                fileHalstead, swallowed, generic, raw, concat, hardcoded,
                fileHash, fullyReusedFromCache, methodsReused);
    }

    static FileMetric buildSummaryStub(String relativePath, String packageName, int physicalLines, int loc,
                                       int classCount, int methodCount, int wmc, int maxCc, Assessment risk,
                                       String fileHash) {
        return new FileMetric(relativePath, packageName, physicalLines, loc, classCount, methodCount,
                wmc, maxCc, risk.score(), risk.level(), risk.factors(), List.of(),
                HalsteadMetrics.EMPTY, 0, 0, 0, 0, 0, fileHash, false, 0);
    }
}
