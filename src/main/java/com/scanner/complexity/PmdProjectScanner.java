package com.scanner.complexity;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.scanner.complexity.AnalysisReport.FileError;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.PmdRulesetPhase;
import com.scanner.complexity.ScanOptions.ReportDetail;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * İki aşamalı tarama:
 * <ol>
 *   <li>Klasik PMD ruleset ({@link ClassicPmdRulesetScanner})</li>
 *   <li>Parser: metrik, risk, duplicate ({@link FileMetricsBuilder}) — PMD XML kullanılmaz; dosyalar yeniden parse edilir</li>
 * </ol>
 * Duplicate ve risk skoru yalnızca aşama 1 tamamlandıktan sonra çalışır; girdi ihlal XML’i değil AST’tir.
 */
public final class PmdProjectScanner {

    private static final String TOOL_NAME =
            "PMD ruleset scan + parser enrichment (risk, duplicates, PMD JavaMetrics align)";

    private final Charset charset;
    private final ParserConfiguration.LanguageLevel languageLevel;
    private final ScanOptions scanOptions;
    private final RiskCalculator riskCalculator;
    private final int topN;
    private final Path rulesetFile;
    private final Path violationsXmlPath;
    private final boolean runRulesetPhase;

    public PmdProjectScanner(Charset charset, ParserConfiguration.LanguageLevel languageLevel,
                             ScanOptions scanOptions, int topN) {
        this(charset, languageLevel, scanOptions, topN, new RiskCalculator(), null, null, true);
    }

    public PmdProjectScanner(Charset charset, ParserConfiguration.LanguageLevel languageLevel,
                             ScanOptions scanOptions, int topN, RiskCalculator riskCalculator) {
        this(charset, languageLevel, scanOptions, topN, riskCalculator, null, null, true);
    }

    public PmdProjectScanner(Charset charset, ParserConfiguration.LanguageLevel languageLevel,
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

        List<FileError> errors = Collections.synchronizedList(new ArrayList<>());
        JavaSourceDiscovery.Result discovery = JavaSourceDiscovery.collect(absoluteRoot, base, errors, scanOptions);
        List<Path> sources = discovery.paths();
        if (discovery.skippedDirectorySubtrees() > 0) {
            System.err.println("[PMD-SCAN] Skipped " + discovery.skippedDirectorySubtrees()
                    + " directory subtree(s) (build outputs / exclude globs)");
        }
        System.err.println("[PMD-SCAN] " + sources.size() + " .java file(s) queued"
                + (discovery.skippedByFilter() > 0
                ? " (" + discovery.skippedByFilter() + " skipped by include/exclude)" : "")
                + ", language " + languageLevel.name() + ", workers=" + scanOptions.workers());

        PmdRulesetPhase pmdPhase = null;
        if (runRulesetPhase && rulesetFile != null && Files.isRegularFile(rulesetFile)) {
            System.err.println("[PMD-SCAN] Phase 1/2: PMD ruleset → "
                    + rulesetFile.toAbsolutePath().normalize());
            long p1 = System.nanoTime();
            pmdPhase = ClassicPmdRulesetScanner.scan(sources, base, rulesetFile, violationsXmlPath, charset,
                    languageLevel, scanOptions.workers());
            System.err.println("[PMD-SCAN] Phase 1 done: " + pmdPhase.violationCount() + " violation(s), "
                    + (System.nanoTime() - p1) / 1_000_000L + " ms");
        } else if (runRulesetPhase) {
            System.err.println("[PMD-SCAN] Phase 1 skipped: ruleset not found "
                    + (rulesetFile != null ? rulesetFile : ""));
        }

        System.err.println("[PMD-SCAN] Phase 2/2: parser metrics, risk, duplicates (PMD metric alignment="
                + scanOptions.pmdMetricAlignment() + ")");

        ModuleRootIndex moduleRoots = ModuleRootIndex.forScanRoot(absoluteRoot);
        ScanAccumulator summaryAccumulator = scanOptions.reportDetail() == ReportDetail.SUMMARY
                ? new ScanAccumulator(topN, moduleRoots) : null;
        List<FileMetric> duplicateInputs = scanOptions.detectDuplicates()
                ? Collections.synchronizedList(new ArrayList<>()) : null;

        List<FileMetric> files = scanFiles(sources, base, errors, summaryAccumulator, duplicateInputs);
        files.sort(Comparator.comparing(FileMetric::path));
        errors.sort(Comparator.comparing(FileError::file));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.err.println("[PMD-SCAN] Total wall time: " + elapsedMs + " ms");

        List<FileMetric> filesForDuplicates = duplicateInputs != null ? duplicateInputs : files;
        List<AnalysisReport.DuplicateGroup> duplicateGroups =
                DuplicateScanSupport.detectGroups(absoluteRoot, scanOptions, filesForDuplicates);
        AnalysisReport.DuplicateStatistics duplicateStatistics = DuplicateScanSupport.summarise(duplicateGroups);
        DuplicateScanSupport.logSummary(duplicateStatistics);

        AnalysisReport.Summary summary = summaryAccumulator != null
                ? summaryAccumulator.toSummary(sources.size(), files.size(), errors.size())
                : ReportAggregates.buildSummary(sources.size(), files, errors, absoluteRoot);
        List<AnalysisReport.RiskHotspot> hotspots = summaryAccumulator != null
                ? summaryAccumulator.topHotspots()
                : ReportAggregates.buildHotspots(files, topN);

        return new AnalysisReport(
                TOOL_NAME,
                Instant.now().toString(),
                absoluteRoot.toString(),
                languageLevel.name(),
                riskCalculator.buildRiskModel(),
                summary,
                hotspots,
                files,
                errors,
                null,
                null,
                null,
                duplicateStatistics,
                duplicateGroups,
                pmdPhase);
    }

    private List<FileMetric> scanFiles(List<Path> sources, Path base, List<FileError> errors,
                                       ScanAccumulator summaryAccumulator, List<FileMetric> duplicateInputs)
            throws IOException {
        if (sources.isEmpty()) {
            return List.of();
        }
        List<FileMetric> files = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(scanOptions.workers());
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Path source : sources) {
                tasks.add(() -> {
                    parseOne(source, base, files, errors, summaryAccumulator, duplicateInputs);
                    logProgress(processed.incrementAndGet(), sources.size());
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel scan interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Parallel scan failed", e.getCause());
        } finally {
            pool.shutdown();
        }
        return new ArrayList<>(files);
    }

    private void parseOne(Path source, Path base, List<FileMetric> files, List<FileError> errors,
                          ScanAccumulator summaryAccumulator, List<FileMetric> duplicateInputs) {
        String relative = JavaSourceDiscovery.relativePath(base, source);
        try {
            String text = SourceEncoding.read(source, charset);
            SourceParser.Outcome outcome = SourceParser.parse(source, charset, languageLevel);
            ParseResult<CompilationUnit> result = outcome.result();
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();
                String fileHash = HashService.hashFile(source);
                FileMetric full = FileMetricsBuilder.build(relative, text, outcome.usedLevel(), cu, riskCalculator,
                        Map.of(), null, fileHash, false, scanOptions.pmdMetricAlignment());
                if (scanOptions.detectDuplicates()) {
                    full = ParserDuplicateHashEnrichment.enrich(source, relative, text, full, charset,
                            languageLevel).get(0);
                    if (duplicateInputs != null) {
                        duplicateInputs.add(full);
                    }
                }
                if (summaryAccumulator != null) {
                    summaryAccumulator.ingestFile(relative, full.packageName(), full.classes(), full.codeLines());
                    files.add(summaryStub(full));
                } else {
                    files.add(full);
                }
            } else {
                errors.add(new FileError(relative, "Parse error: " + describeParseFailure(result)));
            }
        } catch (Exception e) {
            errors.add(new FileError(relative, "Parse/metrics: " + e.getMessage()));
        }
    }

    private static String describeParseFailure(ParseResult<CompilationUnit> result) {
        if (result.getProblems().isEmpty()) {
            return "unknown parser failure";
        }
        return result.getProblems().stream().limit(3)
                .map(p -> p.getVerboseMessage())
                .collect(Collectors.joining(" | "));
    }

    private static FileMetric summaryStub(FileMetric metric) {
        return FileMetricsBuilder.buildSummaryStub(metric.path(), metric.packageName(), metric.physicalLines(),
                metric.codeLines(), metric.classCount(), metric.methodCount(), metric.totalCyclomaticComplexity(),
                metric.maxCyclomaticComplexity(),
                new RiskCalculator.Assessment(metric.riskScore(), metric.riskLevel(), metric.riskFactors(), null),
                metric.fileHash());
    }

    private void logProgress(int processed, int total) {
        if (processed % scanOptions.progressEvery() == 0 || processed == total) {
            System.err.println("[PMD-SCAN] Phase 2: " + processed + "/" + total + " files");
        }
    }
}
