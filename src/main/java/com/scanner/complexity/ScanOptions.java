package com.scanner.complexity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Monorepo / büyük codebase tarama ayarları. */
public record ScanOptions(
        int workers,
        int progressEvery,
        List<String> includeGlobs,
        List<String> excludeGlobs,
        ReportDetail reportDetail,
        boolean applyDefaultIgnoredDirectories,
        Path incrementalStateFile,
        boolean ignoreIncrementalCache,
        boolean detectDuplicates,
        int minDuplicateTokens,
        /** PMD CPD near-miss pass; exact duplicate passes unaffected unless {@code detectDuplicates} is false. */
        boolean detectNearMissDuplicates,
        /** When &gt; 0 and parsed file count &gt;= this, skip CPD unless {@link #forceNearMissDuplicates}. */
        int nearDuplicateAutoSkipMinFiles,
        boolean forceNearMissDuplicates) {

    /** 0 = CPD her zaman çalışır; yalnızca {@code --near-duplicate-auto-skip-files=N} (N&gt;0) ile opt-in atlama. */
    public static final int DEFAULT_NEAR_DUPLICATE_AUTO_SKIP_MIN_FILES = 0;

    public enum ReportDetail {
        /** Dosya → sınıf → metod tam ağaç (varsayılan). */
        FULL,
        /** Dosya özeti + hotspot; metod listesi JSON/Markdown'da yok (bellek/çıktı küçük). */
        SUMMARY
    }

    public static ScanOptions defaults() {
        int cpus = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(1, Math.min(cpus, cpus <= 4 ? cpus : cpus - 1));
        return new ScanOptions(workers, 500, List.of(), List.of(), ReportDetail.FULL, true, null, false, true, 50,
                true, DEFAULT_NEAR_DUPLICATE_AUTO_SKIP_MIN_FILES, false);
    }

    public ScanOptions {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1");
        }
        if (progressEvery < 1) {
            throw new IllegalArgumentException("progressEvery must be >= 1");
        }
        includeGlobs = List.copyOf(includeGlobs);
        excludeGlobs = List.copyOf(excludeGlobs);
    }

    static ScanOptions fromCli(List<String> includeGlobs, List<String> excludeGlobs, int workers,
                               int progressEvery, ReportDetail reportDetail,
                               boolean applyDefaultIgnoredDirectories, Path incrementalStateFile,
                               boolean ignoreIncrementalCache, boolean detectDuplicates, int minDuplicateTokens,
                               boolean detectNearMissDuplicates, int nearDuplicateAutoSkipMinFiles,
                               boolean forceNearMissDuplicates) {
        ScanOptions defaults = defaults();
        int resolvedWorkers = workers > 0 ? workers : defaults.workers();
        int resolvedProgress = progressEvery > 0 ? progressEvery : defaults.progressEvery();
        int resolvedMinTokens = minDuplicateTokens > 0 ? minDuplicateTokens : defaults.minDuplicateTokens();
        int resolvedAutoSkip = nearDuplicateAutoSkipMinFiles >= 0
                ? nearDuplicateAutoSkipMinFiles
                : defaults.nearDuplicateAutoSkipMinFiles();
        return new ScanOptions(resolvedWorkers, resolvedProgress, includeGlobs, excludeGlobs, reportDetail,
                applyDefaultIgnoredDirectories, incrementalStateFile, ignoreIncrementalCache,
                detectDuplicates, resolvedMinTokens, detectNearMissDuplicates, resolvedAutoSkip,
                forceNearMissDuplicates);
    }

    static ReportDetail parseDetail(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> ReportDetail.FULL;
            case "summary" -> ReportDetail.SUMMARY;
            default -> throw new IllegalArgumentException("--detail must be full or summary: " + raw);
        };
    }

    static List<String> splitCsv(String raw) {
        List<String> parts = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }
}
