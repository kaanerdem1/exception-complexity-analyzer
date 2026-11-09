package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.RiskHotspot;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/** Human-readable stderr logs for comparing with external tools (e.g. PMD). */
final class AnalysisConsoleLogger {

    private static final String TAG = "[STANDALONE]";

    private AnalysisConsoleLogger() {
    }

    static void logRunHeader(PathLabel path, boolean verbose) {
        PrintStream err = System.err;
        AnalysisReport.RiskModel model = HealthScoreEngine.get().riskModel();
        err.println(TAG + " ========================================");
        err.println(TAG + " Standalone Java complexity & quality scan");
        err.println(TAG + " Target: " + path.value());
        err.println(TAG + " Score model: " + model.version() + " (0–100; complexity high=bad, quality high=good)");
        err.println(TAG + " " + model.formula());
        err.println(TAG + " Metric ceilings: " + formatThresholds(model));
        err.println(TAG + " Verbose method listing: " + (verbose ? "ON" : "OFF (use --verbose)"));
        err.println(TAG + " JSON schema: riskBreakdown per method when score is computed");
        err.println(TAG + " ========================================");
    }

    static void logSummary(AnalysisReport report, String jsonOutput) {
        PrintStream err = System.err;
        AnalysisReport.Summary s = report.summary();
        err.println(TAG + " --- Summary ---");
        err.println(TAG + " Files: scanned=" + s.filesScanned() + " parsed=" + s.filesParsed()
                + " failed=" + s.filesFailed());
        err.println(TAG + " Methods=" + s.methodCount() + " classes=" + s.classCount()
                + " totalCodeLines=" + s.totalCodeLines());
        err.println(TAG + " CC: avg=" + s.averageCyclomaticComplexity() + " max=" + s.maxCyclomaticComplexity());
        err.println(TAG + " God methods=" + s.godMethodCount());
        err.println(TAG + " Project complexity: score=" + s.projectRiskScore() + " level=" + s.projectRiskLevel()
                + " band=" + HealthScoreEngine.complexityBand(s.projectRiskScore())
                + " | tail p95=" + s.methodRiskScoreP95() + " p99=" + s.methodRiskScoreP99());
        err.println(TAG + " Project quality: score=" + s.projectQualityScore()
                + " band=" + HealthScoreEngine.qualityBand(s.projectQualityScore()));
        err.println(TAG + " High+critical LOC share=" + s.highPlusCriticalLocRatio()
                + " critical/KLOC=" + s.criticalMethodsPerKloc());
        err.println(TAG + " Distribution: LOW=" + s.methodRiskDistribution().get(RiskLevel.LOW)
                + " MEDIUM=" + s.methodRiskDistribution().get(RiskLevel.MEDIUM)
                + " HIGH=" + s.methodRiskDistribution().get(RiskLevel.HIGH)
                + " CRITICAL=" + s.methodRiskDistribution().get(RiskLevel.CRITICAL));
        if (jsonOutput != null) {
            err.println(TAG + " JSON report: " + jsonOutput);
        }
    }

    static void logHotspots(List<RiskHotspot> hotspots, int limit) {
        PrintStream err = System.err;
        err.println(TAG + " --- Top risk hotspots (max " + limit + ") ---");
        int n = Math.min(limit, hotspots.size());
        for (int i = 0; i < n; i++) {
            RiskHotspot h = hotspots.get(i);
            err.printf(Locale.US, TAG + " #%d score=%.3f %s %s.%s line=%d%n",
                    i + 1, h.riskScore(), h.riskLevel(), h.className(), h.method(), h.startLine());
            err.printf(TAG + "     raw: CC=%d LOC=%d nest=%d params=%d%n",
                    h.cyclomaticComplexity(), h.codeLines(), h.maxNestingDepth(), h.parameterCount());
            if (!h.riskFactors().isEmpty()) {
                err.println(TAG + "     factors: " + String.join("; ", h.riskFactors()));
            }
        }
        if (hotspots.isEmpty()) {
            err.println(TAG + " (none)");
        }
    }

    static void logVerboseMethods(List<FileMetric> files) {
        PrintStream err = System.err;
        err.println(TAG + " --- All methods (verbose) ---");
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric m : type.methods()) {
                    RiskBreakdown b = m.riskBreakdown();
                    err.printf(Locale.US, TAG + " %s | %s.%s | score=%.3f %s | CC=%d cog=%d LOC=%d nest=%d exit=%d catch=%d sw=%d fout=%d lam=%d tryNest=%d locals=%d chain=%d params=%d primObs=%d boolOps=%d%n",
                            file.path(), type.name(), m.signature(), m.riskScore(), m.riskLevel(),
                            m.cyclomaticComplexity(), m.cognitiveComplexity(), m.codeLines(),
                            m.maxNestingDepth(), m.exitPoints(), m.catchClauses(), m.switchCases(),
                            m.outboundDistinctCalls(), m.lambdaCount(), m.maxTryNestingDepth(),
                            m.localVariableCount(), m.maxMethodCallChainLength(), m.parameterCount(),
                            m.primitiveObsessionIndex(), m.maxBooleanOperatorsInCondition());
                    if (m.emptyCatchBlocks() > 0 || m.catchExceptionOrThrowable() > 0
                            || m.catchWithOnlyPrintStackTrace() > 0) {
                        err.printf(Locale.US, TAG + "     catch-quality: empty=%d broad=%d printStackTraceOnly=%d%n",
                                m.emptyCatchBlocks(), m.catchExceptionOrThrowable(),
                                m.catchWithOnlyPrintStackTrace());
                    }
                    if (b != null) {
                        err.printf(Locale.US, TAG + "     sub: cc=%.3f loc=%.3f nest=%.3f par=%.3f dom=%.3f blend=%.3f bonus=%.3f final=%.3f%n",
                                b.ccSubScore(), b.locSubScore(), b.nestingSubScore(), b.paramsSubScore(),
                                b.dominantSubScore(), b.weightedBlend(), b.compoundBonus(), b.finalScore());
                    }
                    if (m.godMethod()) {
                        err.println(TAG + "     ** godMethod heuristic **");
                    }
                }
            }
        }
    }

    static void logParseErrors(List<AnalysisReport.FileError> errors) {
        if (errors.isEmpty()) {
            return;
        }
        PrintStream err = System.err;
        err.println(TAG + " --- Parse / analysis errors ---");
        int shown = 0;
        int max = 25;
        for (AnalysisReport.FileError e : errors) {
            if (shown >= max) {
                err.println(TAG + " ... " + (errors.size() - max) + " more (see report: unreadable files section)");
                break;
            }
            String where = e.file();
            if (e.className() != null && !e.className().isEmpty()) {
                where += " " + e.className();
            }
            if (e.methodSignature() != null && !e.methodSignature().isEmpty()) {
                where += "#" + e.methodSignature();
            }
            if (e.startLine() > 0) {
                where += " satır " + e.startLine();
            }
            err.println(TAG + " " + where + " [" + e.category().labelTr() + "]: " + e.message());
            shown++;
        }
    }

    static void logScanDiagnostics(ScanDiagnostics diagnostics) {
        if (diagnostics == null) {
            return;
        }
        PrintStream err = System.err;
        err.println(TAG + " --- Scan diagnostics ---");
        err.println(TAG + " Status: " + diagnostics.completionStatus()
                + " | last phase: " + diagnostics.lastPhaseEn());
        if (diagnostics.fatalMessage() != null) {
            err.println(TAG + " FATAL phase=" + diagnostics.fatalPhaseEn() + ": " + diagnostics.fatalMessage());
        }
        if (diagnostics.lastFileAttempted() != null && !diagnostics.lastFileAttempted().isBlank()) {
            err.println(TAG + " Last file: " + diagnostics.lastFileAttempted());
        }
        err.printf(Locale.US, TAG + " Parse failure ratio: %.1f%%%n", diagnostics.parseFailureRatio() * 100);
        if (!diagnostics.errorsByCategory().isEmpty()) {
            err.println(TAG + " Error categories: " + diagnostics.errorsByCategory());
        }
        for (String tip : diagnostics.recommendationsEn()) {
            err.println(TAG + " -> " + tip);
        }
        err.println(TAG + " Details: README.md (parse errors / LANGUAGE_LEVEL / encoding)");
    }

    static void logFatal(ScanPhase phase, String message, String lastFile) {
        PrintStream err = System.err;
        err.println(TAG + " FATAL phase=" + phase.labelEn() + ": " + message);
        if (lastFile != null && !lastFile.isBlank()) {
            err.println(TAG + " Last file: " + lastFile);
        }
        err.println(TAG + " Report may be incomplete; check stderr parse errors.");
    }

    private static String formatThresholds(AnalysisReport.RiskModel model) {
        StringBuilder sb = new StringBuilder();
        model.thresholds().forEach((id, bounds) -> {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(id).append(": ");
            for (int i = 0; i < bounds.size(); i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(bounds.get(i));
            }
        });
        return sb.toString();
    }

    record PathLabel(String value) {
    }
}
