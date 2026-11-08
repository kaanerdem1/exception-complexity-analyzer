package com.scanner.complexity;

import java.io.BufferedWriter;
import java.io.Reader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.ScanOptions.ReportDetail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Entry point.
 *
 * <pre>
 *   java -jar java-code-analyzer.jar --path=/path/to/java6/src [--output=report.json]
 *        [--top=20] [--encoding=UTF-8] [--compact] [--verbose] [--markdown=report.md]
 * </pre>
 * JSON goes to stdout (or --output); progress and diagnostics go to stderr.
 * Exit codes: 0 = success, 1 = invalid arguments or fatal I/O error.
 */
public final class Java6CodeAnalyzerMain {

    private static final int DEFAULT_TOP = 20;

    private Java6CodeAnalyzerMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[ERROR] " + e.getMessage());
            printUsage();
            return 1;
        }
        if (options.help()) {
            printUsage();
            return 0;
        }

        ScanRunContext run = new ScanRunContext();
        try {
            if (!Files.exists(options.path()) || !Files.isReadable(options.path())) {
                System.err.println("[ERROR] Path does not exist or is not readable: " + options.path());
                return 1;
            }
            logResolvedOutputTargets(options);

            RiskCalculator riskCalculator =
                    RiskProfileLoader.createCalculator(options.riskProfile(), options.riskConfig());
            AnalysisConsoleLogger.logRunHeader(
                    new AnalysisConsoleLogger.PathLabel(options.path().toAbsolutePath().normalize().toString()),
                    options.verbose(), riskCalculator);

            AnalysisReport report = new ProjectAnalyzer(
                    options.charset(), options.top(), options.languageLevel(), options.scanOptions(),
                    riskCalculator)
                    .analyze(options.path(), run);

            run.phase(ScanPhase.WRITE_JSON);
            writeJson(report, options);
            run.phase(ScanPhase.WRITE_MARKDOWN);
            writeMarkdownIfRequested(report, options);

            String jsonPath = options.output() != null
                    ? options.output().toAbsolutePath().normalize().toString()
                    : null;
            AnalysisConsoleLogger.logSummary(report, jsonPath);
            AnalysisConsoleLogger.logHotspots(report.topRiskyMethods(), options.top());
            AnalysisConsoleLogger.logParseErrors(report.errors());
            if (options.verbose()) {
                AnalysisConsoleLogger.logVerboseMethods(report.files());
            }

            AnalysisReport.Summary s = report.summary();
            System.err.println("[STANDALONE] Done: " + s.filesParsed() + " parsed, " + s.filesFailed() + " failed, "
                    + s.methodCount() + " methods, project risk " + s.projectRiskScore()
                    + " (" + s.projectRiskLevel() + ")");

            run.phase(ScanPhase.EXIT_EVAL);
            int exitCode = ScanExitEvaluator.evaluate(report, options.maxFailureRatio(), options.failOnRisk());
            AnalysisConsoleLogger.logScanDiagnostics(ScanDiagnostics.from(report, run, exitCode));
            return exitCode;
        } catch (IOException e) {
            run.markFatal(run.phase(), e.getMessage());
            AnalysisConsoleLogger.logFatal(run.phase(), e.getMessage(), run.currentFile());
            return 1;
        } catch (RuntimeException e) {
            run.markFatal(run.phase(), e.getMessage());
            AnalysisConsoleLogger.logFatal(run.phase(), e.getMessage(), run.currentFile());
            return 1;
        }
    }

    private static void writeMarkdownIfRequested(AnalysisReport report, Options options) throws IOException {
        if (options.markdown() == null) {
            return;
        }
        Path parent = options.markdown().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer w = Files.newBufferedWriter(options.markdown(), StandardCharsets.UTF_8)) {
            if (options.output() != null && Files.isRegularFile(options.output())) {
                try (Reader r = Files.newBufferedReader(options.output(), StandardCharsets.UTF_8)) {
                    StandaloneReportMarkdown.renderFromJson(r, w);
                }
            } else {
                StandaloneReportMarkdown.write(report, w);
            }
        }
        Path mdPath = options.markdown().toAbsolutePath().normalize();
        if (!Files.isRegularFile(mdPath)) {
            throw new IOException("Markdown file was not created: " + mdPath);
        }
        System.err.println("[STANDALONE] Readable Markdown: " + mdPath);
    }

    private static void logResolvedOutputTargets(Options options) {
        if (options.output() == null) {
            System.err.println("[STANDALONE] JSON cikti: stdout (dosya yok). Windows CMD'de --output= "
                    + "bos kalirsa veya STANDALONE_OUTPUT set edilmezse JSON terminale akar.");
        } else {
            System.err.println("[STANDALONE] JSON hedef: " + options.output().toAbsolutePath().normalize());
        }
        if (options.markdown() == null) {
            System.err.println("[STANDALONE] Markdown: yazilmayacak");
        } else {
            System.err.println("[STANDALONE] Markdown hedef: " + options.markdown().toAbsolutePath().normalize());
        }
    }

    private static void writeJson(AnalysisReport report, Options options) throws IOException {
        if (options.output() != null) {
            Path parent = options.output().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer w = Files.newBufferedWriter(options.output(), StandardCharsets.UTF_8)) {
                AnalysisReportJsonWriter.write(report, w, options.compact());
            }
            Path written = options.output().toAbsolutePath().normalize();
            if (!Files.isRegularFile(written)) {
                throw new IOException("JSON file was not created: " + written);
            }
            System.err.println("[STANDALONE] Report written to " + written);
        } else {
            Writer w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8));
            AnalysisReportJsonWriter.write(report, w, options.compact());
            w.write('\n');
            w.flush();
        }
    }

    private static void printUsage() {
        System.err.println("""
                Standalone Java Code Analyzer

                Usage:
                  java -jar java-code-analyzer.jar --path=<dir|file> [options]

                Options:
                  --path=<dir|file>   Source root (required): proje kökü veya src/main/java
                  --language-level=<n>  Parse modu: JAVA_17 (varsayılan), JAVA_11, JAVA_6, 17, 6 ...
                  --output=<file>     Write JSON to file instead of stdout
                  --top=<n>           Number of risk hotspots in the report (default 20)
                  --encoding=<name>   Source encoding (default UTF-8; e.g. ISO-8859-9, windows-1254)
                  --compact           Single-line JSON
                  --verbose           List every method with riskBreakdown on stderr
                  --markdown=<file>   Also write human-readable Markdown report (Türkçe)
                  --workers=<n>       Parallel parse threads (0 = auto, default)
                  --progress-every=<n> Log every N files (default 500)
                  --include=<glob>    Only scan matching paths (repeatable / comma-separated)
                  --exclude=<glob>    Skip matching paths (repeatable / comma-separated)
                  --detail=full|summary  full = all methods in JSON; summary = hotspots + file totals
                  --no-default-ignores  Do not skip target/build/etc. at module boundaries
                  --risk-profile=<id>   YAML profile (default: enterprise-java)
                  --risk-config=<file>  Risk YAML path (default: config/risk-parameters-proposal.yaml)
                  --max-failure-ratio=<0-1>  Exit 2 if parse failures / files scanned exceeds ratio
                  --fail-on-risk=<LEVEL>   Exit 3 if any method or project risk >= LEVEL (LOW|MEDIUM|HIGH|CRITICAL)
                  --state=<file>      Incremental cache JSON (Stage-1 file hash + Stage-2 method hash)
                  --fresh             Ignore existing --state file (cold run, still writes state at end)
                  --no-duplicates     Skip duplicate / near-duplicate detection (incl. PMD CPD pass)
                  --no-near-duplicates  Skip PMD CPD only (EXACT_TEXT / EXACT_STRUCTURE still run)
                  --min-duplicate-tokens=<n>  PMD CPD min. ortak parça eşiği (default 50; CLI adı PMD ile uyumlu)
                  --near-duplicate-auto-skip-files=<n>  Opt-in: skip CPD when parsed files >= n (default 0=never)
                  --force-near-duplicates  Reserved; CPD runs by default unless --no-near-duplicates or opt-in skip
                  --no-pmd-metrics    Skip PMD CC/cognitive/NCSS/NPath alignment (faster; JavaParser counts only)
                  --help              Show this help
                """);
    }

    private record Options(Path path, Path output, Path markdown, int top, Charset charset,
                           ParserConfiguration.LanguageLevel languageLevel, ScanOptions scanOptions,
                           String riskProfile, Path riskConfig, Double maxFailureRatio, RiskLevel failOnRisk,
                           boolean compact, boolean verbose, boolean help) {

        static Options parse(String[] args) {
            Path path = null;
            Path output = null;
            Path markdown = null;
            int top = DEFAULT_TOP;
            Charset charset = StandardCharsets.UTF_8;
            ParserConfiguration.LanguageLevel languageLevel = defaultLanguageLevel();
            List<String> includes = new ArrayList<>();
            List<String> excludes = new ArrayList<>();
            int workers = 0;
            int progressEvery = 0;
            ReportDetail detail = ReportDetail.FULL;
            boolean compact = false;
            boolean verbose = false;
            boolean help = false;
            boolean noDefaultIgnores = false;
            String riskProfile = null;
            Path riskConfig = null;
            Double maxFailureRatio = null;
            RiskLevel failOnRisk = null;
            Path incrementalState = null;
            boolean freshIncremental = false;
            boolean detectDuplicates = true;
            int minDuplicateTokens = 0;
            boolean detectNearMissDuplicates = true;
            int nearDuplicateAutoSkipMinFiles = -1;
            boolean forceNearMissDuplicates = false;
            boolean pmdMetricAlignment = true;
            boolean outputFlagSeen = false;
            boolean markdownFlagSeen = false;

            for (String arg : args) {
                if (arg.equals("--help") || arg.equals("-h")) {
                    help = true;
                } else if (arg.equals("--verbose") || arg.equals("-v")) {
                    verbose = true;
                } else if (arg.equals("--compact")) {
                    compact = true;
                } else if (arg.startsWith("--path=")) {
                    path = Paths.get(pathValue(arg, "--path"));
                } else if (arg.startsWith("--output=")) {
                    outputFlagSeen = true;
                    output = optionalPathArg(arg, "--output");
                } else if (arg.startsWith("--markdown=")) {
                    markdownFlagSeen = true;
                    markdown = optionalPathArg(arg, "--markdown");
                } else if (arg.startsWith("--top=")) {
                    top = parseTop(value(arg));
                } else if (arg.startsWith("--encoding=")) {
                    charset = Charset.forName(value(arg));
                } else if (arg.startsWith("--language-level=")) {
                    languageLevel = parseLanguageLevel(value(arg));
                } else if (arg.startsWith("--workers=")) {
                    workers = parsePositiveInt(value(arg), "--workers");
                } else if (arg.startsWith("--progress-every=")) {
                    progressEvery = parsePositiveInt(value(arg), "--progress-every");
                } else if (arg.startsWith("--include=")) {
                    includes.addAll(ScanOptions.splitCsv(value(arg)));
                } else if (arg.startsWith("--exclude=")) {
                    excludes.addAll(ScanOptions.splitCsv(value(arg)));
                } else if (arg.startsWith("--detail=")) {
                    detail = ScanOptions.parseDetail(value(arg));
                } else if (arg.equals("--no-default-ignores")) {
                    noDefaultIgnores = true;
                } else if (arg.startsWith("--risk-profile=")) {
                    riskProfile = value(arg);
                } else if (arg.startsWith("--risk-config=")) {
                    riskConfig = Paths.get(pathValue(arg, "--risk-config"));
                } else if (arg.startsWith("--max-failure-ratio=")) {
                    maxFailureRatio = parseRatio(value(arg));
                } else if (arg.startsWith("--fail-on-risk=")) {
                    failOnRisk = RiskLevel.valueOf(value(arg).trim().toUpperCase(Locale.ROOT));
                } else if (arg.startsWith("--state=")) {
                    incrementalState = optionalPathArg(arg, "--state");
                } else if (arg.equals("--fresh")) {
                    freshIncremental = true;
                } else if (arg.equals("--no-duplicates")) {
                    detectDuplicates = false;
                } else if (arg.equals("--no-near-duplicates")) {
                    detectNearMissDuplicates = false;
                } else if (arg.startsWith("--min-duplicate-tokens=")) {
                    minDuplicateTokens = parsePositiveInt(value(arg), "--min-duplicate-tokens");
                } else if (arg.startsWith("--near-duplicate-auto-skip-files=")) {
                    nearDuplicateAutoSkipMinFiles = parsePositiveInt(value(arg),
                            "--near-duplicate-auto-skip-files");
                } else if (arg.equals("--force-near-duplicates")) {
                    forceNearMissDuplicates = true;
                } else if (arg.equals("--no-pmd-metrics")) {
                    pmdMetricAlignment = false;
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (!help && path == null) {
                throw new IllegalArgumentException("Missing required argument --path=<dir>");
            }
            OutputTargets targets = coalesceOutputPaths(path, output, markdown, outputFlagSeen, markdownFlagSeen);
            OutputTargets envTargets = applyEnvironmentOutputPaths(targets.json(), targets.markdown());
            output = envTargets.json();
            markdown = envTargets.markdown();
            ScanOptions scanOptions = ScanOptions.fromCli(includes, excludes, workers, progressEvery, detail,
                    !noDefaultIgnores, incrementalState, freshIncremental, detectDuplicates, minDuplicateTokens,
                    detectNearMissDuplicates, nearDuplicateAutoSkipMinFiles, forceNearMissDuplicates,
                    pmdMetricAlignment);
            return new Options(path, output, markdown, top, charset, languageLevel, scanOptions, riskProfile,
                    riskConfig, maxFailureRatio, failOnRisk, compact, verbose, help);
        }

        private static double parseRatio(String raw) {
            double value = Double.parseDouble(raw);
            if (value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("--max-failure-ratio must be between 0 and 1");
            }
            return value;
        }

        private static int parsePositiveInt(String raw, String flag) {
            try {
                int parsed = Integer.parseInt(raw);
                if (parsed < 0) {
                    throw new IllegalArgumentException(flag + " must be >= 0");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(flag + " must be an integer: " + raw);
            }
        }

        /**
         * Empty {@code --output=} / {@code --markdown=} from Windows CMD are ignored (see {@link #coalesceOutputPaths}).
         */
        private static Path optionalPathArg(String arg, String flag) {
            String raw = arg.substring(flag.length() + 1).trim();
            if (raw.isEmpty()) {
                return null;
            }
            return Paths.get(pathValue(arg, flag));
        }

        private record OutputTargets(Path json, Path markdown) {
        }

        /** Windows {@code run-analyze.cmd} sets these when CMD quoting drops {@code --output=}. */
        private static OutputTargets applyEnvironmentOutputPaths(Path output, Path markdown) {
            String envJson = System.getenv("STANDALONE_OUTPUT");
            String envMd = System.getenv("STANDALONE_MARKDOWN");
            Path out = output;
            Path md = markdown;
            if (envJson != null && !envJson.isBlank()) {
                out = Paths.get(envJson.trim());
            }
            if (envMd != null && !envMd.isBlank()) {
                md = Paths.get(envMd.trim());
            }
            return new OutputTargets(out, md);
        }

        /**
         * Fill missing JSON/Markdown when a flag was present but empty (typical Windows {@code --output=}).
         * If neither flag was passed, JSON stays on stdout.
         */
        private static OutputTargets coalesceOutputPaths(Path scanRoot, Path output, Path markdown,
                                                         boolean outputFlagSeen, boolean markdownFlagSeen) {
            if (!outputFlagSeen && !markdownFlagSeen) {
                return new OutputTargets(output, markdown);
            }
            if (output != null && markdown != null) {
                return new OutputTargets(output, markdown);
            }
            if (output == null && markdown != null) {
                return new OutputTargets(jsonSiblingForMarkdown(markdown), markdown);
            }
            if (output != null) {
                return new OutputTargets(output, markdownSiblingForJson(output));
            }
            Path outDir = scanRoot.resolve("analysis-output");
            return new OutputTargets(outDir.resolve("standalone.json"), outDir.resolve("parser-raporu.md"));
        }

        private static Path jsonSiblingForMarkdown(Path markdown) {
            String file = markdown.getFileName().toString();
            if (file.endsWith(".md")) {
                String base = file.substring(0, file.length() - 3);
                if (base.startsWith("parser")) {
                    return markdown.resolveSibling("standalone" + base.substring("parser".length()) + ".json");
                }
                return markdown.resolveSibling(base + ".json");
            }
            Path parent = markdown.getParent();
            return parent != null ? parent.resolve("standalone.json") : Paths.get("standalone.json");
        }

        private static Path markdownSiblingForJson(Path json) {
            String name = json.getFileName().toString();
            if (name.startsWith("standalone") && name.endsWith(".json")) {
                String mid = name.substring("standalone".length(), name.length() - 5);
                if (mid.isEmpty()) {
                    return json.resolveSibling("parser-raporu.md");
                }
                return json.resolveSibling("parser" + mid + ".md");
            }
            if (name.endsWith(".json")) {
                return json.resolveSibling(name.substring(0, name.length() - 5) + ".md");
            }
            return json.resolveSibling("parser-raporu.md");
        }

        private static String value(String arg) {
            String value = arg.substring(arg.indexOf('=') + 1).trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Empty value for " + arg);
            }
            return value;
        }

        /**
         * Windows CMD/PowerShell sometimes merges {@code --path="…" --output=…} into one argv token;
         * strip quotes and reject embedded follow-on flags with a clear message.
         */
        private static String pathValue(String arg, String flag) {
            String value = value(arg);
            int mergedFlag = value.indexOf("\" --");
            if (mergedFlag >= 0) {
                value = value.substring(0, mergedFlag);
            }
            value = stripOuterQuotes(value.trim());
            if (value.contains("\"")) {
                throw new IllegalArgumentException(flag + " contains an illegal \" character. "
                        + "Quote each argument separately (e.g. scripts\\run-analyze.cmd C:\\path\\to\\src) "
                        + "or use --path=C:\\path without stray quotes. Value was: " + value);
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Empty value for " + flag);
            }
            return value;
        }

        private static String stripOuterQuotes(String value) {
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                return value.substring(1, value.length() - 1);
            }
            if (value.endsWith("\"")) {
                return value.substring(0, value.length() - 1).trim();
            }
            if (value.startsWith("\"")) {
                return value.substring(1).trim();
            }
            return value;
        }

        private static int parseTop(String raw) {
            try {
                int parsed = Integer.parseInt(raw);
                if (parsed < 1) {
                    throw new IllegalArgumentException("--top must be >= 1");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--top must be an integer: " + raw);
            }
        }
    }

    private static ParserConfiguration.LanguageLevel defaultLanguageLevel() {
        return ParserConfiguration.LanguageLevel.JAVA_17;
    }

    private static ParserConfiguration.LanguageLevel parseLanguageLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultLanguageLevel();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            if (normalized.matches("JAVA_[\\d_]+")) {
                return ParserConfiguration.LanguageLevel.valueOf(normalized);
            }
            if (normalized.matches("\\d+")) {
                return ParserConfiguration.LanguageLevel.valueOf("JAVA_" + normalized);
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw new IllegalArgumentException(
                "Unknown --language-level: " + raw + ". Supported: " + Arrays.stream(
                                ParserConfiguration.LanguageLevel.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", ")));
    }
}
