package com.scanner.complexity;

import com.scanner.exception.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Klonlanmış repoyu PMD ile tarar, JSON ve Markdown yazar.
 * Skor: health-score-catalog.yaml (karmaşıklık ve kalite ayrı).
 */
public final class ComplexityRunner {

    public enum Engine { PMD }

    /** Bir yolun sonucu */
    public record Result(String engine, AnalysisReport report, Path json, Path markdown) {
        public AnalysisReport.Summary summary() {
            return report.summary();
        }
    }

    private static final String RULESET_RESOURCE = "/config/pmd-ruleset-complexity.xml";

    private final Config.Complexity cfg;

    public ComplexityRunner(Config.Complexity cfg) {
        this.cfg = cfg;
    }

    public java.util.List<Engine> engines() {
        return java.util.List.of(Engine.PMD);
    }

    /** Raporlar baseName + "-pmd.json/.md" ve "-pmd-violations.xml". */
    public Result analyze(Path sourceRoot, Path baseName, Engine engine) throws IOException {
        return pmd(sourceRoot, baseName);
    }

    private Result pmd(Path sourceRoot, Path baseName) throws IOException {
        RiskCalculator risk = RiskProfileLoader.createCalculator(profile("enterprise-java-pmd"), null);
        Path ruleset = Files.createTempFile("pmd-ruleset-", ".xml");
        try {
            try (InputStream in = ComplexityRunner.class.getResourceAsStream(RULESET_RESOURCE)) {
                if (in == null) throw new IOException("PMD ruleset bulunamadı: " + RULESET_RESOURCE);
                Files.copy(in, ruleset, StandardCopyOption.REPLACE_EXISTING);
            }
            Path violations = sibling(baseName, "-pmd-violations.xml");
            Files.createDirectories(violations.toAbsolutePath().getParent());
            // PMD yolu summary modunda metotları özet hesaplanmadan önce atıyor (metot sayısı 0 çıkıyor); hep full
            AnalysisReport report = new PmdNativeProjectScanner(cfg.charset, cfg.languageLevel,
                    options(true, baseName, Engine.PMD), cfg.top,
                    risk, ruleset, violations, true).scan(sourceRoot);
            return write("pmd", report, sibling(baseName, "-pmd.json"), sibling(baseName, "-pmd.md"));
        } finally {
            Files.deleteIfExists(ruleset);
        }
    }

    private Result write(String engine, AnalysisReport report, Path json, Path md) throws IOException {
        Files.createDirectories(json.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
            AnalysisReportJsonWriter.write(report, w, true, cfg.methodRiskMinScore);
        }
        if (cfg.markdown) {
            try (Writer w = Files.newBufferedWriter(md, StandardCharsets.UTF_8)) {
                StandaloneReportMarkdown.write(report, w);
            }
        }
        return new Result(engine, report, json, cfg.markdown ? md : null);
    }

    private ScanOptions options(boolean fullDetail, Path baseName, Engine engine) {
        ScanOptions d = ScanOptions.defaults();
        Path stateFile = null;
        if (cfg.incrementalCache) {
            String suffix = "-pmd-analyzer-state.json";
            stateFile = persistentStateFile(baseName, suffix);
            try {
                Path parent = stateFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException e) {
                System.err.println("[COMPLEXITY] State klasörü oluşturulamadı: " + e.getMessage());
            }
            System.err.println("[COMPLEXITY] Artımlı önbellek (" + engine.name().toLowerCase(java.util.Locale.ROOT)
                    + "): " + stateFile.toAbsolutePath().normalize()
                    + (cfg.freshCache ? " (--fresh)" : ""));
        }
        return new ScanOptions(cfg.workers > 0 ? cfg.workers : d.workers(), d.progressEvery(), cfg.include, cfg.exclude,
                fullDetail ? ScanOptions.ReportDetail.FULL : ScanOptions.ReportDetail.SUMMARY,
                true, stateFile, cfg.freshCache, cfg.duplicates, d.minDuplicateTokens(),
                cfg.duplicates && cfg.nearDuplicates,
                d.nearDuplicateAutoSkipMinFiles(), false);
    }

    /** complexity.risk.profile boşsa yolun kendi varsayılan profili */
    private String profile(String fallback) {
        return cfg.riskProfile.isEmpty() ? fallback : cfg.riskProfile;
    }

    private static Path sibling(Path baseName, String suffix) {
        return baseName.resolveSibling(baseName.getFileName() + suffix);
    }

    /** scanReports/yyyyMMdd_HHmm değil; analyzer-cache/<proje>/<repo>-pmd-analyzer-state.json */
    private Path persistentStateFile(Path baseName, String suffix) {
        String repo = baseName.getFileName() != null ? baseName.getFileName().toString() : "repo";
        Path project = baseName.getParent() != null && baseName.getParent().getFileName() != null
                ? baseName.getParent().getFileName()
                : Path.of("_");
        return cfg.cacheDirectory.resolve(project.toString()).resolve(repo + suffix);
    }
}
