package com.scanner.complexity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints calibration audit (synthetic + optional mock-modules). Tests: {@code RiskCalibrationTest}.
 *
 * <pre>
 *   java -cp java-code-analyzer.jar com.scanner.complexity.RiskCalibrationAuditMain [mock-modules-dir]
 * </pre>
 */
public final class RiskCalibrationAuditMain {

    private RiskCalibrationAuditMain() {
    }

    public static void main(String[] args) throws Exception {
        RiskCalculator parser = RiskProfileLoader.createCalculator("enterprise-java", null);
        RiskCalculator pmd = RiskProfileLoader.createCalculator("enterprise-java-pmd", null);

        List<RiskCalibrationSupport.ScenarioResult> scenarios =
                RiskCalibrationSupport.runSyntheticScenarios(parser, pmd);
        List<RiskCalibrationSupport.Finding> findings = new ArrayList<>();
        findings.addAll(RiskCalibrationSupport.analyzeScenarioResults(scenarios));
        findings.addAll(RiskCalibrationSupport.analyzeEnricherInflation(pmd));

        Path mockRoot = args.length > 0 ? Path.of(args[0]) : Path.of("mock-modules");
        addSourceParity(findings, parser, pmd, mockRoot.resolve(
                "module-auth/src/main/java/com/mock/auth/AuthFacade.java"),
                "module-auth/src/main/java/com/mock/auth/AuthFacade.java", "login");
        addSourceParity(findings, parser, pmd, mockRoot.resolve(
                "module-v3fixtures/src/main/java/com/mock/v3fixtures/V3MetricFixtures.java"),
                "module-v3fixtures/src/main/java/com/mock/v3fixtures/V3MetricFixtures.java", "demoComplexCondition");

        String report = RiskCalibrationSupport.formatReport(scenarios, findings);
        System.out.println(report);

        Path outDir = Path.of("analysis-output");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("risk-calibration-audit-latest.md");
        Files.writeString(out, report);
        System.err.println("[CAL-AUDIT] Wrote " + out.toAbsolutePath().normalize());
    }

    private static void addSourceParity(List<RiskCalibrationSupport.Finding> findings, RiskCalculator parser,
                                        RiskCalculator pmd, Path file, String relativePath, String method)
            throws Exception {
        if (!Files.isRegularFile(file)) {
            return;
        }
        findings.addAll(RiskCalibrationSupport.analyzeSourceParity(parser, pmd, file, relativePath, method));
    }
}
