package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.FileError;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Yalnızca klasik PMD ruleset → {@code pmd.xml} (aşama 1 tek başına).
 * Tam akış için {@link PmdScanMain} (aşama 1 + parser aşama 2).
 */
public final class ClassicPmdMain {

    private ClassicPmdMain() {
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
            if (!Files.isRegularFile(options.ruleset())) {
                System.err.println("[ERROR] Ruleset not found: " + options.ruleset());
                return 1;
            }
            logTargets(options);
            int violations = scan(options);
            System.err.println("[CLASSIC-PMD] Done: " + violations + " violation(s) → "
                    + options.output().toAbsolutePath().normalize());
            return violations > 0 ? 4 : 0;
        } catch (IOException e) {
            System.err.println("[FATAL] " + e.getMessage());
            return 1;
        }
    }

    private static int scan(Options options) throws IOException {
        Path absoluteRoot = options.path().toAbsolutePath().normalize();
        Path base = Files.isDirectory(absoluteRoot) ? absoluteRoot : absoluteRoot.getParent();
        List<FileError> errors = new ArrayList<>();
        JavaSourceDiscovery.Result discovery =
                JavaSourceDiscovery.collect(absoluteRoot, base, errors, options.scanOptions());
        List<Path> sources = discovery.paths();
        if (discovery.skippedDirectorySubtrees() > 0) {
            System.err.println("[CLASSIC-PMD] Skipped " + discovery.skippedDirectorySubtrees()
                    + " directory subtree(s) (build outputs / exclude globs)");
        }
        System.err.println("[CLASSIC-PMD] " + sources.size() + " .java file(s), ruleset "
                + options.ruleset().toAbsolutePath().normalize()
                + ", language " + options.languageLevel().name()
                + ", threads=" + options.scanOptions().workers());
        for (FileError error : errors) {
            System.err.println("[CLASSIC-PMD] " + error.file() + ": " + error.message());
        }
        var phase = ClassicPmdRulesetScanner.scan(sources, base, options.ruleset(), options.output(),
                options.charset(), options.languageLevel(), options.scanOptions().workers());
        return phase.violationCount();
    }

    private static void logTargets(Options options) {
        System.err.println("[CLASSIC-PMD] XML hedef: " + options.output().toAbsolutePath().normalize());
        if (options.markdown() != null) {
            System.err.println("[CLASSIC-PMD] Markdown hedef: " + options.markdown().toAbsolutePath().normalize());
        }
    }

    private static void printUsage() {
        System.err.println("""
                Klasik PMD ruleset taraması (pmd.xml violation)

                Usage:
                  java -cp java-code-analyzer.jar com.scanner.complexity.ClassicPmdMain --path=<dir|file> [options]

                Options (parser ile hizalı keşif):
                  --path=<dir|file>           Taranacak kaynak (zorunlu)
                  --output=<file>             pmd.xml (zorunlu)
                  --ruleset=<file>            Varsayılan: config/pmd-ruleset-complexity.xml
                  --language-level=JAVA_17
                  --workers=<n>
                  --include=<glob> / --exclude=<glob>
                  --no-default-ignores
                  --help

                Tam rapor (PMD + risk + duplicate): PmdScanMain / run-pmd-analyze.sh
                """);
    }

    private record Options(Path path, Path output, Path markdown, Path ruleset, Charset charset,
                           ParserConfiguration.LanguageLevel languageLevel, ScanOptions scanOptions,
                           boolean help) {

        static Options parse(String[] args) {
            Path path = null;
            Path output = null;
            Path markdown = null;
            Path ruleset = Path.of("config/pmd-ruleset-complexity.xml");
            Charset charset = StandardCharsets.UTF_8;
            ParserConfiguration.LanguageLevel languageLevel = ParserConfiguration.LanguageLevel.JAVA_17;
            List<String> includes = new ArrayList<>();
            List<String> excludes = new ArrayList<>();
            int workers = 0;
            boolean help = false;
            boolean noDefaultIgnores = false;

            for (String arg : args) {
                if (arg.equals("--help") || arg.equals("-h")) {
                    help = true;
                } else if (arg.startsWith("--path=")) {
                    path = Paths.get(value(arg));
                } else if (arg.startsWith("--output=")) {
                    output = Paths.get(value(arg));
                } else if (arg.startsWith("--markdown=")) {
                    markdown = Paths.get(value(arg));
                } else if (arg.startsWith("--ruleset=")) {
                    ruleset = Paths.get(value(arg));
                } else if (arg.startsWith("--encoding=")) {
                    charset = Charset.forName(value(arg));
                } else if (arg.startsWith("--language-level=")) {
                    languageLevel = parseLanguageLevel(value(arg));
                } else if (arg.startsWith("--workers=")) {
                    workers = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--include=")) {
                    includes.addAll(ScanOptions.splitCsv(value(arg)));
                } else if (arg.startsWith("--exclude=")) {
                    excludes.addAll(ScanOptions.splitCsv(value(arg)));
                } else if (arg.equals("--no-default-ignores")) {
                    noDefaultIgnores = true;
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (!help && path == null) {
                throw new IllegalArgumentException("Missing --path=<dir>");
            }
            if (!help && output == null) {
                throw new IllegalArgumentException("Missing --output=<pmd.xml>");
            }
            ScanOptions scanOptions = ScanOptions.fromCli(includes, excludes, workers, 500,
                    ScanOptions.ReportDetail.FULL, !noDefaultIgnores, null, false, false, 50, false, 0,
                    false, false);
            return new Options(path, output, markdown, ruleset, charset, languageLevel, scanOptions, help);
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
