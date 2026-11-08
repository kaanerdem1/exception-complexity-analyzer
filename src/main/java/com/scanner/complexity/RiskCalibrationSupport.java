package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.PmdViolationRow;
import com.scanner.complexity.RiskCalculator.Assessment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Synthetic scenarios + heuristics for risk score calibration audits (tests + {@link RiskCalibrationAuditMain}).
 */
public final class RiskCalibrationSupport {

    public enum IssueKind {
        /** Final score above {@link RiskProfile#capMethodScoreByLoc} tier. */
        LOC_CAP_BREACH,
        /** Short, structurally simple method still scores high. */
        SHORT_METHOD_OVER_SCORED,
        /** Large / complex method scores surprisingly low. */
        LONG_METHOD_UNDER_SCORED,
        /** Parser vs PMD-native on same source diverge beyond tolerance. */
        PMD_PARSER_SCORE_GAP,
        /** PMD ruleset enricher inflates counters without matching AST metrics. */
        PMD_ENRICHER_INFLATION,
        /** Dominant sub-score high while LOC sub-score is near zero (density mismatch). */
        DENSITY_DOMINANT_MISMATCH
    }

    public record Finding(
            IssueKind kind,
            String scenarioId,
            String profileId,
            double score,
            double referenceScore,
            int codeLines,
            String dominantDriver,
            String detail) {
    }

    public record ScenarioResult(
            String id,
            String label,
            Assessment parser,
            Assessment pmd,
            MethodScanValues scan) {
    }

    private static final double PMD_PARSER_GAP_TOLERANCE = 12.0;
    private static final int SHORT_LOC = 5;
    private static final double SHORT_SIMPLE_MAX_SCORE = 25.0;
    private static final int LONG_LOC = 60;
    private static final int LONG_COMPLEX_MIN_CC = 18;

    private RiskCalibrationSupport() {
    }

    public static List<ScenarioResult> runSyntheticScenarios(RiskCalculator parser, RiskCalculator pmd) {
        List<ScenarioResult> out = new ArrayList<>();
        for (SyntheticScenario s : SyntheticScenario.values()) {
            MethodScanValues scan = s.build();
            out.add(new ScenarioResult(s.name(), s.label(), parser.assessMethod(scan), pmd.assessMethod(scan), scan));
        }
        return out;
    }

    public static List<Finding> analyzeScenarioResults(List<ScenarioResult> results) {
        List<Finding> findings = new ArrayList<>();
        for (ScenarioResult r : results) {
            findings.addAll(analyzeOne(r.id(), r.label(), r.scan(), r.parser(), "enterprise-java"));
            findings.addAll(analyzeOne(r.id(), r.label(), r.scan(), r.pmd(), "enterprise-java-pmd"));
            double gap = Math.abs(r.parser().score() - r.pmd().score());
            if (gap > PMD_PARSER_GAP_TOLERANCE) {
                findings.add(new Finding(IssueKind.PMD_PARSER_SCORE_GAP, r.id(), "both",
                        r.parser().score(), r.pmd().score(), r.scan().codeLines(),
                        RiskBreakdownUtil.dominantDriver(r.parser().breakdown()),
                        String.format(Locale.ROOT,
                                "Parser %.1f vs PMD %.1f (Δ=%.1f > %.0f): %s",
                                r.parser().score(), r.pmd().score(), gap, PMD_PARSER_GAP_TOLERANCE, r.label())));
            }
        }
        return findings;
    }

    public static List<Finding> analyzeEnricherInflation(RiskCalculator pmdCalculator) {
        List<Finding> findings = new ArrayList<>();
        MethodScanValues base = builder().codeLines(3).cyclomatic(1).build();
        Assessment baseScore = pmdCalculator.assessMethod(base);
        for (EnricherProbe probe : EnricherProbe.values()) {
            MethodScanValues enriched = probe.apply(base);
            Assessment after = pmdCalculator.assessMethod(enriched);
            if (after.score() > baseScore.score() + 8.0) {
                findings.add(new Finding(IssueKind.PMD_ENRICHER_INFLATION, probe.name(), "enterprise-java-pmd",
                        after.score(), baseScore.score(), base.codeLines(), "—",
                        probe.detail() + String.format(Locale.ROOT, " (%.1f → %.1f)", baseScore.score(), after.score())));
            }
        }
        return findings;
    }

    public static List<Finding> analyzeSourceParity(RiskCalculator parser, RiskCalculator pmd, Path javaFile,
                                                    String relativePath, String methodName) throws IOException {
        String text = Files.readString(javaFile, StandardCharsets.UTF_8);
        MethodMetric parserMethod = extractParserMethod(text, relativePath, methodName);
        MethodMetric pmdMethod = extractPmdMethod(text, relativePath, methodName);
        if (parserMethod == null || pmdMethod == null) {
            return List.of();
        }
        double gap = Math.abs(parserMethod.riskScore() - pmdMethod.riskScore());
        if (gap <= PMD_PARSER_GAP_TOLERANCE) {
            return List.of();
        }
        return List.of(new Finding(IssueKind.PMD_PARSER_SCORE_GAP, methodName + "@" + javaFile.getFileName(),
                "both", parserMethod.riskScore(), pmdMethod.riskScore(), parserMethod.codeLines(),
                RiskBreakdownUtil.dominantDriver(parserMethod.riskBreakdown()),
                String.format(Locale.ROOT,
                        "boolOps parser=%d pmd=%d; factors parser=%s | pmd=%s",
                        parserMethod.maxBooleanOperatorsInCondition(), pmdMethod.maxBooleanOperatorsInCondition(),
                        parserMethod.riskFactors(), pmdMethod.riskFactors())));
    }

    public static String formatReport(List<ScenarioResult> scenarios, List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Risk calibration audit (generated)\n\n");
        sb.append("> Üretim risk: **enterprise-java-pmd** only. Parser sütunları referans; envanter insert PMD JSON.\n\n");
        sb.append("## Synthetic scenarios\n\n");
        sb.append("| Id | LOC | CC | Parser | PMD | Δ |\n|----|-----|----|--------|-----|---|\n");
        for (ScenarioResult r : scenarios) {
            MethodScanValues v = r.scan();
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %.1f | %.1f | %.1f |\n",
                    r.id(), v.codeLines(), v.cyclomaticComplexity(), r.parser().score(), r.pmd().score(),
                    Math.abs(r.parser().score() - r.pmd().score())));
        }
        sb.append("\n## Findings (").append(findings.size()).append(")\n\n");
        findings.stream()
                .sorted(Comparator.comparing(Finding::kind).thenComparing(Finding::scenarioId))
                .forEach(f -> sb.append(String.format(Locale.ROOT, "- **%s** `%s` [%s] score=%.1f ref=%.1f loc=%d — %s%n",
                        f.kind(), f.scenarioId(), f.profileId(), f.score(), f.referenceScore(), f.codeLines(),
                        f.detail())));
        return sb.toString();
    }

    static Builder builder() {
        return new Builder();
    }

    private static List<Finding> analyzeOne(String id, String label, MethodScanValues scan, Assessment assessment,
                                            String profileId) {
        List<Finding> out = new ArrayList<>();
        double score = assessment.score();
        int loc = scan.codeLines();
        String driver = RiskBreakdownUtil.dominantDriver(assessment.breakdown());
        double locCap = capForLoc(profileId, loc);
        if (locCap > 0 && score > locCap + 0.05) {
            out.add(new Finding(IssueKind.LOC_CAP_BREACH, id, profileId, score, locCap, loc, driver,
                    "Score exceeds LOC cap tier for " + loc + " lines"));
        }
        if (loc <= SHORT_LOC && isStructurallySimple(scan) && score > SHORT_SIMPLE_MAX_SCORE + 0.05) {
            out.add(new Finding(IssueKind.SHORT_METHOD_OVER_SCORED, id, profileId, score, SHORT_SIMPLE_MAX_SCORE,
                    loc, driver, label));
        }
        if (loc >= LONG_LOC && scan.cyclomaticComplexity() >= LONG_COMPLEX_MIN_CC && score < 45.0) {
            out.add(new Finding(IssueKind.LONG_METHOD_UNDER_SCORED, id, profileId, score, 45.0, loc, driver,
                    "Long/high-CC method scores below expected floor"));
        }
        if (loc <= SHORT_LOC && assessment.breakdown() != null
                && assessment.breakdown().locSubScore() < 10.0
                && assessment.breakdown().dominantSubScore() > 70.0) {
            out.add(new Finding(IssueKind.DENSITY_DOMINANT_MISMATCH, id, profileId, score,
                    assessment.breakdown().locSubScore(), loc, driver,
                    "Dominant dimension high while LOC sub-score negligible"));
        }
        return out;
    }

    private static double capForLoc(String profileId, int codeLines) {
        try {
            RiskProfile profile = RiskProfileLoader.load(profileId, RiskProfileLoader.defaultConfigPath());
            return profile.capMethodScoreByLoc(codeLines, 100.0) == 100.0 ? -1 : profile.capMethodScoreByLoc(codeLines, 100.0);
        } catch (IOException e) {
            return -1;
        }
    }

    private static boolean isStructurallySimple(MethodScanValues scan) {
        return scan.cyclomaticComplexity() <= 3
                && scan.maxNestingDepth() <= 2
                && scan.catchClauses() <= 1
                && scan.maxBooleanOperatorsInCondition() <= 2;
    }

    private static MethodMetric extractParserMethod(String text, String relativePath, String methodName)
            throws IOException {
        Path tmp = Files.createTempFile("cal-", ".java");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            SourceParser.Outcome outcome = SourceParser.parse(tmp, StandardCharsets.UTF_8,
                    ParserConfiguration.LanguageLevel.JAVA_17);
            if (outcome.result().getResult().isEmpty()) {
                return null;
            }
            CompilationUnit cu = outcome.result().getResult().get();
            RiskCalculator calc = RiskProfileLoader.createCalculator("enterprise-java",
                    RiskProfileLoader.defaultConfigPath());
            FileMetric file = FileMetricsBuilder.build(relativePath, text, outcome.usedLevel(), cu, calc, Map.of(),
                    null, "test", false, false);
            return findMethod(file, methodName);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static MethodMetric extractPmdMethod(String text, String relativePath, String methodName) {
        RiskCalculator calc = RiskProfileLoader.createCalculator("enterprise-java-pmd",
                RiskProfileLoader.defaultConfigPath());
        FileMetric file = PmdFileMetricsBuilder.build(relativePath, text, ParserConfiguration.LanguageLevel.JAVA_17,
                List.of(), calc, false);
        return findMethod(file, methodName);
    }

    private static MethodMetric findMethod(FileMetric file, String methodName) {
        for (AnalysisReport.ClassMetric type : file.classes()) {
            for (MethodMetric method : type.methods()) {
                if (method.name().equals(methodName)) {
                    return method;
                }
            }
        }
        return null;
    }

    private enum EnricherProbe {
        LAW_OF_DEMETER {
            @Override
            MethodScanValues apply(MethodScanValues base) {
                List<PmdViolationRow> rows = List.of(
                        new PmdViolationRow("f.java", 2, 2, "LawOfDemeter", "demo"));
                return PmdViolationMethodEnricher.applyScanValues(base, rows, 1, 5);
            }

            @Override
            String detail() {
                return "LawOfDemeter sets call-chain proxy=3";
            }
        },
        SIMPLIFY_CONDITIONAL {
            @Override
            MethodScanValues apply(MethodScanValues base) {
                List<PmdViolationRow> rows = List.of(
                        new PmdViolationRow("f.java", 2, 2, "SimplifyConditional", "demo"));
                return PmdViolationMethodEnricher.applyScanValues(base, rows, 1, 5);
            }

            @Override
            String detail() {
                return "SimplifyConditional sets boolOps proxy≥2";
            }
        },
        COUPLING {
            @Override
            MethodScanValues apply(MethodScanValues base) {
                List<PmdViolationRow> rows = List.of(
                        new PmdViolationRow("f.java", 2, 2, "CouplingBetweenObjects", "demo"));
                return PmdViolationMethodEnricher.applyScanValues(base, rows, 1, 5);
            }

            @Override
            String detail() {
                return "CouplingBetweenObjects sets outbound proxy=5";
            }
        };

        abstract MethodScanValues apply(MethodScanValues base);

        abstract String detail();
    }

    private enum SyntheticScenario {
        TINY_DELEGATE("1 LOC getter-like") {
            @Override
            MethodScanValues build() {
                return builder().codeLines(1).cyclomatic(1).build();
            }
        },
        TINY_FOUR_PARAMS("2 LOC, 4 parameters") {
            @Override
            MethodScanValues build() {
                return builder().codeLines(2).cyclomatic(1).parameterCount(4).build();
            }
        },
        TINY_IF_TWO_BOOL("3 LOC if with 2 && in condition") {
            @Override
            MethodScanValues build() {
                return builder().codeLines(3).cyclomatic(2).maxBooleanOperatorsInCondition(2).build();
            }
        },
        TINY_THREE_CATCH("5 LOC, 3 catch (mock-style)") {
            @Override
            MethodScanValues build() {
                return builder().codeLines(5).cyclomatic(4).catchClauses(3).catchExceptionOrThrowable(3).build();
            }
        },
        MEDIUM_NESTED("25 LOC, nest 4, CC 8") {
            @Override
            MethodScanValues build() {
                return builder().codeLines(25).cyclomatic(8).maxNestingDepth(4).cognitive(12).build();
            }
        },
        LONG_FLAT("70 LOC, CC 22, low nesting") {
            @Override
            MethodScanValues build() {
                return builder().codeLines(70).cyclomatic(22).logicalStatements(40).build();
            }
        },
        LONG_SHALLOW_CC("80 LOC, CC 5 only") {
            @Override
            MethodScanValues build() {
                return builder().codeLines(80).cyclomatic(5).build();
            }
        },
        HALSTEAD_SPIKE("4 LOC but huge Halstead effort (PMD proxy path)") {
            @Override
            MethodScanValues build() {
                return builder().codeLines(4).cyclomatic(2).halsteadEffort(20_000).halsteadDifficulty(30).build();
            }
        };

        private final String label;

        SyntheticScenario(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        abstract MethodScanValues build();
    }

    /** Fluent builder for calibration {@link MethodScanValues}. */
    public static final class Builder {
        private int cc = 1;
        private int loc = 1;
        private int nest;
        private int params;
        private int cognitive;
        private int logical;
        private int exits;
        private int catches;
        private int boolOps;
        private int halsteadEffort;
        private int halsteadDiff;

        public Builder cyclomatic(int v) {
            cc = v;
            return this;
        }

        public Builder codeLines(int v) {
            loc = v;
            return this;
        }

        public Builder maxNestingDepth(int v) {
            nest = v;
            return this;
        }

        public Builder parameterCount(int v) {
            params = v;
            return this;
        }

        public Builder cognitive(int v) {
            cognitive = v;
            return this;
        }

        public Builder logicalStatements(int v) {
            logical = v;
            return this;
        }

        public Builder exitPoints(int v) {
            exits = v;
            return this;
        }

        private int genericCatch;

        public Builder catchClauses(int v) {
            catches = v;
            return this;
        }

        public Builder catchExceptionOrThrowable(int v) {
            genericCatch = v;
            return this;
        }

        public Builder maxBooleanOperatorsInCondition(int v) {
            boolOps = v;
            return this;
        }

        public Builder halsteadEffort(int v) {
            halsteadEffort = v;
            return this;
        }

        public Builder halsteadDifficulty(int v) {
            halsteadDiff = v;
            return this;
        }

        public MethodScanValues build() {
            return new MethodScanValues(cc, loc, nest, params, cognitive, logical, exits, catches,
                    0, 0, 0, 0, 0, 0, 0, genericCatch, 0, 0, boolOps,
                    halsteadDiff, halsteadEffort, 0, 0, 0, 0, 0);
        }
    }
}
