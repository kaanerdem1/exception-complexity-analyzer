package com.scanner.complexity;

import com.scanner.exception.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Klonlanmış repoyu PMD ile tarar. Skor bellekte kalır ve tabloya yazılır.
 * Rapor json ve ihlal xml üretilmez. Önbellek analyzer-state dosyasındadır.
 * Skor: health-score-catalog.yaml (karmaşıklık ve kalite ayrı).
 */
public final class ComplexityRunner {

    public enum Engine { PMD }

    /** Bir yolun sonucu */
    public record Result(String engine, AnalysisReport report, Path json) {
        public AnalysisReport.Summary summary() {
            return report.summary();
        }
    }

    private static final String RULESET_RESOURCE = "/config/pmd-ruleset-complexity.xml";

    private final Config.Complexity cfg;
    private boolean databaseFileHash;
    private Map<String, String> dbFileHashes = Map.of();
    private List<String> lastUnchanged = List.of();

    public ComplexityRunner(Config.Complexity cfg) {
        this.cfg = cfg;
    }

    /** Önceki taramadaki dosya hash'leri. Verilince analyzer-state yazılmaz. */
    public void databaseFileHashes(Map<String, String> hashes) {
        this.databaseFileHash = true;
        this.dbFileHashes = hashes == null ? Map.of() : Map.copyOf(hashes);
    }

    public void clearDatabaseFileHashes() {
        this.databaseFileHash = false;
        this.dbFileHashes = Map.of();
    }

    public List<String> unchangedFiles() {
        return lastUnchanged;
    }

    public java.util.List<Engine> engines() {
        return java.util.List.of(Engine.PMD);
    }

    /** Skor {@code report} içindedir. json yazılmaz; ihlal xml de üretilmez. */
    public Result analyze(Path sourceRoot, Path baseName, Engine engine) throws IOException {
        return pmd(sourceRoot, baseName);
    }

    private Result pmd(Path sourceRoot, Path baseName) throws IOException {
        lastUnchanged = List.of();
        RiskCalculator risk = RiskProfileLoader.createCalculator(profile("enterprise-java-pmd"));
        Path ruleset = Files.createTempFile("pmd-ruleset-", ".xml");
        Path violations = Files.createTempFile("pmd-violations-", ".xml");
        try {
            try (InputStream in = ComplexityRunner.class.getResourceAsStream(RULESET_RESOURCE)) {
                if (in == null) throw new IOException("PMD ruleset bulunamadı: " + RULESET_RESOURCE);
                Files.copy(in, ruleset, StandardCopyOption.REPLACE_EXISTING);
            }
            // PMD yolu summary modunda metotları özet hesaplanmadan önce atıyor (metot sayısı 0 çıkıyor); hep full.
            // İhlal xml yalnız PMD'nin kendi çıktısı için geçicidir; skor bellekte kalır, dosya koşu bitince silinir.
            PmdNativeProjectScanner scanner = new PmdNativeProjectScanner(cfg.charset, cfg.languageLevel,
                    options(true, baseName, Engine.PMD), cfg.top,
                    risk, ruleset, violations, true);
            if (databaseFileHash) {
                scanner.useDatabaseFileHashes(dbFileHashes);
            }
            AnalysisReport report = scanner.scan(sourceRoot);
            lastUnchanged = scanner.unchangedFiles();
            return new Result("pmd", report, null);
        } finally {
            Files.deleteIfExists(violations);
            Files.deleteIfExists(ruleset);
        }
    }

    private ScanOptions options(boolean fullDetail, Path baseName, Engine engine) {
        ScanOptions d = ScanOptions.defaults();
        Path stateFile = null;
        if (cfg.incrementalCache && !databaseFileHash) {
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

    /** scanReports/yyyyMMdd_HHmm değil; analyzer-cache/<proje>/<repo>-pmd-analyzer-state.json */
    private Path persistentStateFile(Path baseName, String suffix) {
        String repo = baseName.getFileName() != null ? baseName.getFileName().toString() : "repo";
        Path project = baseName.getParent() != null && baseName.getParent().getFileName() != null
                ? baseName.getParent().getFileName()
                : Path.of("_");
        return cfg.cacheDirectory.resolve(project.toString()).resolve(repo + suffix);
    }
}
