package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.PmdRulesetPhase;
import com.scanner.complexity.AnalysisReport.PmdViolationRow;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.document.FileCollector;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import net.sourceforge.pmd.reporting.RuleViolation;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Klasik PMD ruleset taraması (aşama 1). */
final class ClassicPmdRulesetScanner {

    private static final int MAX_VIOLATIONS_IN_JSON = 200;

    private ClassicPmdRulesetScanner() {
    }

    record ScanOutcome(PmdRulesetPhase phase, List<PmdViolationRow> allViolations) {
    }

    static PmdRulesetPhase scan(List<Path> sources, Path base, Path rulesetFile, Path violationsXml,
                                Charset charset, ParserConfiguration.LanguageLevel languageLevel,
                                int threads) throws IOException {
        return scanWithViolations(sources, base, rulesetFile, violationsXml, charset, languageLevel, threads).phase();
    }

    static ScanOutcome scanWithViolations(List<Path> sources, Path base, Path rulesetFile, Path violationsXml,
                                          Charset charset, ParserConfiguration.LanguageLevel languageLevel,
                                          int threads) throws IOException {
        String rulesetPath = rulesetFile.toAbsolutePath().normalize().toString();
        if (sources.isEmpty()) {
            if (violationsXml != null) {
                Path parent = violationsXml.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(violationsXml, minimalEmptyReport());
            }
            PmdRulesetPhase empty = new PmdRulesetPhase(rulesetPath, 0, xmlRef(violationsXml), List.of());
            return new ScanOutcome(empty, List.of());
        }

        Path parent = violationsXml != null ? violationsXml.getParent() : null;
        if (parent != null) {
            Files.createDirectories(parent);
        }

        PMDConfiguration config = new PMDConfiguration();
        config.setSourceEncoding(charset);
        config.addRuleSet(rulesetPath);
        config.setReportFormat("xml");
        if (violationsXml != null) {
            config.setReportFile(violationsXml);
        }
        config.setThreads(Math.max(1, threads));
        config.setForceLanguageVersion(PmdLanguageLevels.toPmd(languageLevel));
        if (base != null) {
            config.addRelativizeRoot(base);
        }

        try (PmdAnalysis analysis = PmdAnalysis.create(config)) {
            RuleSet ruleSet = RuleSetLoader.fromPmdConfig(config).loadFromResource(rulesetPath);
            analysis.addRuleSet(ruleSet);
            FileCollector collector = analysis.files();
            collector.setCharset(StandardCharsets.UTF_8);
            collector.setRecursive(false);
            for (Path source : sources) {
                Path abs = source.toAbsolutePath().normalize();
                collector.addSourceFile(FileId.fromPath(abs), SourceEncoding.read(abs, charset));
            }
            if (violationsXml != null) {
                analysis.addRenderer(config.createRenderer(true));
            }
            var report = analysis.performAnalysisAndCollectReport();
            List<PmdViolationRow> all = toAllRows(report.getViolations());
            List<PmdViolationRow> sample = sampleRows(all);
            PmdRulesetPhase phase = new PmdRulesetPhase(rulesetPath, report.getViolations().size(),
                    xmlRef(violationsXml), sample);
            return new ScanOutcome(phase, all);
        }
    }

    private static List<PmdViolationRow> sampleRows(List<PmdViolationRow> all) {
        return all.size() <= MAX_VIOLATIONS_IN_JSON ? all : all.subList(0, MAX_VIOLATIONS_IN_JSON);
    }

    private static List<PmdViolationRow> toAllRows(List<RuleViolation> violations) {
        List<RuleViolation> sorted = new ArrayList<>(violations);
        sorted.sort(Comparator
                .comparing((RuleViolation v) -> v.getFileId() == null ? "" : v.getFileId().getOriginalPath(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(RuleViolation::getBeginLine));
        List<PmdViolationRow> rows = new ArrayList<>(sorted.size());
        for (RuleViolation v : sorted) {
            String ruleName = v.getRule() != null ? v.getRule().getName() : "";
            String file = v.getFileId() != null ? v.getFileId().getOriginalPath() : "";
            rows.add(new PmdViolationRow(file, v.getBeginLine(), v.getEndLine(), ruleName,
                    v.getDescription()));
        }
        return rows;
    }

    private static String xmlRef(Path violationsXml) {
        return violationsXml == null ? "" : violationsXml.toString();
    }

    private static String minimalEmptyReport() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0"
                     version="7.0.0" timestamp="">
                </pmd>
                """;
    }
}
