package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;

/** CI exit codes: parse failure ratio and risk gate. */
final class ScanExitEvaluator {

    static final int EXIT_OK = 0;
    static final int EXIT_PARSE_FAILURE_RATIO = 2;
    static final int EXIT_RISK_GATE = 3;

    private ScanExitEvaluator() {
    }

    static int evaluate(AnalysisReport report, Double maxFailureRatio, RiskLevel failOnRiskMinLevel) {
        AnalysisReport.Summary summary = report.summary();
        if (maxFailureRatio != null && summary.filesScanned() > 0) {
            double ratio = (double) summary.filesFailed() / summary.filesScanned();
            if (ratio > maxFailureRatio) {
                System.err.println("[STANDALONE] Exit " + EXIT_PARSE_FAILURE_RATIO
                        + ": parse failure ratio " + ratio + " > " + maxFailureRatio);
                return EXIT_PARSE_FAILURE_RATIO;
            }
        }
        if (failOnRiskMinLevel != null) {
            if (meetsOrExceeds(summary.projectRiskLevel(), failOnRiskMinLevel)) {
                System.err.println("[STANDALONE] Exit " + EXIT_RISK_GATE
                        + ": project risk level " + summary.projectRiskLevel()
                        + " >= gate " + failOnRiskMinLevel);
                return EXIT_RISK_GATE;
            }
            for (FileMetric file : report.files()) {
                for (ClassMetric type : file.classes()) {
                    for (MethodMetric method : type.methods()) {
                        if (meetsOrExceeds(method.riskLevel(), failOnRiskMinLevel)) {
                            System.err.println("[STANDALONE] Exit " + EXIT_RISK_GATE
                                    + ": method " + type.name() + "." + method.signature()
                                    + " level " + method.riskLevel());
                            return EXIT_RISK_GATE;
                        }
                    }
                }
            }
        }
        return EXIT_OK;
    }

    private static boolean meetsOrExceeds(RiskLevel actual, RiskLevel gate) {
        return actual.ordinal() >= gate.ordinal();
    }
}
