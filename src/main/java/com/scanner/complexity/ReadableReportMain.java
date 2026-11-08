package com.scanner.complexity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JSON/XML → Türkçe Markdown (yeniden tarama gerekmez).
 *
 * <pre>
 *   standalone &lt;report.json&gt; [parser-raporu.md]
 *   pmd        &lt;pmd.xml&gt;      [pmd-raporu.md]
 * </pre>
 */
public final class ReadableReportMain {

    public static final String PARSER_MD = "parser-raporu.md";
    public static final String PMD_MD = "pmd-raporu.md";

    private ReadableReportMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length < 2 || args[0].equals("--help") || args[0].equals("-h")) {
            printUsage();
            return args.length >= 2 && !args[0].equals("--help") ? 0 : 1;
        }
        try {
            String mode = args[0].toLowerCase();
            return switch (mode) {
                case "standalone" -> standalone(args);
                case "pmd" -> pmd(args);
                default -> {
                    System.err.println("[ERROR] Bilinmeyen mod: " + mode);
                    printUsage();
                    yield 1;
                }
            };
        } catch (IOException e) {
            System.err.println("[ERROR] " + e.getMessage());
            return 1;
        }
    }

    private static int standalone(String[] args) throws IOException {
        Path json = Paths.get(args[1]);
        Path out = args.length >= 3 ? Paths.get(args[2]) : defaultMd(json, PARSER_MD);
        ensureParent(out);
        try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8);
             Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            StandaloneReportMarkdown.renderFromJson(r, w);
        }
        System.err.println("[READABLE] Parser Markdown: " + out.toAbsolutePath().normalize());
        return 0;
    }

    private static int pmd(String[] args) throws IOException {
        Path xml = Paths.get(args[1]);
        Path out = args.length >= 3 ? Paths.get(args[2]) : defaultMd(xml, PMD_MD);
        ensureParent(out);
        Files.writeString(out, PmdXmlMarkdown.render(xml), StandardCharsets.UTF_8);
        System.err.println("[READABLE] PMD Markdown: " + out.toAbsolutePath().normalize());
        return 0;
    }

    private static void ensureParent(Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** Varsayılan: aynı klasörde sabit Türkçe dosya adı. */
    private static Path defaultMd(Path source, String fixedName) {
        Path dir = source.getParent() != null ? source.getParent() : Paths.get(".");
        return dir.resolve(fixedName);
    }

    private static void printUsage() {
        System.err.println("""
                Markdown dönüştürücü

                Kullanım:
                  java -cp java-code-analyzer.jar com.scanner.complexity.ReadableReportMain <mod> ...

                Modlar:
                  standalone <report.json> [çıktı.md]   (varsayılan: parser-raporu.md)
                  pmd        <pmd.xml>      [çıktı.md]   (varsayılan: pmd-raporu.md)
                """);
    }
}
