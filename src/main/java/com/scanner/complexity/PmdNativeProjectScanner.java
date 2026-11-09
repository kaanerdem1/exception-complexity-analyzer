package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.FileError;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.PmdRulesetPhase;
import com.scanner.complexity.AnalysisReport.PmdViolationRow;
import com.scanner.complexity.ClassicPmdRulesetScanner.ScanOutcome;
import com.scanner.complexity.ScanOptions.ReportDetail;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Yalnızca PMD: ruleset + PMD AST/JavaMetrics. Skor {@link HealthScoreEngine}.
 */
public final class PmdNativeProjectScanner {

    private static final String TOOL_NAME =
            "PMD-only scan (ruleset + JavaMetrics + PMD AST stats)";

    private static final String CACHE_PIPELINE = "pmd-native";
    /** Tek dosya PMD metriği bu süreyi aşarsa atlanır. NPath sayımı skor tavanında durur; bu süre başka bir takılma içindir. */
    static final long METRICS_FILE_TIMEOUT_SEC = 90;

    private final Charset charset;
    private final ParserConfiguration.LanguageLevel languageLevel;
    private final ScanOptions scanOptions;
    private final RiskCalculator riskCalculator;
    private final int topN;
    private final Path rulesetFile;
    private final Path violationsXmlPath;
    private final boolean runRulesetPhase;

    public PmdNativeProjectScanner(Charset charset, ParserConfiguration.LanguageLevel languageLevel,
                                   ScanOptions scanOptions, int topN, RiskCalculator riskCalculator,
                                   Path rulesetFile, Path violationsXmlPath, boolean runRulesetPhase) {
        this.charset = charset;
        this.languageLevel = languageLevel;
        this.scanOptions = scanOptions;
        this.riskCalculator = riskCalculator;
        this.topN = topN;
        this.rulesetFile = rulesetFile;
        this.violationsXmlPath = violationsXmlPath;
        this.runRulesetPhase = runRulesetPhase;
    }

    public AnalysisReport scan(Path root) throws IOException {
        long started = System.nanoTime();
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path base = Files.isDirectory(absoluteRoot) ? absoluteRoot : absoluteRoot.getParent();
        System.err.println("[PMD-NATIVE] Kök: " + absoluteRoot);

        List<FileError> errors = Collections.synchronizedList(new ArrayList<>());
        List<Path> sources;
        try {
            PmdScanDiagnosticsLog.stepStart("1/7", "Kaynak keşfi (.java dosyaları)");
            JavaSourceDiscovery.Result discovery = JavaSourceDiscovery.collect(absoluteRoot, base, errors, scanOptions);
            sources = discovery.paths();
            logDiscovery(discovery, sources.size());
            PmdScanDiagnosticsLog.stepDone("1/7", sources.size() + " dosya");
        } catch (Exception e) {
            PmdScanDiagnosticsLog.stepFail("1/7", "kaynak keşfi", e);
            return null; // unreachable
        }

        Path stateFile = scanOptions.incrementalStateFile();
        boolean incremental = stateFile != null;
        String cacheIdentity = IncrementalStateSupport.cacheIdentity(CACHE_PIPELINE, riskCalculator);
        AnalyzerState loadedState = new AnalyzerState();
        boolean hadPreviousScan = false;
        IncrementalAnalysisEngine incrementalEngine = null;
        AnalyzerState stateForDiff = new AnalyzerState();
        PmdCacheRunCounters cacheCounters = incremental ? new PmdCacheRunCounters() : null;

        try {
            PmdScanDiagnosticsLog.stepStart("2/7", "Artımlı önbellek state okuma");
            if (incremental && scanOptions.workers() > 1) {
                PmdScanDiagnosticsLog.stepWarn("2/7",
                        "Paralel worker + state desteklenmiyor; workers=1 kullanılacak.");
            }
            loadedState = IncrementalStateSupport.load(stateFile, scanOptions.ignoreIncrementalCache());
            hadPreviousScan = incremental && !scanOptions.ignoreIncrementalCache()
                    && IncrementalStateSupport.cacheIdentityMatches(loadedState, cacheIdentity)
                    && loadedState.files != null && !loadedState.files.isEmpty();
            PmdScanDiagnosticsLog.logIncrementalBootstrap(stateFile, scanOptions.ignoreIncrementalCache(),
                    incremental, loadedState, cacheIdentity, hadPreviousScan);
            if (incremental && !scanOptions.ignoreIncrementalCache() && !hadPreviousScan
                    && loadedState.files != null && !loadedState.files.isEmpty()) {
                PmdScanDiagnosticsLog.stepWarn("2/7",
                        "Eski state var ama risk profili veya şema değişti — tam yeniden skor.");
            }
            incrementalEngine = incremental
                    ? new IncrementalAnalysisEngine(hadPreviousScan ? loadedState : new AnalyzerState())
                    : null;
            stateForDiff = hadPreviousScan ? loadedState : new AnalyzerState();
            PmdScanDiagnosticsLog.stepDone("2/7", hadPreviousScan ? "önbellek aktif" : "soğuk / yeni state");
        } catch (Exception e) {
            PmdScanDiagnosticsLog.stepFail("2/7", "state okuma", e);
        }

        List<Path> rulesetSources;
        Map<String, List<PmdViolationRow>> violationsByAbsolutePath = Map.of();
        PmdRulesetPhase pmdPhase = null;
        try {
            PmdScanDiagnosticsLog.stepStart("3/7", "PMD ruleset taraması (ihlaller)");
            rulesetSources = filterRulesetSources(sources, base, incrementalEngine);
            if (runRulesetPhase && rulesetFile != null && Files.isRegularFile(rulesetFile)) {
                System.err.println("[PMD-NATIVE]   Ruleset: " + rulesetFile.toAbsolutePath().normalize());
                if (incrementalEngine != null) {
                    System.err.printf(Locale.ROOT,
                            "[PMD-NATIVE]   Değişen dosyalar: %d / %d (byte hash farklı olanlar).%n",
                            rulesetSources.size(), sources.size());
                }
                long p1 = System.nanoTime();
                ScanOutcome outcome = ClassicPmdRulesetScanner.scanWithViolations(
                        rulesetSources, base, rulesetFile, violationsXmlPath, charset, languageLevel,
                        incrementalEngine != null ? 1 : scanOptions.workers());
                pmdPhase = outcome.phase();
                violationsByAbsolutePath = indexViolations(outcome.allViolations());
                PmdScanDiagnosticsLog.stepDone("3/7", pmdPhase.violationCount() + " ihlal, "
                        + (System.nanoTime() - p1) / 1_000_000L + " ms");
            } else if (runRulesetPhase) {
                PmdScanDiagnosticsLog.stepWarn("3/7", "Ruleset dosyası yok — adım atlandı.");
                rulesetSources = sources;
            } else {
                System.err.println("[PMD-NATIVE]   Ruleset fazı kapalı (runRulesetPhase=false).");
                rulesetSources = sources;
                PmdScanDiagnosticsLog.stepDone("3/7", "atlandı");
            }
        } catch (Exception e) {
            PmdScanDiagnosticsLog.stepFail("3/7", "PMD ruleset", e);
        }

        Map<String, List<PmdViolationRow>> violationsByPath = violationsByAbsolutePath;
        boolean attachHashes = scanOptions.detectDuplicates();
        List<FileMetric> files;
        try {
            PmdScanDiagnosticsLog.stepStart("4/7",
                    "PMD AST metrikleri + risk skoru (JavaParser yok; önbellek burada devreye girer)");
            files = metricsPass(sources, base, errors, violationsByPath, attachHashes, incrementalEngine,
                    cacheCounters);
            files.sort(Comparator.comparing(FileMetric::path));
            errors.sort(Comparator.comparing(FileError::file));
            PmdScanDiagnosticsLog.stepDone("4/7", files.size() + " dosya metrik, " + errors.size() + " hata kaydı");
        } catch (Exception e) {
            PmdScanDiagnosticsLog.stepFail("4/7", "PMD metrik + risk", e);
            return null;
        }

        IncrementalChanges incrementalChanges = IncrementalChanges.empty();
        try {
            PmdScanDiagnosticsLog.stepStart("5/7", "Önceki taramayla diff + state yazma");
            incrementalChanges = incremental
                    ? IncrementalChangeDetector.detect(stateForDiff, files, hadPreviousScan, Map.of())
                    : IncrementalChanges.empty();
            if (incremental && incrementalChanges.comparedToPreviousScan()) {
                logIncrementalChanges(incrementalChanges);
            }
            if (incremental) {
                IncrementalStateSupport.persist(stateFile, cacheIdentity, files, Map.of());
                System.err.println("[PMD-NATIVE]   State güncellendi: "
                        + stateFile.toAbsolutePath().normalize());
            }
            PmdScanDiagnosticsLog.stepDone("5/7", incremental ? "state kaydedildi" : "state yok");
        } catch (Exception e) {
            PmdScanDiagnosticsLog.stepFail("5/7", "state yazma / diff", e);
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        AnalysisReport.CacheStatistics cacheStatistics = null;
        if (incremental && cacheCounters != null) {
            cacheStatistics = new AnalysisReport.CacheStatistics(sources.size(), cacheCounters.filesSkippedViaFileHash,
                    cacheCounters.filesReanalyzed, 0, 0, 0,
                    cacheCounters.totalMethods, cacheCounters.methodsSkippedViaHash,
                    cacheCounters.methodsReanalyzed, elapsedMs);
            PmdScanDiagnosticsLog.logCacheOzet(cacheStatistics);
        }
        PmdScanDiagnosticsLog.logAfterRiskState(stateFile, hadPreviousScan, cacheStatistics, incrementalChanges);

        if (!attachHashes) {
            PmdScanDiagnosticsLog.stepStart("6/7", "Benzerlik hash");
            System.err.println("[PMD-NATIVE]   DUPLICATE=0 — adım atlandı.");
            PmdScanDiagnosticsLog.stepDone("6/7", "kapalı");
        } else {
            PmdScanDiagnosticsLog.stepStart("6/7", "Benzerlik hash (PMD gövde hash)");
            PmdScanDiagnosticsLog.stepDone("6/7", "PMD");
        }

        List<AnalysisReport.DuplicateGroup> duplicateGroups = List.of();
        AnalysisReport.DuplicateStatistics duplicateStatistics = null;
        try {
            PmdScanDiagnosticsLog.stepStart("7/7", "Benzer kod grupları (hash + CPD)");
            duplicateGroups = DuplicateScanSupport.detectGroups(absoluteRoot, scanOptions, files);
            duplicateStatistics = DuplicateScanSupport.summarise(duplicateGroups);
            DuplicateScanSupport.logSummary(duplicateStatistics);
            PmdScanDiagnosticsLog.stepDone("7/7",
                    duplicateStatistics != null
                            ? duplicateStatistics.exactGroups() + " exact grup, "
                            + duplicateStatistics.nearMissGroups() + " near-miss"
                            : "");
        } catch (Exception e) {
            PmdScanDiagnosticsLog.stepFail("7/7", "duplicate / CPD", e);
        }

        System.err.println("[PMD-NATIVE] Toplam süre: " + elapsedMs + " ms");

        AnalysisReport.Summary summary = ReportAggregates.buildSummary(sources.size(), files, errors, absoluteRoot);
        List<AnalysisReport.RiskHotspot> hotspots = ReportAggregates.buildHotspots(files, topN);

        return new AnalysisReport(
                TOOL_NAME,
                Instant.now().toString(),
                absoluteRoot.toString(),
                languageLevel.name(),
                HealthScoreEngine.get().riskModel(),
                summary,
                hotspots,
                files,
                errors,
                null,
                cacheStatistics,
                incrementalChanges,
                duplicateStatistics,
                duplicateGroups,
                pmdPhase);
    }

    private static List<Path> filterRulesetSources(List<Path> sources, Path base,
                                                   IncrementalAnalysisEngine incrementalEngine) throws IOException {
        if (incrementalEngine == null) {
            return sources;
        }
        List<Path> changed = new ArrayList<>();
        for (Path source : sources) {
            String relative = JavaSourceDiscovery.relativePath(base, source);
            String fileHash = HashService.hashFile(source);
            if (!incrementalEngine.checkFileCache(source, relative, fileHash).canSkipParsing()) {
                changed.add(source);
            }
        }
        return changed;
    }

    private static void logIncrementalChanges(IncrementalChanges changes) {
        System.err.printf(Locale.ROOT,
                "[PMD-NATIVE]   Önceki taramaya göre: dosya +%d -%d ~%d | metod +%d -%d ~%d%n",
                changes.filesAdded(), changes.filesRemoved(), changes.filesModified(),
                changes.methodsAdded(), changes.methodsRemoved(), changes.methodsModified());
    }

    private void logDiscovery(JavaSourceDiscovery.Result discovery, int count) {
        if (discovery.skippedDirectorySubtrees() > 0) {
            System.err.println("[PMD-NATIVE] Skipped " + discovery.skippedDirectorySubtrees()
                    + " directory subtree(s)");
        }
        System.err.println("[PMD-NATIVE] " + count + " .java file(s), language " + languageLevel.name()
                + ", workers=" + scanOptions.workers());
    }

    private static Map<String, List<PmdViolationRow>> indexViolations(List<PmdViolationRow> all) {
        Map<String, List<PmdViolationRow>> map = new HashMap<>();
        for (PmdViolationRow row : all) {
            String key = Path.of(row.file()).toAbsolutePath().normalize().toString();
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    private List<FileMetric> metricsPass(List<Path> sources, Path base, List<FileError> errors,
                                         Map<String, List<PmdViolationRow>> violationsByPath,
                                         boolean attachDuplicateHashes, IncrementalAnalysisEngine incrementalEngine,
                                         PmdCacheRunCounters cacheCounters) throws IOException {
        if (sources.isEmpty()) {
            return List.of();
        }
        List<FileMetric> files = Collections.synchronizedList(new ArrayList<>());
        List<FileError> errorSink = Collections.synchronizedList(errors);
        AtomicInteger processed = new AtomicInteger();
        int workers = incrementalEngine != null ? 1 : Math.max(1, scanOptions.workers());
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "pmd-metrics");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Path source : sources) {
                Path src = source;
                futures.add(pool.submit(() -> {
                    if (Thread.interrupted()) {
                        Thread.interrupted();
                    }
                    try {
                        metricsOne(src, base, files, errorSink, violationsByPath, attachDuplicateHashes,
                                incrementalEngine, cacheCounters);
                    } catch (Throwable t) {
                        String relative = JavaSourceDiscovery.relativePath(base, src);
                        String message = fileSkippedMessage(relative, metricsFailureDetail(t));
                        System.err.println("[PMD-NATIVE]   ADIM 4/7 " + message);
                        errorSink.add(new FileError(relative, message));
                    }
                    int n = processed.incrementAndGet();
                    int every = Math.max(1, scanOptions.progressEvery());
                    if (n % every == 0 || n == sources.size()) {
                        System.err.println("[PMD-NATIVE] PMD metrics: " + n + "/" + sources.size() + " files");
                    }
                    return null;
                }));
            }
            int stuckWorkers = 0;
            List<String> stuckFiles = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                Future<Void> future = futures.get(i);
                Path src = sources.get(i);
                String relative = JavaSourceDiscovery.relativePath(base, src);
                if (stuckWorkers >= workers) {
                    future.cancel(false);
                    String message = metricsSkippedBecauseStuckMessage(relative, stuckWorkers, stuckFiles);
                    System.err.println("[PMD-NATIVE]   ADIM 4/7 " + message);
                    errorSink.add(new FileError(relative, message));
                    continue;
                }
                try {
                    future.get(METRICS_FILE_TIMEOUT_SEC, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    stuckWorkers++;
                    stuckFiles.add(relative);
                    String message = metricsTimeoutMessage(relative, METRICS_FILE_TIMEOUT_SEC, stuckWorkers, workers);
                    System.err.println("[PMD-NATIVE]   ADIM 4/7 " + message);
                    errorSink.add(new FileError(relative, message));
                    future.cancel(false);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String message = fileSkippedMessage(relative, metricsFailureDetail(cause));
                    System.err.println("[PMD-NATIVE]   ADIM 4/7 " + message);
                    errorSink.add(new FileError(relative, message));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("PMD metrics interrupted", e);
        } finally {
            pool.shutdownNow();
        }
        if (scanOptions.reportDetail() == ReportDetail.SUMMARY) {
            return files.stream().map(PmdNativeProjectScanner::summaryStub).collect(Collectors.toList());
        }
        return new ArrayList<>(files);
    }

    private void metricsOne(Path source, Path base, List<FileMetric> files, List<FileError> errors,
                            Map<String, List<PmdViolationRow>> violationsByPath, boolean attachDuplicateHashes,
                            IncrementalAnalysisEngine incrementalEngine, PmdCacheRunCounters cacheCounters) {
        String relative = JavaSourceDiscovery.relativePath(base, source);
        try {
            String fileHash = HashService.hashFile(source);
            if (incrementalEngine != null) {
                IncrementalAnalysisEngine.CacheCheck check =
                        incrementalEngine.checkFileCache(source, relative, fileHash);
                if (check.canSkipParsing()) {
                    FileMetric reused = check.reusableMetric().reusedCopy();
                    files.add(reused);
                    if (cacheCounters != null) {
                        cacheCounters.filesSkippedViaFileHash++;
                        cacheCounters.totalMethods += reused.methodCount();
                        cacheCounters.methodsSkippedViaHash += reused.methodsReusedFromCache() > 0
                                ? reused.methodsReusedFromCache()
                                : reused.methodCount();
                    }
                    return;
                }
            }
            String text = SourceEncoding.read(source, charset);
            String abs = source.toAbsolutePath().normalize().toString();
            List<PmdViolationRow> fileViolations = violationsByPath.getOrDefault(abs, List.of());
            Map<String, MethodMetric> previousMethods = incrementalEngine != null
                    ? incrementalEngine.previousMethodsByKey(relative)
                    : Map.of();
            List<FileError> methodErrors = new ArrayList<>();
            FileMetric metric = PmdFileMetricsBuilder.build(relative, text, languageLevel, fileViolations,
                    riskCalculator, attachDuplicateHashes, previousMethods, fileHash, methodErrors);
            errors.addAll(methodErrors);
            files.add(metric);
            if (cacheCounters != null) {
                cacheCounters.filesReanalyzed++;
                cacheCounters.totalMethods += metric.methodCount();
                cacheCounters.methodsSkippedViaHash += metric.methodsReusedFromCache();
                cacheCounters.methodsReanalyzed += metric.methodCount() - metric.methodsReusedFromCache();
            }
        } catch (Exception e) {
            String message = fileSkippedMessage(relative, describe(e));
            System.err.println("[PMD-NATIVE]   ADIM 4/7 " + message);
            errors.add(new FileError(relative, message));
        } catch (VirtualMachineError e) {
            String message = fileSkippedMessage(relative, metricsFailureDetail(e));
            System.err.println("[PMD-NATIVE]   ADIM 4/7 " + message);
            errors.add(new FileError(relative, message));
        }
    }

    static String fileSkippedMessage(String file, String detail) {
        return file + " atlandı, tarama diğer dosyalarla sürer. Neden: " + detail;
    }

    static String metricsTimeoutMessage(String file, long seconds, int stuckWorkers, int workers) {
        return "Metrik süre aşımı (" + seconds + " sn): " + file
                + " ölçümü bitmedi, dosya atlandı ve raporda başarısız sayılır. Tarama diğer dosyalarla sürer. "
                + "Takılan işçi " + stuckWorkers + "/" + workers
                + ". NPath sayımı skor tavanında durur; bu süre başka bir metrik veya ayrıştırma takılırsa dolar.";
    }

    static String metricsSkippedBecauseStuckMessage(String file, int stuckWorkers, List<String> stuckFiles) {
        return "Atlandı: " + file + " ölçülmedi. " + stuckWorkers
                + " işçi metrikte takılı (süre " + METRICS_FILE_TIMEOUT_SEC + " sn). Takılan dosyalar: "
                + String.join(", ", stuckFiles)
                + ". Biten dosyaların raporu yazılır; bu dosya başarısız kaydıdır.";
    }

    private static String metricsFailureDetail(Throwable t) {
        if (t instanceof StackOverflowError) {
            return "Yığın taştı (stack overflow): ifade veya AST iç içe geçmesi çok derin. "
                    + "Bu dosya atlandı, diğer dosyalar taranmaya devam eder.";
        }
        if (t instanceof OutOfMemoryError) {
            return "Bellek yetmedi (out of memory): bu dosya ölçülürken heap doldu. "
                    + "Dosya atlandı. complexity.workers düşürülebilir veya heap büyütülebilir.";
        }
        return describe(t);
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            return t.getClass().getSimpleName() + ": " + msg;
        }
        return t.getClass().getSimpleName();
    }

    private static final class PmdCacheRunCounters {
        int filesSkippedViaFileHash;
        int filesReanalyzed;
        int totalMethods;
        int methodsSkippedViaHash;
        int methodsReanalyzed;
    }

    private static FileMetric summaryStub(FileMetric full) {
        return new FileMetric(full.path(), full.packageName(), full.physicalLines(), full.codeLines(),
                full.classCount(), full.methodCount(), full.totalCyclomaticComplexity(),
                full.maxCyclomaticComplexity(), full.riskScore(), full.riskLevel(), full.riskFactors(), List.of(),
                full.halstead(), full.swallowedExceptionCount(), full.genericExceptionCatchCount(),
                full.rawTypeUsageCount(), full.stringConcatInLoopCount(), full.hardcodedLiteralCount(),
                full.fileHash(), full.fullyReusedFromCache(), full.methodsReusedFromCache(),
                full.qualityScore());
    }
}
