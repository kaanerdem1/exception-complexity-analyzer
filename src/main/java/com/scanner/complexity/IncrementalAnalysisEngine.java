package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * İki aşamalı lazy parsing: dosya hash (Stage 1) + metod gövdesi hash (Stage 2).
 * {@link FileMetricsBuilder} enterprise metrikleri korur; cache yalnızca parse maliyetini düşürür.
 */
final class IncrementalAnalysisEngine {

    private final AnalyzerState previousState;

    IncrementalAnalysisEngine(AnalyzerState previousState) {
        this.previousState = previousState != null ? previousState : new AnalyzerState();
        if (this.previousState.files == null) {
            this.previousState.files = new java.util.LinkedHashMap<>();
        }
    }

    record CacheCheck(String fileHash, FileMetric reusableMetric) {
        boolean canSkipParsing() {
            return reusableMetric != null;
        }
    }

    CacheCheck checkFileCache(Path absoluteFile, String relativePath, String precomputedByteHash)
            throws IOException {
        String fileHash = precomputedByteHash != null ? precomputedByteHash : HashService.hashFile(absoluteFile);
        AnalyzerState.FileState previous = previousState.files.get(relativePath);
        if (previous != null && fileHash.equals(previous.fileHash) && previous.cachedFileMetric != null) {
            return new CacheCheck(fileHash, previous.cachedFileMetric);
        }
        return new CacheCheck(fileHash, null);
    }

    FileMetric reusableFromState(String relativePath) {
        AnalyzerState.FileState previous = previousState.files.get(relativePath);
        if (previous == null || previous.cachedFileMetric == null) {
            return null;
        }
        return previous.cachedFileMetric.reusedCopy();
    }

    String previousSemanticFileHash(String relativePath) {
        AnalyzerState.FileState previous = previousState.files.get(relativePath);
        return previous == null ? null : previous.semanticFileHash;
    }

    boolean isSemanticOnlyDrift(String relativePath, String semanticFileHash) {
        if (semanticFileHash == null || semanticFileHash.isEmpty()) {
            return false;
        }
        AnalyzerState.FileState previous = previousState.files.get(relativePath);
        return previous != null && previous.semanticFileHash != null
                && semanticFileHash.equals(previous.semanticFileHash);
    }

    FileMetric buildFileMetric(String relativePath, String sourceText,
                               ParserConfiguration.LanguageLevel languageLevel, CompilationUnit cu, String fileHash,
                               RiskCalculator riskCalculator, Map<String, MethodMetric> previousMethods,
                               ScanAccumulator summaryAccumulator, boolean pmdMetricAlignment) {
        return FileMetricsBuilder.build(relativePath, sourceText, languageLevel, cu, riskCalculator, previousMethods,
                summaryAccumulator, fileHash, false, pmdMetricAlignment);
    }

    Map<String, MethodMetric> previousMethodsByKey(String relativePath) {
        AnalyzerState.FileState previous = previousState.files.get(relativePath);
        if (previous == null || previous.cachedFileMetric == null) {
            return Map.of();
        }
        Map<String, MethodMetric> byKey = new HashMap<>();
        for (ClassMetric type : previous.cachedFileMetric.classes()) {
            for (MethodMetric method : type.methods()) {
                byKey.put(type.name() + "#" + method.signature(), method);
            }
        }
        return byKey;
    }
}
