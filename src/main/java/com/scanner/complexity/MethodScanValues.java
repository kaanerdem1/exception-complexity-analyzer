package com.scanner.complexity;

import java.util.List;

/** Raw method counters passed into {@link RiskCalculator#assessMethod(MethodScanValues)}. */
public record MethodScanValues(
        int cyclomaticComplexity,
        int codeLines,
        int maxNestingDepth,
        int parameterCount,
        int cognitiveComplexity,
        int logicalStatements,
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
        int halsteadDifficultyRounded,
        int halsteadEffortRounded,
        int rawTypeUsage,
        int stringConcatInLoop,
        int hardcodedLiteralCount,
        int swallowedExceptionSmells,
        int genericExceptionSmells) {

    static MethodScanValues zeros(int cyclomatic, int codeLines, int nesting, int parameters) {
        return new MethodScanValues(cyclomatic, codeLines, nesting, parameters,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0);
    }

    static MethodScanValues fromMetric(MethodMetric method) {
        MethodScanValues base = new MethodScanValues(
                method.cyclomaticComplexity(), method.codeLines(), method.maxNestingDepth(), method.parameterCount(),
                method.cognitiveComplexity(), method.logicalStatements(), method.exitPoints(), method.catchClauses(),
                method.switchCases(), method.outboundDistinctCalls(), method.lambdaCount(), method.maxTryNestingDepth(),
                method.localVariableCount(), method.maxMethodCallChainLength(), method.emptyCatchBlocks(),
                method.catchExceptionOrThrowable(), method.catchWithOnlyPrintStackTrace(),
                method.primitiveObsessionIndex(), method.maxBooleanOperatorsInCondition(),
                0, 0, 0, 0, 0, 0, 0);
        return withLegacyExtensions(base, method.halstead(), method.exceptionSmells(), method.codeSmells());
    }

    static MethodScanValues withLegacyExtensions(MethodScanValues base, HalsteadMetrics halstead,
                                                 List<ExceptionSmell> exceptionSmells, List<CodeSmell> codeSmells) {
        int diff = halstead == null ? 0 : (int) Math.round(halstead.difficulty());
        int effort = halstead == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.round(halstead.effort()));
        return new MethodScanValues(
                base.cyclomaticComplexity(), base.codeLines(), base.maxNestingDepth(), base.parameterCount(),
                base.cognitiveComplexity(), base.logicalStatements(), base.exitPoints(), base.catchClauses(),
                base.switchCases(), base.outboundDistinctCalls(), base.lambdaCount(), base.maxTryNestingDepth(),
                base.localVariableCount(), base.maxMethodCallChainLength(), base.emptyCatchBlocks(),
                base.catchExceptionOrThrowable(), base.catchWithOnlyPrintStackTrace(), base.primitiveObsessionIndex(),
                base.maxBooleanOperatorsInCondition(),
                diff, effort,
                SmellCounts.rawType(codeSmells), SmellCounts.concatInLoop(codeSmells),
                SmellCounts.hardcoded(codeSmells), SmellCounts.swallowed(exceptionSmells),
                SmellCounts.genericCatch(exceptionSmells));
    }
}
