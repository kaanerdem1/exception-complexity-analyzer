package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Varsayılan {@link ScanPipeline#PMD_NATIVE}: PMD ruleset + PMD metrik/risk (JavaParser yok).
 * {@link ScanPipeline#PARSER_HYBRID}: PMD ruleset + JavaParser zenginleştirme.
 *
 * <pre>
 *   java -cp java-code-analyzer.jar com.scanner.complexity.PmdScanMain --path=/src [options]
 * </pre>
 */
public final class PmdScanMain {

    private PmdScanMain() {
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
        try {
            if (!Files.exists(options.path()) || !Files.isReadable(options.path())) {
                System.err.println("[ERROR] Path does not exist or is not readable: " + options.path());
                return 1;
            }
            logTargets(options);
            RiskCalculator calculator = RiskProfileLoader.createCalculator(
                    options.riskProfile(), options.riskConfig());
            AnalysisReport report = switch (options.pipeline()) {
                case PMD_NATIVE -> new PmdNativeProjectScanner(
                        options.charset(), options.languageLevel(), options.scanOptions(), options.top(), calculator,
                        options.ruleset(), options.violationsXml(), options.runRulesetPhase())
                        .scan(options.path());
                case PARSER_HYBRID -> new PmdProjectScanner(
                        options.charset(), options.languageLevel(), options.scanOptions(), options.top(), calculator,
                        options.ruleset(), options.violationsXml(), options.runRulesetPhase())
                        .scan(options.path());
            };

            if (options.output() != null) {
                Path parent = options.output().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (Writer w = Files.newBufferedWriter(options.output(), StandardCharsets.UTF_8)) {
                    AnalysisReportJsonWriter.write(report, w, options.compact());
                }
                System.err.println("[PMD-SCAN] JSON: " + options.output().toAbsolutePath().normalize());
            }
            if (options.markdown() != null) {
                Path parent = options.markdown().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (Writer w = Files.newBufferedWriter(options.markdown(), StandardCharsets.UTF_8)) {
                    StandaloneReportMarkdown.write(report, w);
                }
                System.err.println("[PMD-SCAN] Markdown: " + options.markdown().toAbsolutePath().normalize());
            }

            AnalysisReport.Summary s = report.summary();
            System.err.println("[PMD-SCAN] Done: " + s.filesParsed() + " parsed, " + s.filesFailed() + " failed, "
                    + s.methodCount() + " methods, max CC " + s.maxCyclomaticComplexity());
            return s.filesFailed() > 0 ? 2 : 0;
        } catch (IOException e) {
            System.err.println("[FATAL] " + e.getMessage());
            return 1;
        }
    }

    private static void logTargets(Options options) {
        if (options.output() != null) {
            System.err.println("[PMD-SCAN] JSON hedef: " + options.output().toAbsolutePath().normalize());
        }
        if (options.markdown() != null) {
            System.err.println("[PMD-SCAN] Markdown hedef: " + options.markdown().toAbsolutePath().normalize());
        }
    }

    private static void printUsage() {
        System.err.println("""
                PMD-aligned scan (parser ile ayni metrik + risk JSON/Markdown)

                Usage:
                  java -cp java-code-analyzer.jar com.scanner.complexity.PmdScanMain --path=<dir|file> [options]

                Options (parser ile hizalı):
                  --path=<dir|file>        Kaynak kökü (zorunlu)
                  --language-level=<n>     JAVA_17 (varsayılan), JAVA_11, JAVA_6, ...
                  --output=<file>          JSON rapor
                  --markdown=<file>        Türkçe Markdown rapor
                  --top=<n>                En yüksek CC hotspot (varsayılan 20)
                  --encoding=<charset>     UTF-8 (varsayılan)
                  --compact                Tek satır JSON
                  --workers=<n>            Paralel dosya (0=auto)
                  --progress-every=<n>       İlerleme logu (varsayılan 500)
                  --include=<glob>         Dahil (tekrarlanabilir / virgül)
                  --exclude=<glob>         Hariç
                  --detail=full|summary    summary = dosya özeti, metod ağacı yok
                  --no-default-ignores     target/build atlama kapalı
                  --risk-profile=<id>    enterprise-java-pmd (varsayılan); parser: enterprise-java
                  --risk-config=<file>   risk YAML (varsayılan config/risk-parameters-proposal.yaml)
                  --no-pmd-metrics         PMD CC/cog/NCSS/NPath hizasini kapat
                  --ruleset=<file>         Aşama 1 ruleset (vars: config/pmd-ruleset-complexity.xml)
                  --violations-xml=<file>  Aşama 1 pmd.xml (vars: JSON adi -violations.xml)
                  --skip-pmd-ruleset       Yalnizca metrik hatti (ruleset kapali)
                  --pipeline=pmd-native|parser-hybrid   (vars: pmd-native)
                  --state=<file>      Incremental cache JSON (PMD-native: pmd-analyzer-state.json)
                  --fresh             Ignore existing --state (cold run, still writes state at end)
                  --help
                """);
    }

    private record Options(Path path, Path output, Path markdown, int top, Charset charset,
                           ParserConfiguration.LanguageLevel languageLevel, ScanOptions scanOptions,
                           String riskProfile, Path riskConfig, Path ruleset, Path violationsXml,
                           boolean runRulesetPhase, ScanPipeline pipeline, boolean compact, boolean help) {

        static Options parse(String[] args) {
            Path path = null;
            Path output = null;
            Path markdown = null;
            int top = 20;
            Charset charset = StandardCharsets.UTF_8;
            ParserConfiguration.LanguageLevel languageLevel = ParserConfiguration.LanguageLevel.JAVA_17;
            List<String> includes = new ArrayList<>();
            List<String> excludes = new ArrayList<>();
            int workers = 0;
            int progressEvery = 0;
            ScanOptions.ReportDetail detail = ScanOptions.ReportDetail.FULL;
            boolean compact = false;
            boolean help = false;
            boolean noDefaultIgnores = false;
            String riskProfile = "enterprise-java-pmd";
            Path riskConfig = Path.of("config/risk-parameters-proposal.yaml");
            boolean pmdMetricAlignment = true;
            Path ruleset = Path.of("config/pmd-ruleset-complexity.xml");
            Path violationsXml = null;
            boolean runRulesetPhase = true;
            ScanPipeline pipeline = ScanPipeline.PMD_NATIVE;
            Path incrementalState = null;
            boolean freshIncremental = false;

            for (String arg : args) {
                if (arg.equals("--help") || arg.equals("-h")) {
                    help = true;
                } else if (arg.equals("--compact")) {
                    compact = true;
                } else if (arg.startsWith("--path=")) {
                    path = Paths.get(value(arg));
                } else if (arg.startsWith("--output=")) {
                    output = optionalPath(arg, "--output");
                } else if (arg.startsWith("--markdown=")) {
                    markdown = optionalPath(arg, "--markdown");
                } else if (arg.startsWith("--top=")) {
                    top = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--encoding=")) {
                    charset = Charset.forName(value(arg));
                } else if (arg.startsWith("--language-level=")) {
                    languageLevel = parseLanguageLevel(value(arg));
                } else if (arg.startsWith("--workers=")) {
                    workers = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--progress-every=")) {
                    progressEvery = Integer.parseInt(value(arg));
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
                    riskConfig = optionalPath(arg, "--risk-config");
                } else if (arg.equals("--no-pmd-metrics")) {
                    pmdMetricAlignment = false;
                } else if (arg.startsWith("--ruleset=")) {
                    ruleset = Paths.get(value(arg));
                } else if (arg.startsWith("--violations-xml=")) {
                    violationsXml = optionalPath(arg, "--violations-xml");
                } else if (arg.equals("--skip-pmd-ruleset")) {
                    runRulesetPhase = false;
                } else if (arg.startsWith("--pipeline=")) {
                    pipeline = ScanPipeline.parse(value(arg));
                } else if (arg.startsWith("--state=")) {
                    incrementalState = optionalPath(arg, "--state");
                } else if (arg.equals("--fresh")) {
                    freshIncremental = true;
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (!help && path == null) {
                throw new IllegalArgumentException("Missing --path=<dir>");
            }
            if (!help && violationsXml == null && output != null && runRulesetPhase) {
                violationsXml = violationsXmlSibling(output);
            }
            ScanOptions scanOptions = ScanOptions.fromCli(includes, excludes, workers, progressEvery, detail,
                    !noDefaultIgnores, incrementalState, freshIncremental, true, 50, true, 0, false,
                    pmdMetricAlignment);
            return new Options(path, output, markdown, top, charset, languageLevel, scanOptions, riskProfile,
                    riskConfig, ruleset, violationsXml, runRulesetPhase, pipeline, compact, help);
        }

        static Path violationsXmlSibling(Path jsonOutput) {
            Path fileName = jsonOutput.getFileName();
            if (fileName == null) {
                return jsonOutput.resolveSibling("pmd-violations.xml");
            }
            String name = fileName.toString();
            if (name.endsWith(".json")) {
                return jsonOutput.resolveSibling(name.substring(0, name.length() - 5) + "-violations.xml");
            }
            return jsonOutput.resolveSibling(name + "-violations.xml");
        }

        private static Path optionalPath(String arg, String flag) {
            String raw = arg.substring(flag.length() + 1).trim();
            if (raw.isEmpty()) {
                return null;
            }
            return Paths.get(raw);
        }

        private static String value(String arg) {
            return arg.substring(arg.indexOf('=') + 1).trim();
        }

        private static ParserConfiguration.LanguageLevel parseLanguageLevel(String raw) {
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (normalized.matches("JAVA_[\\d_]+")) {
                return ParserConfiguration.LanguageLevel.valueOf(normalized);
            }
            if (normalized.matches("\\d+")) {
                return ParserConfiguration.LanguageLevel.valueOf("JAVA_" + normalized);
            }
            throw new IllegalArgumentException("Unknown language level: " + raw);
        }
    }
}
