package com.scanner.complexity;

import java.util.List;
import java.util.Map;

/** Root DTO serialised to JSON. */
public record AnalysisReport(
        String tool,
        String generatedAt,
        String analyzedPath,
        String parserLanguageLevel,
        RiskModel riskModel,
        Summary summary,
        List<RiskHotspot> topRiskyMethods,
        List<FileMetric> files,
        List<FileError> errors,
        ScanDiagnostics scanDiagnostics,
        CacheStatistics cacheStatistics,
        IncrementalChanges incrementalChanges,
        DuplicateStatistics duplicateStatistics,
        List<DuplicateGroup> duplicateGroups,
        /** Aşama 1: klasik PMD ruleset (yalnızca {@link PmdProjectScanner} ve benzeri). */
        PmdRulesetPhase pmdRulesetPhase) {

    public AnalysisReport {
        incrementalChanges = incrementalChanges == null ? IncrementalChanges.empty() : incrementalChanges;
        duplicateStatistics = duplicateStatistics == null
                ? new DuplicateStatistics(0, 0, 0, 0) : duplicateStatistics;
        duplicateGroups = duplicateGroups == null ? List.of() : List.copyOf(duplicateGroups);
    }

    /**
     * İki aşamalı lazy parse cache istatistikleri ({@link IncrementalAnalysisEngine}).
     * Incremental kapalıysa {@code null}.
     */
    public record CacheStatistics(
            int totalFiles,
            int filesSkippedViaFileHash,
            int filesReanalyzed,
            int filesSemanticCosmeticOnly,
            int filesSkippedViaModuleBulk,
            int modulesUnchanged,
            int totalMethods,
            int methodsSkippedViaHash,
            int methodsReanalyzed,
            long scanTimeMillis) {
    }

    /** Documents the composite score so JSON can be compared with PMD rule output. */
    public record RiskModel(
            String version,
            String formula,
            Map<String, Double> weights,
            Map<String, List<Integer>> thresholds) {
    }

    public record Summary(
            int filesScanned,
            int filesParsed,
            int filesFailed,
            int classCount,
            int methodCount,
            int totalCodeLines,
            double averageCyclomaticComplexity,
            int maxCyclomaticComplexity,
            int godMethodCount,
            /** Uygulama karmaşıklığı: sınıf skorlarının LOC ağırlıklı ortalaması. Yüksek = kötü. */
            double projectRiskScore,
            RiskLevel projectRiskLevel,
            /** Uygulama kalitesi: aynı sınıf LOC ağırlığı. Yüksek = iyi. Karmaşıklıkla birleştirilmez. */
            double projectQualityScore,
            Map<RiskLevel, Long> methodRiskDistribution,
            /** YÜKSEK+KRİTİK metodların metod-LOC içindeki payı (0–1). */
            double highPlusCriticalLocRatio,
            /** KRİTİK metod sayısı / KLOC. */
            double criticalMethodsPerKloc,
            double methodRiskScoreP95,
            double methodRiskScoreP99,
            List<ModuleRiskSummary> moduleSummaries) {

        public Summary {
            moduleSummaries = moduleSummaries == null ? List.of() : List.copyOf(moduleSummaries);
        }
    }

    public record ModuleRiskSummary(
            String moduleRoot,
            int methodCount,
            int codeLines,
            double locWeightedRiskScore,
            double locWeightedQualityScore,
            double methodRiskScoreP95,
            double criticalMethodsPerKloc) {
    }

    public record FileMetric(
            String path,
            String packageName,
            int physicalLines,
            int codeLines,
            int classCount,
            int methodCount,
            int totalCyclomaticComplexity,
            int maxCyclomaticComplexity,
            double riskScore,
            RiskLevel riskLevel,
            List<String> riskFactors,
            List<ClassMetric> classes,
            HalsteadMetrics halstead,
            int swallowedExceptionCount,
            int genericExceptionCatchCount,
            int rawTypeUsageCount,
            int stringConcatInLoopCount,
            int hardcodedLiteralCount,
            String fileHash,
            boolean fullyReusedFromCache,
            int methodsReusedFromCache,
            /** Kalite: yüksek = iyi. {@link #riskScore} karmaşıklıktır: yüksek = kötü. */
            double qualityScore) {

        public FileMetric {
            halstead = halstead == null ? HalsteadMetrics.EMPTY : halstead;
            fileHash = fileHash == null ? "" : fileHash;
        }

        /** Önceki taramadan birebir kopya (Stage-1 dosya hash eşleşmesi). */
        public FileMetric reusedCopy() {
            return new FileMetric(path, packageName, physicalLines, codeLines, classCount, methodCount,
                    totalCyclomaticComplexity, maxCyclomaticComplexity, riskScore, riskLevel, riskFactors,
                    classes, halstead, swallowedExceptionCount, genericExceptionCatchCount,
                    rawTypeUsageCount, stringConcatInLoopCount, hardcodedLiteralCount, fileHash, true, methodCount,
                    qualityScore);
        }

        /** Byte hash güncellendi; metrikler önceki taramadan (yorum/boşluk-only drift). */
        public FileMetric withRefreshedFileHash(String newFileHash) {
            return new FileMetric(path, packageName, physicalLines, codeLines, classCount, methodCount,
                    totalCyclomaticComplexity, maxCyclomaticComplexity, riskScore, riskLevel, riskFactors,
                    classes, halstead, swallowedExceptionCount, genericExceptionCatchCount,
                    rawTypeUsageCount, stringConcatInLoopCount, hardcodedLiteralCount, newFileHash, true,
                    methodCount, qualityScore);
        }
    }

    public record ClassMetric(
            String name,
            String kind,
            int startLine,
            int endLine,
            int methodCount,
            int publicMethodCount,
            int codeLines,
            int efferentCouplingProxy,
            int weightedMethodComplexity,
            int maxMethodComplexity,
            double averageMethodComplexity,
            double riskScore,
            RiskLevel riskLevel,
            List<String> riskFactors,
            List<MethodMetric> methods,
            HalsteadMetrics halstead,
            List<String> dependentTypes,
            double lcom3,
            boolean godClassCandidate,
            int swallowedExceptionCount,
            int genericExceptionCatchCount,
            int rawTypeUsageCount,
            int stringConcatInLoopCount,
            int hardcodedLiteralCount,
            double qualityScore) {

        public ClassMetric {
            halstead = halstead == null ? HalsteadMetrics.EMPTY : halstead;
            dependentTypes = dependentTypes == null ? List.of() : List.copyOf(dependentTypes);
        }
    }

    public record RiskHotspot(
            String file,
            String packageName,
            String className,
            String method,
            int startLine,
            double riskScore,
            RiskLevel riskLevel,
            int cyclomaticComplexity,
            int codeLines,
            int maxNestingDepth,
            int parameterCount,
            int cognitiveComplexity,
            int outboundDistinctCalls,
            String dominantDriver,
            List<String> riskFactors,
            double qualityScore) {
    }

    public record FileError(
            String file,
            String message,
            ScanErrorCategory category,
            /** Sınıf adı (basit); dosya düzeyi hatalarda boş. */
            String className,
            /** İmza (ör. foo(String)); dosya düzeyi hatalarda boş. */
            String methodSignature,
            /** Metot / oluşturucu başlangıç satırı; yoksa -1. */
            int startLine,
            /** Metot bitiş satırı; yoksa -1. */
            int endLine) {

        public FileError(String file, String message) {
            this(file, message, ScanErrorClassifier.classify(message), "", "", -1, -1);
        }

        public FileError(String file, String message, ScanErrorCategory category) {
            this(file, message, category, "", "", -1, -1);
        }

        public FileError(String file, String message, String className, String methodSignature, int startLine, int endLine) {
            this(file, message, ScanErrorClassifier.classify(message), className, methodSignature, startLine, endLine);
        }
    }

    public record DuplicateGroup(
            String groupId,
            String similarityType,
            int matchedTokenCount,
            int duplicatedLines,
            int similarityPercent,
            List<DuplicateMember> members) {

        public DuplicateGroup(String groupId, String similarityType, int matchedTokenCount,
                              int duplicatedLines, List<DuplicateMember> members) {
            this(groupId, similarityType, matchedTokenCount, duplicatedLines,
                    DuplicateSimilarity.percent(similarityType, duplicatedLines, members), members);
        }
    }

    public record DuplicateMember(String file, String className, String method, int startLine, int endLine) {
    }

    public record DuplicateStatistics(
            int exactGroups,
            int methodsInExactGroups,
            int nearMissGroups,
            int methodsInNearMissGroups) {
    }

    /** Klasik PMD ruleset koşusu (tarama aşaması 1). */
    public record PmdRulesetPhase(
            String rulesetPath,
            int violationCount,
            String violationsXmlPath,
            List<PmdViolationRow> violationsSample) {

        public PmdRulesetPhase {
            violationsSample = violationsSample == null ? List.of() : List.copyOf(violationsSample);
        }
    }

    public record PmdViolationRow(
            String file,
            int beginLine,
            int endLine,
            String ruleName,
            String description) {
    }
}
