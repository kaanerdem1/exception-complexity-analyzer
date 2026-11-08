package com.scanner.complexity;

import java.util.Locale;

/** Tarama yöntemi: PMD-yerel (JavaParser yok) veya parser hibrit. */
public enum ScanPipeline {
    /** PMD ruleset + PMD AST/JavaMetrics; risk skoru PMD metrik + ihlal zenginleştirmesinden. */
    PMD_NATIVE,
    /** PMD ruleset, ardından JavaParser ile tam metrik/risk (legacy birleşik hat). */
    PARSER_HYBRID;

    static ScanPipeline parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PMD_NATIVE;
        }
        String n = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "PMD", "PMD_NATIVE", "NATIVE" -> PMD_NATIVE;
            case "HYBRID", "PARSER_HYBRID", "PARSER" -> PARSER_HYBRID;
            default -> throw new IllegalArgumentException("Unknown pipeline: " + raw
                    + " (use pmd-native or parser-hybrid)");
        };
    }
}
