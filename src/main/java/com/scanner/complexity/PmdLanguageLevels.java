package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;

import java.util.Locale;

/** JavaParser {@link ParserConfiguration.LanguageLevel} → PMD {@link LanguageVersion}. */
final class PmdLanguageLevels {

    private PmdLanguageLevels() {
    }

    static LanguageVersion toPmd(ParserConfiguration.LanguageLevel level) {
        String version = mapJavaParserLevelToPmdVersion(level);
        LanguageVersion resolved = LanguageRegistry.PMD.getLanguageVersionById("java", version);
        if (resolved != null) {
            return resolved;
        }
        LanguageVersion fallback = LanguageRegistry.PMD.getLanguageVersionById("java", "17");
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("No PMD Java language version available");
    }

    private static String mapJavaParserLevelToPmdVersion(ParserConfiguration.LanguageLevel level) {
        String name = level.name();
        if (!name.startsWith("JAVA_")) {
            return "17";
        }
        String tail = name.substring("JAVA_".length()).toLowerCase(Locale.ROOT);
        return switch (tail) {
            case "6" -> "1.6";
            case "7" -> "1.7";
            case "8" -> "1.8";
            default -> tail.replace('_', '.');
        };
    }
}
