package com.scanner.complexity;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.DuplicateGroup;
import com.scanner.complexity.AnalysisReport.DuplicateStatistics;
import com.scanner.complexity.AnalysisReport.FileError;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.RiskHotspot;
import com.scanner.complexity.AnalysisReport.Summary;
import com.scanner.complexity.ScanOptions.ReportDetail;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Scans a directory tree, parses .java files and assembles the {@link AnalysisReport}. */
public final class ProjectAnalyzer {

    private static final String TOOL_NAME = "Standalone Java Code Analyzer";
    /** Deep expression trees (generated SQL/strings) need more stack than the default ~1 MB. */
    private static final long WORKER_STACK_BYTES = 64L << 20;
    private static final AtomicInteger WORKER_ID = new AtomicInteger();

    private final RiskCalculator riskCalculator;
    private final ParserConfiguration parserConfiguration;
    private final int topN;
    private final ParserConfiguration.LanguageLevel languageLevel;
    private final ScanOptions scanOptions;
    public ProjectAnalyzer(Charset sourceCharset, int topN, ParserConfiguration.LanguageLevel languageLevel) {
        this(sourceCharset, topN, languageLevel, ScanOptions.defaults(), new RiskCalculator());
    }

    public ProjectAnalyzer(Charset sourceCharset, int topN, ParserConfiguration.LanguageLevel languageLevel,
                           ScanOptions scanOptions) {
        this(sourceCharset, topN, languageLevel, scanOptions, new RiskCalculator());
    }

    public ProjectAnalyzer(Charset sourceCharset, int topN, ParserConfiguration.LanguageLevel languageLevel,
                           ScanOptions scanOptions, RiskCalculator riskCalculator) {
        this.riskCalculator = riskCalculator;
        this.languageLevel = languageLevel;
        this.scanOptions = scanOptions;
        this.parserConfiguration = new ParserConfiguration()
                .setLanguageLevel(languageLevel)
                .setCharacterEncoding(sourceCharset)
                .setStoreTokens(true)
                .setAttributeComments(false);
        this.topN = topN;
    }

    public AnalysisReport analyze(Path root) throws IOException {
        return analyze(root, new ScanRunContext());
    }

    public AnalysisReport analyze(Path root, ScanRunContext runContext) throws IOException {
        long started = System.nanoTime();
        ScanRunContext run = runContext != null ? runContext : new ScanRunContext();
        run.phase(ScanPhase.DISCOVERY);
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path base = Files.isDirectory(absoluteRoot) ? absoluteRoot : absoluteRoot.getParent();

        List<FileError> errors = Collections.synchronizedList(new ArrayList<>());
        JavaSourceDiscovery.Result discovered = JavaSourceDiscovery.collect(absoluteRoot, base, errors, scanOptions);
        FileDiscovery discovery = new FileDiscovery(discovered.paths(), discovered.skippedByFilter());
        if (discovered.skippedDirectorySubtrees() > 0) {
            System.err.println("[STANDALONE] Skipped " + discovered.skippedDirectorySubtrees()
                    + " directory subtree(s) (build outputs / exclude globs)");
        }
        List<Path> sources = discovery.paths();

        System.err.println("[STANDALONE] " + sources.size() + " .java file(s) queued"
                + (discovery.skippedByFilter() > 0
                ? " (" + discovery.skippedByFilter() + " skipped by include/exclude)" : "")
                + ", parsing as " + languageLevel.name()
                + ", workers=" + scanOptions.workers() + ", detail=" + scanOptions.reportDetail() + "...");

        ModuleRootIndex moduleRoots = ModuleRootIndex.forScanRoot(absoluteRoot);
        ScanAccumulator summaryAccumulator =
                scanOptions.reportDetail() == ReportDetail.SUMMARY
                        ? new ScanAccumulator(topN, moduleRoots) : null;
        run.phase(ScanPhase.PARSING);
        Path stateFile = scanOptions.incrementalStateFile();
        boolean incremental = stateFile != null;
        if (incremental && scanOptions.workers() > 1) {
            System.err.println("[STANDALONE] Incremental cache: forcing workers=1 (parallel + state unsupported).");
        }
        String cacheIdentity = IncrementalStateSupport.cacheIdentity("parser", riskCalculator);
        AnalyzerState loadedState = IncrementalStateSupport.load(stateFile, scanOptions.ignoreIncrementalCache());
        boolean hadPreviousScan = incremental && !scanOptions.ignoreIncrementalCache()
                && IncrementalStateSupport.cacheIdentityMatches(loadedState, cacheIdentity)
                && loadedState.files != null && !loadedState.files.isEmpty();
        if (incremental && !scanOptions.ignoreIncrementalCache() && !hadPreviousScan
                && loadedState.files != null && !loadedState.files.isEmpty()) {
            System.err.println("[STANDALONE] Cache outdated (risk profile or analyzer schema changed); full re-score.");
        }
        AnalyzerState previousState = hadPreviousScan ? loadedState : new AnalyzerState();
        IncrementalAnalysisEngine incrementalEngine = incremental ? new IncrementalAnalysisEngine(previousState) : null;
        AnalyzerState stateForDiff = hadPreviousScan ? loadedState : new AnalyzerState();
        CacheRunCounters cacheCounters = incremental ? new CacheRunCounters() : null;
        IncrementalScanContext scanContext = null;
        if (incremental && incrementalEngine != null) {
            scanContext = IncrementalModulePlanner.prepare(sources, base, moduleRoots, previousState);
            if (scanContext.modulesUnchanged() > 0) {
                System.err.printf(Locale.ROOT,
                        "[STANDALONE] Incremental: %d/%d module(s) unchanged (bulk skip parse).%n",
                        scanContext.modulesUnchanged(), scanContext.modulesScanned());
            }
        }
        Map<String, String> semanticHashesByPath = incremental ? new HashMap<>() : Map.of();

        List<FileMetric> files = parseAll(sources, base, errors, summaryAccumulator, run, incrementalEngine,
                cacheCounters, scanContext, semanticHashesByPath);
        Collections.sort(files, (a, b) -> a.path().compareTo(b.path()));
        errors.sort(Comparator.comparing(FileError::file));
        run.phase(ScanPhase.REPORT_ASSEMBLY);

        IncrementalChanges incrementalChanges = incremental
                ? IncrementalChangeDetector.detect(stateForDiff, files, hadPreviousScan, semanticHashesByPath)
                : IncrementalChanges.empty();
        if (incremental && incrementalChanges.comparedToPreviousScan()) {
            logIncrementalChanges(incrementalChanges);
        }

        if (incremental) {
            persistAnalyzerState(stateFile, cacheIdentity, files, semanticHashesByPath,
                    scanContext != null ? scanContext.moduleFingerprints() : Map.of());
        }

        int filesParsed = files.size();
        int filesFailed = errors.size();
        Summary summary = summaryAccumulator != null
                ? summaryAccumulator.toSummary(sources.size(), filesParsed, filesFailed)
                : buildSummary(sources.size(), files, errors, absoluteRoot);
        List<RiskHotspot> hotspots = summaryAccumulator != null
                ? summaryAccumulator.topHotspots()
                : buildHotspots(files);
        List<FileMetric> reportFiles = files;
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.err.println("[STANDALONE] Parse wall time: " + elapsedMs + " ms");

        AnalysisReport.CacheStatistics cacheStatistics = null;
        if (incremental && cacheCounters != null) {
            if (scanContext != null) {
                cacheCounters.modulesUnchanged = scanContext.modulesUnchanged();
            }
            cacheStatistics = new AnalysisReport.CacheStatistics(sources.size(), cacheCounters.filesSkippedViaFileHash,
                    cacheCounters.filesReanalyzed, cacheCounters.filesSemanticCosmeticOnly,
                    cacheCounters.filesSkippedViaModuleBulk, cacheCounters.modulesUnchanged,
                    cacheCounters.totalMethods, cacheCounters.methodsSkippedViaHash,
                    cacheCounters.methodsReanalyzed, elapsedMs);
            logCacheSummary(cacheStatistics);
        }

        List<DuplicateGroup> duplicateGroups = DuplicateScanSupport.detectGroups(absoluteRoot, scanOptions, reportFiles);
        DuplicateStatistics duplicateStatistics = DuplicateScanSupport.summarise(duplicateGroups);
        DuplicateScanSupport.logSummary(duplicateStatistics);

        AnalysisReport report = new AnalysisReport(TOOL_NAME, Instant.now().toString(), absoluteRoot.toString(),
                languageLevel.name(), riskCalculator.buildRiskModel(), summary, hotspots, reportFiles, errors, null,
                cacheStatistics, incrementalChanges, duplicateStatistics, duplicateGroups, null);
        ScanDiagnostics diagnostics = ScanDiagnostics.from(report, run, null);
        run.phase(ScanPhase.COMPLETE);
        return new AnalysisReport(TOOL_NAME, report.generatedAt(), report.analyzedPath(),
                report.parserLanguageLevel(), report.riskModel(), report.summary(), report.topRiskyMethods(),
                report.files(), report.errors(), diagnostics, cacheStatistics, incrementalChanges,
                duplicateStatistics, duplicateGroups, null);
    }

    private List<FileMetric> parseAll(List<Path> sources, Path base, List<FileError> errors,
                                      ScanAccumulator summaryAccumulator, ScanRunContext run,
                                      IncrementalAnalysisEngine incrementalEngine, CacheRunCounters cacheCounters,
                                      IncrementalScanContext scanContext, Map<String, String> semanticHashesByPath) {
        if (sources.isEmpty()) {
            return new ArrayList<>();
        }
        int workers = incrementalEngine != null ? 1 : scanOptions.workers();
        if (sources.size() == 1 || workers == 1) {
            return parseSequential(sources, base, errors, summaryAccumulator, run, incrementalEngine, cacheCounters,
                    scanContext, semanticHashesByPath);
        }
        return parseParallel(sources, base, errors, summaryAccumulator, run, incrementalEngine, cacheCounters,
                scanContext, semanticHashesByPath);
    }

    private List<FileMetric> parseSequential(List<Path> sources, Path base, List<FileError> errors,
                                             ScanAccumulator summaryAccumulator, ScanRunContext run,
                                             IncrementalAnalysisEngine incrementalEngine, CacheRunCounters cacheCounters,
                                             IncrementalScanContext scanContext, Map<String, String> semanticHashesByPath) {
        List<FileMetric> files = new ArrayList<>();
        int processed = 0;
        for (Path source : sources) {
            parseOne(source, base, files, errors, summaryAccumulator, run, incrementalEngine, cacheCounters,
                    scanContext, semanticHashesByPath);
            logProgress(++processed, sources.size());
        }
        return files;
    }

    private List<FileMetric> parseParallel(List<Path> sources, Path base, List<FileError> errors,
                                           ScanAccumulator summaryAccumulator, ScanRunContext run,
                                           IncrementalAnalysisEngine incrementalEngine, CacheRunCounters cacheCounters,
                                           IncrementalScanContext scanContext, Map<String, String> semanticHashesByPath) {
        List<FileMetric> files = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(scanOptions.workers(), ProjectAnalyzer::newWorkerThread);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Path source : sources) {
                tasks.add(() -> {
                    parseOne(source, base, files, errors, summaryAccumulator, run, incrementalEngine, cacheCounters,
                            scanContext, semanticHashesByPath);
                    logProgress(processed.incrementAndGet(), sources.size());
                    return null;
                });
            }
            try {
                List<Future<Void>> futures = pool.invokeAll(tasks);
                for (Future<Void> future : futures) {
                    future.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Parallel parse interrupted", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Parallel parse failed", e.getCause());
            }
        } finally {
            pool.shutdown();
        }
        return new ArrayList<>(files);
    }

    private void parseOne(Path source, Path base, List<FileMetric> files, List<FileError> errors,
                          ScanAccumulator summaryAccumulator, ScanRunContext run,
                          IncrementalAnalysisEngine incrementalEngine, CacheRunCounters cacheCounters,
                          IncrementalScanContext scanContext, Map<String, String> semanticHashesByPath) {
        String relative = JavaSourceDiscovery.relativePath(base, source);
        run.parsingFile(relative);
        try {
            String sourceText = SourceEncoding.read(source, sourceCharset());
            if (incrementalEngine != null) {
                if (scanContext != null && scanContext.bulkReuse(relative)) {
                    FileMetric reused = incrementalEngine.reusableFromState(relative);
                    if (reused != null) {
                        files.add(reused);
                        if (cacheCounters != null) {
                            cacheCounters.filesSkippedViaModuleBulk++;
                            cacheCounters.totalMethods += reused.methodCount();
                            cacheCounters.methodsSkippedViaHash += reused.methodCount();
                        }
                        copyPreviousSemanticHash(incrementalEngine, semanticHashesByPath, relative);
                        ingestReused(relative, reused, summaryAccumulator);
                        return;
                    }
                }
                String precomputed = scanContext != null ? scanContext.byteHash(relative) : null;
                IncrementalAnalysisEngine.CacheCheck check =
                        incrementalEngine.checkFileCache(source, relative, precomputed);
                if (check.canSkipParsing()) {
                    FileMetric reused = check.reusableMetric().reusedCopy();
                    files.add(reused);
                    if (cacheCounters != null) {
                        cacheCounters.filesSkippedViaFileHash++;
                        cacheCounters.totalMethods += reused.methodCount();
                        cacheCounters.methodsSkippedViaHash += reused.methodCount();
                    }
                    copyPreviousSemanticHash(incrementalEngine, semanticHashesByPath, relative);
                    ingestReused(relative, reused, summaryAccumulator);
                    return;
                }
                SourceParser.Outcome outcome = SourceParser.parse(source, sourceCharset(), languageLevel);
                ParseResult<CompilationUnit> result = outcome.result();
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    CompilationUnit cu = result.getResult().get();
                    String semanticHash = HashService.semanticCompilationUnitHash(cu);
                    semanticHashesByPath.put(relative, semanticHash);
                    if (incrementalEngine.isSemanticOnlyDrift(relative, semanticHash)) {
                        FileMetric cached = incrementalEngine.reusableFromState(relative);
                        if (cached != null) {
                            FileMetric metric = cached.withRefreshedFileHash(check.fileHash());
                            files.add(metric);
                            if (cacheCounters != null) {
                                cacheCounters.filesSemanticCosmeticOnly++;
                                cacheCounters.totalMethods += metric.methodCount();
                                cacheCounters.methodsSkippedViaHash += metric.methodCount();
                            }
                            ingestReused(relative, metric, summaryAccumulator);
                            return;
                        }
                    }
                    FileMetric metric = incrementalEngine.buildFileMetric(relative, sourceText, outcome.usedLevel(), cu,
                            check.fileHash(), riskCalculator,
                            incrementalEngine.previousMethodsByKey(relative), summaryAccumulator,
                            scanOptions.pmdMetricAlignment());
                    files.add(metric);
                    if (cacheCounters != null) {
                        cacheCounters.filesReanalyzed++;
                        cacheCounters.totalMethods += metric.methodCount();
                        cacheCounters.methodsSkippedViaHash += metric.methodsReusedFromCache();
                        cacheCounters.methodsReanalyzed += metric.methodCount() - metric.methodsReusedFromCache();
                    }
                } else {
                    String detail = describe(result.getProblems());
                    if (outcome.attempted().size() > 1) {
                        detail = detail + " (tried language levels: "
                                + outcome.attempted().stream().map(Enum::name).collect(Collectors.joining(", "))
                                + ")";
                    }
                    errors.add(fileError(relative, "Parse error: " + detail));
                }
                return;
            }
            SourceParser.Outcome outcome = SourceParser.parse(source, sourceCharset(), languageLevel);
            ParseResult<CompilationUnit> result = outcome.result();
            if (result.isSuccessful() && result.getResult().isPresent()) {
                files.add(buildFileMetric(relative, sourceText, outcome.usedLevel(),
                        result.getResult().get(), summaryAccumulator));
            } else {
                String detail = describe(result.getProblems());
                if (outcome.attempted().size() > 1) {
                    detail = detail + " (tried language levels: "
                            + outcome.attempted().stream().map(Enum::name).collect(Collectors.joining(", ")) + ")";
                }
                errors.add(fileError(relative, "Parse error: " + detail));
            }
        } catch (IOException e) {
            errors.add(fileError(relative, "I/O error: " + e.getMessage()));
        } catch (ParseProblemException e) {
            errors.add(fileError(relative, "Parse error: " + e.getMessage()));
        } catch (StackOverflowError e) {
            errors.add(fileError(relative, "Analysis aborted: expression nesting too deep (stack overflow)"));
        } catch (OutOfMemoryError e) {
            errors.add(fileError(relative, "Analysis aborted: out of memory while analyzing file"));
        } catch (RuntimeException e) {
            errors.add(fileError(relative, "Unexpected error: " + e));
        } finally {
            run.clearParsingFile();
            run.fileFinished();
        }
    }

    private static void copyPreviousSemanticHash(
            IncrementalAnalysisEngine engine, Map<String, String> semanticHashesByPath, String relative) {
        String previous = engine.previousSemanticFileHash(relative);
        if (previous != null) {
            semanticHashesByPath.put(relative, previous);
        }
    }

    private static void ingestReused(String relative, FileMetric reused, ScanAccumulator summaryAccumulator) {
        if (summaryAccumulator != null && !reused.classes().isEmpty()) {
            summaryAccumulator.ingestFile(relative, reused.packageName(), reused.classes(), reused.codeLines());
        }
    }

    private static FileError fileError(String relative, String message) {
        return new FileError(relative, message, ScanErrorClassifier.classify(message));
    }

    private void logProgress(int processed, int total) {
        if (processed % scanOptions.progressEvery() == 0 || processed == total) {
            System.err.println("[STANDALONE] " + processed + "/" + total + " files processed");
        }
    }

    // ------------------------------------------------------------------ file discovery

    private record FileDiscovery(List<Path> paths, int skippedByFilter) {
    }

    /**
     * Skip {@code target/build/dist/...} trees but not package segments such as
     * {@code src/main/java/com/acme/build/util}.
     */
    static boolean shouldSkipAsBuildOutputDirectory(Path scanRoot, Path dir, boolean applyDefaultIgnores) {
        if (!applyDefaultIgnores) {
            return false;
        }
        Path name = dir.getFileName();
        if (name == null || !JavaSourceDiscovery.IGNORED_DIRECTORIES.contains(name.toString())) {
            return false;
        }
        String relative = scanRoot.relativize(dir).toString().replace('\\', '/');
        if (isUnderJavaSourceTree(relative)) {
            return false;
        }
        Path parent = dir.getParent();
        return parent != null && isModuleBoundaryDirectory(scanRoot, parent);
    }

    static boolean isModuleBoundaryDirectory(Path scanRoot, Path directory) {
        if (directory.equals(scanRoot)) {
            return true;
        }
        return hasBuildMarker(directory)
                || Files.isDirectory(directory.resolve("src/main/java"))
                || Files.isDirectory(directory.resolve("src"));
    }

    private static boolean hasBuildMarker(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml"))
                || Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"));
    }

    private Charset sourceCharset() {
        Charset encoding = parserConfiguration.getCharacterEncoding();
        return encoding != null ? encoding : java.nio.charset.StandardCharsets.UTF_8;
    }

    static boolean isUnderJavaSourceTree(String relativeUnixPath) {
        return relativeUnixPath.contains("/src/main/java/")
                || relativeUnixPath.contains("/src/test/java/")
                || relativeUnixPath.startsWith("src/main/java/")
                || relativeUnixPath.startsWith("src/test/java/");
    }

    private static String describe(List<Problem> problems) {
        if (problems.isEmpty()) {
            return "unknown parser failure";
        }
        return problems.stream().limit(3).map(Problem::getVerboseMessage).collect(Collectors.joining(" | "));
    }

    // ------------------------------------------------------------------ per-file assembly

    private FileMetric buildFileMetric(String relativePath, String sourceText,
                                       ParserConfiguration.LanguageLevel languageLevel, CompilationUnit cu,
                                       ScanAccumulator summaryAccumulator) {
        return FileMetricsBuilder.build(relativePath, sourceText, languageLevel, cu, riskCalculator, Map.of(),
                summaryAccumulator, "", false, scanOptions.pmdMetricAlignment());
    }

    private static void logIncrementalChanges(IncrementalChanges changes) {
        System.err.printf(Locale.ROOT,
                "[STANDALONE] Since last scan: files +%d -%d ~%d cosmetic %d (= %d unchanged); methods +%d -%d ~%d%n",
                changes.filesAdded(), changes.filesRemoved(), changes.filesModified(), changes.filesCosmeticOnly(),
                changes.filesUnchanged(),
                changes.methodsAdded(), changes.methodsRemoved(), changes.methodsModified());
        int limit = 15;
        int shown = 0;
        for (IncrementalChanges.MethodPathChange mc : changes.methodChanges()) {
            if (shown >= limit) {
                System.err.println("[STANDALONE]   ... more method changes in JSON incrementalChanges");
                break;
            }
            if (mc.kind() == IncrementalChanges.ChangeKind.REMOVED) {
                System.err.printf(Locale.ROOT, "[STANDALONE]   %s %s.%s removed%n",
                        mc.file(), mc.className(), mc.methodSignature());
            } else {
                System.err.printf(Locale.ROOT, "[STANDALONE]   %s %s.%s lines %d-%d (%s)%n",
                        mc.file(), mc.className(), mc.methodSignature(), mc.startLine(), mc.endLine(),
                        mc.kind().name().toLowerCase(Locale.ROOT));
            }
            shown++;
        }
    }

    private static void persistAnalyzerState(
            Path stateFile,
            String cacheIdentity,
            List<FileMetric> files,
            Map<String, String> semanticHashesByPath,
            Map<String, String> moduleFingerprints) {
        AnalyzerState newState = new AnalyzerState();
        newState.cacheIdentity = cacheIdentity;
        for (Map.Entry<String, String> entry : moduleFingerprints.entrySet()) {
            AnalyzerState.ModuleState moduleState = new AnalyzerState.ModuleState();
            moduleState.fingerprint = entry.getValue();
            newState.modules.put(entry.getKey(), moduleState);
        }
        for (FileMetric file : files) {
            AnalyzerState.FileState fileState = new AnalyzerState.FileState();
            fileState.fileHash = file.fileHash();
            fileState.semanticFileHash = semanticHashesByPath.get(file.path());
            fileState.cachedFileMetric = file;
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    AnalyzerState.MethodState methodState = new AnalyzerState.MethodState();
                    methodState.methodHash = method.methodHash();
                    methodState.lastCalculatedRiskScore = method.riskScore();
                    fileState.methods.put(type.name() + "#" + method.signature(), methodState);
                }
            }
            newState.files.put(file.path(), fileState);
        }
        new StatePersistenceManager().save(stateFile, newState);
    }

    private static void logCacheSummary(AnalysisReport.CacheStatistics s) {
        double seconds = s.scanTimeMillis() / 1000.0;
        System.err.printf(Locale.ROOT,
                "[STANDALONE] Incremental cache: files %d | skip file-hash %d | skip module-bulk %d | "
                        + "re-parsed %d | skip cosmetic (AST) %d | modules unchanged %d | methods re-analyzed %d | "
                        + "methods reused %d | %.1fs%n",
                s.totalFiles(), s.filesSkippedViaFileHash(), s.filesSkippedViaModuleBulk(), s.filesReanalyzed(),
                s.filesSemanticCosmeticOnly(), s.modulesUnchanged(), s.methodsReanalyzed(),
                s.methodsSkippedViaHash(), seconds);
    }

    private static final class CacheRunCounters {
        int filesSkippedViaFileHash;
        int filesSkippedViaModuleBulk;
        int filesReanalyzed;
        int filesSemanticCosmeticOnly;
        int modulesUnchanged;
        int totalMethods;
        int methodsSkippedViaHash;
        int methodsReanalyzed;
    }

    // ------------------------------------------------------------------ report-level aggregation

    private Summary buildSummary(int scanned, List<FileMetric> files, List<FileError> errors, Path scanRoot) {
        return ReportAggregates.buildSummary(scanned, files, errors, scanRoot);
    }

    private List<RiskHotspot> buildHotspots(List<FileMetric> files) {
        return ReportAggregates.buildHotspots(files, topN);
    }

    private static Thread newWorkerThread(Runnable task) {
        int id = WORKER_ID.incrementAndGet();
        return new Thread(null, task, "standalone-parser-" + id, WORKER_STACK_BYTES);
    }

}
