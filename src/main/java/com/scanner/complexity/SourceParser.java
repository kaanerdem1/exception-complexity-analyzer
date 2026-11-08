package com.scanner.complexity;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Tek dosya parse; başarısız olursa üst/alt Java dil seviyeleriyle yeniden dener. */
final class SourceParser {

    private SourceParser() {
    }

    record Outcome(ParseResult<CompilationUnit> result, ParserConfiguration.LanguageLevel usedLevel,
                   List<ParserConfiguration.LanguageLevel> attempted) {
    }

    static Outcome parse(Path source, Charset charset, ParserConfiguration.LanguageLevel preferred)
            throws IOException {
        List<ParserConfiguration.LanguageLevel> order = fallbackOrder(preferred);
        ParseResult<CompilationUnit> last = null;
        List<ParserConfiguration.LanguageLevel> tried = new ArrayList<>();
        String sourceText = SourceEncoding.read(source, charset);
        for (ParserConfiguration.LanguageLevel level : order) {
            tried.add(level);
            JavaParser parser = parserFor(charset, level);
            ParseResult<CompilationUnit> result = parser.parse(sourceText);
            last = result;
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return new Outcome(result, level, List.copyOf(tried));
            }
        }
        return new Outcome(last, preferred, List.copyOf(tried));
    }

    private static JavaParser parserFor(Charset charset, ParserConfiguration.LanguageLevel level) {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(level)
                .setCharacterEncoding(charset)
                .setStoreTokens(true)
                .setAttributeComments(false);
        return new JavaParser(config);
    }

    static List<ParserConfiguration.LanguageLevel> fallbackOrder(
            ParserConfiguration.LanguageLevel preferred) {
        Set<ParserConfiguration.LanguageLevel> ordered = new LinkedHashSet<>();
        ordered.add(preferred);
        List<ParserConfiguration.LanguageLevel> all = List.of(ParserConfiguration.LanguageLevel.values());
        int preferredVersion = languageVersion(preferred);
        all.stream()
                .filter(l -> languageVersion(l) > preferredVersion)
                .sorted(Comparator.comparingInt(SourceParser::languageVersion))
                .forEach(ordered::add);
        all.stream()
                .filter(l -> languageVersion(l) < preferredVersion)
                .sorted(Comparator.comparingInt(SourceParser::languageVersion).reversed())
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static int languageVersion(ParserConfiguration.LanguageLevel level) {
        String name = level.name();
        if (!name.startsWith("JAVA_")) {
            return 0;
        }
        String tail = name.substring("JAVA_".length()).replace('_', '.');
        try {
            if (tail.contains(".")) {
                return (int) (Double.parseDouble(tail) * 10);
            }
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
