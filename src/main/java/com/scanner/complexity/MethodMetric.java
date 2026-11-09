package com.scanner.complexity;

import java.util.List;

/**
 * Metrics of a single method or constructor.
 *
 * @param physicalLines     total line span of the declaration (method length)
 * @param codeLines         net lines of code (blank and comment-only lines excluded)
 * @param logicalStatements number of statements (blocks excluded)
 * @param outboundDistinctCalls distinct outbound call keys (FOUT / ATFD proxy)
 */
public record MethodMetric(
        String name,
        String kind,
        String signature,
        /** Return type label for methods; empty for constructors and similar. */
        String returnType,
        int startLine,
        int endLine,
        int cyclomaticComplexity,
        int physicalLines,
        int codeLines,
        int logicalStatements,
        int cognitiveComplexity,
        int exitPoints,
        int catchClauses,
        int switchCases,
        int outboundDistinctCalls,
        int lambdaCount,
        int maxTryNestingDepth,
        int localVariableCount,
        int maxMethodCallChainLength,
        int emptyCatchBlocks,
        int catchExceptionOrThrowable,
        int catchWithOnlyPrintStackTrace,
        int primitiveObsessionIndex,
        int maxBooleanOperatorsInCondition,
        int maxNestingDepth,
        int parameterCount,
        boolean godMethod,
        double riskScore,
        RiskLevel riskLevel,
        List<String> riskFactors,
        RiskBreakdown riskBreakdown,
        HalsteadMetrics halstead,
        List<ExceptionSmell> exceptionSmells,
        List<CodeSmell> codeSmells,
        String methodHash,
        boolean analysisReused,
        List<String> accessedFieldNames,
        String bodyHash,
        String structuralHash,
        /** PMD {@link net.sourceforge.pmd.lang.java.metrics.JavaMetrics#NPATH} (capped). */
        int npathComplexity,
        /** PMD {@link net.sourceforge.pmd.lang.java.metrics.JavaMetrics#NCSS}. */
        int ncssCount,
        /**
         * true: ölçüm JVM/PMD limiti yüzünden tamamlanamadı; CC/LOC vb. nominal,
         * risk skoru el ile yükseltildi (policy: maks. inceleme bayrağı).
         */
        boolean metricsImputed,
        /** Kalite skoru: yüksek = iyi. Karmaşıklık {@link #riskScore} alanındadır (yüksek = kötü). */
        double qualityScore,
        /** PDF kapsam filtresi: trivial get/set/is hem paydan hem paydadan çıkar. */
        boolean excludedFromHealthScore) {

    public MethodMetric {
        halstead = halstead == null ? HalsteadMetrics.EMPTY : halstead;
        exceptionSmells = exceptionSmells == null ? List.of() : List.copyOf(exceptionSmells);
        codeSmells = codeSmells == null ? List.of() : List.copyOf(codeSmells);
        methodHash = methodHash == null ? "" : methodHash;
        accessedFieldNames = accessedFieldNames == null ? List.of() : List.copyOf(accessedFieldNames);
        bodyHash = bodyHash == null ? "" : bodyHash;
        structuralHash = structuralHash == null ? "" : structuralHash;
        returnType = returnType == null ? "" : returnType;
    }

    MethodMetric withImputed(boolean imputed) {
        return new MethodMetric(name, kind, signature, returnType, startLine, endLine, cyclomaticComplexity, physicalLines,
                codeLines, logicalStatements, cognitiveComplexity, exitPoints, catchClauses, switchCases,
                outboundDistinctCalls, lambdaCount, maxTryNestingDepth, localVariableCount, maxMethodCallChainLength,
                emptyCatchBlocks, catchExceptionOrThrowable, catchWithOnlyPrintStackTrace, primitiveObsessionIndex,
                maxBooleanOperatorsInCondition, maxNestingDepth, parameterCount, godMethod, riskScore, riskLevel,
                riskFactors, riskBreakdown, halstead, exceptionSmells, codeSmells, methodHash, analysisReused,
                accessedFieldNames, bodyHash, structuralHash, npathComplexity, ncssCount, imputed,
                qualityScore, excludedFromHealthScore);
    }

    /** EXACT_STRUCTURE duplicate gruplama: dönüş tipi + imza (parametre tipleri). */
    String structureDuplicateSignature() {
        if (returnType.isEmpty()) {
            return signature;
        }
        return returnType + " " + signature;
    }

    /** Koku listeleri boş bırakılır; skor {@link HealthScoreEngine} kurallarındadır. */
    static MethodMetric withoutLegacyExtensions(
            String name, String kind, String signature, String returnType, int startLine, int endLine,
            int cyclomaticComplexity, int physicalLines, int codeLines, int logicalStatements,
            int cognitiveComplexity, int exitPoints, int catchClauses, int switchCases,
            int outboundDistinctCalls, int lambdaCount, int maxTryNestingDepth, int localVariableCount,
            int maxMethodCallChainLength, int emptyCatchBlocks, int catchExceptionOrThrowable,
            int catchWithOnlyPrintStackTrace, int primitiveObsessionIndex, int maxBooleanOperatorsInCondition,
            int maxNestingDepth, int parameterCount, boolean godMethod, double riskScore, RiskLevel riskLevel,
            List<String> riskFactors, RiskBreakdown riskBreakdown, String bodyHash, String structuralHash) {
        return withoutLegacyExtensions(name, kind, signature, returnType, startLine, endLine, cyclomaticComplexity,
                physicalLines, codeLines, logicalStatements, cognitiveComplexity, exitPoints, catchClauses, switchCases,
                outboundDistinctCalls, lambdaCount, maxTryNestingDepth, localVariableCount, maxMethodCallChainLength,
                emptyCatchBlocks, catchExceptionOrThrowable, catchWithOnlyPrintStackTrace, primitiveObsessionIndex,
                maxBooleanOperatorsInCondition, maxNestingDepth, parameterCount, godMethod, riskScore, riskLevel,
                riskFactors, riskBreakdown, bodyHash, structuralHash, 0, 0);
    }

    static MethodMetric withoutLegacyExtensions(
            String name, String kind, String signature, String returnType, int startLine, int endLine,
            int cyclomaticComplexity, int physicalLines, int codeLines, int logicalStatements,
            int cognitiveComplexity, int exitPoints, int catchClauses, int switchCases,
            int outboundDistinctCalls, int lambdaCount, int maxTryNestingDepth, int localVariableCount,
            int maxMethodCallChainLength, int emptyCatchBlocks, int catchExceptionOrThrowable,
            int catchWithOnlyPrintStackTrace, int primitiveObsessionIndex, int maxBooleanOperatorsInCondition,
            int maxNestingDepth, int parameterCount, boolean godMethod, double riskScore, RiskLevel riskLevel,
            List<String> riskFactors, RiskBreakdown riskBreakdown, String bodyHash, String structuralHash,
            int npathComplexity, int ncssCount) {
        return new MethodMetric(name, kind, signature, returnType, startLine, endLine, cyclomaticComplexity, physicalLines,
                codeLines, logicalStatements, cognitiveComplexity, exitPoints, catchClauses, switchCases,
                outboundDistinctCalls, lambdaCount, maxTryNestingDepth, localVariableCount, maxMethodCallChainLength,
                emptyCatchBlocks, catchExceptionOrThrowable, catchWithOnlyPrintStackTrace, primitiveObsessionIndex,
                maxBooleanOperatorsInCondition, maxNestingDepth, parameterCount, godMethod, riskScore, riskLevel,
                riskFactors, riskBreakdown, HalsteadMetrics.EMPTY, List.of(), List.of(), "", false, List.of(),
                bodyHash == null ? "" : bodyHash, structuralHash == null ? "" : structuralHash, npathComplexity,
                ncssCount, false, 0, false);
    }
}
