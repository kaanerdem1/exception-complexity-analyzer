package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.PmdViolationRow;

import java.util.List;

/** PMD ihlal satırlarını metod {@link MethodScanValues} sayaçlarına yansıtır. */
final class PmdViolationMethodEnricher {

    private PmdViolationMethodEnricher() {
    }

    static MethodScanValues applyScanValues(MethodScanValues scan, List<PmdViolationRow> fileViolations,
                                            int startLine, int endLine) {
        if (fileViolations == null || fileViolations.isEmpty()) {
            return scan;
        }
        int emptyCatch = scan.emptyCatchBlocks();
        int genericCatch = scan.catchExceptionOrThrowable();
        int printStack = scan.catchWithOnlyPrintStackTrace();
        int hardcoded = scan.hardcodedLiteralCount();
        int rawType = scan.rawTypeUsage();
        int stringLoop = scan.stringConcatInLoop();
        int swallowed = scan.swallowedExceptionSmells();
        int genericSmell = scan.genericExceptionSmells();
        int callChain = scan.maxMethodCallChainLength();
        int outbound = scan.outboundDistinctCalls();
        int nesting = scan.maxNestingDepth();
        int boolOps = scan.maxBooleanOperatorsInCondition();
        int primitiveObs = scan.primitiveObsessionIndex();
        int switchCases = scan.switchCases();

        for (PmdViolationRow row : fileViolations) {
            if (row.beginLine() < startLine || row.beginLine() > endLine) {
                continue;
            }
            String rule = row.ruleName() == null ? "" : row.ruleName();
            switch (rule) {
                case "EmptyCatchBlock" -> emptyCatch++;
                case "AvoidCatchingGenericException", "AvoidCatchingThrowable" -> genericCatch++;
                case "AvoidPrintStackTrace" -> printStack++;
                case "AvoidDuplicateLiterals" -> hardcoded++;
                case "LooseCoupling" -> rawType++;
                case "InefficientStringBuffering", "ConsecutiveAppendsShouldReuse" -> stringLoop++;
                case "AvoidLosingExceptionInformation", "ExceptionAsFlowControl" -> swallowed++;
                case "AvoidThrowingRawExceptionTypes" -> genericSmell++;
                case "LawOfDemeter" -> callChain = Math.max(callChain, 3);
                case "CouplingBetweenObjects", "ExcessiveImports", "LoosePackageCoupling" ->
                        outbound = Math.max(outbound, 5);
                case "AvoidDeeplyNestedIfStmts" -> nesting = Math.max(nesting, 4);
                case "SimplifyConditional", "CollapsibleIfStatements", "ConfusingTernary" ->
                        boolOps = Math.max(boolOps, 2);
                case "AvoidLiteralsInIfCondition", "AvoidUsingHardCodedIP" -> hardcoded++;
                case "SwitchDensity", "ExhaustiveSwitchHasDefault" -> switchCases = Math.max(switchCases, 4);
                case "PrimitiveWrapperInstantiation" -> primitiveObs++;
                case "IdenticalCatchBranches", "AvoidReassigningCatchVariables" -> swallowed++;
                case "AvoidRethrowingException" -> genericSmell++;
                case "ExcessiveParameterList" -> { /* param sayaci AST'den */ }
                default -> { }
            }
        }

        return new MethodScanValues(
                scan.cyclomaticComplexity(), scan.codeLines(), nesting, scan.parameterCount(),
                scan.cognitiveComplexity(), scan.logicalStatements(), scan.exitPoints(), scan.catchClauses(),
                switchCases, outbound, scan.lambdaCount(), scan.maxTryNestingDepth(),
                scan.localVariableCount(), callChain, emptyCatch, genericCatch, printStack,
                primitiveObs, boolOps,
                scan.halsteadDifficultyRounded(), scan.halsteadEffortRounded(), rawType, stringLoop, hardcoded,
                swallowed, genericSmell);
    }

    static MethodScanValues applyViolations(MethodMetric base, List<PmdViolationRow> fileViolations) {
        MethodScanValues scan = MethodScanValues.fromMetric(base);
        return applyScanValues(scan, fileViolations, base.startLine(), base.endLine());
    }
}
