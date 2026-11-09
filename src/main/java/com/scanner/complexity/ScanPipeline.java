package com.scanner.complexity;

import java.util.Locale;

/** Tarama yöntemi: yalnızca PMD. */
public enum ScanPipeline {
    /** PMD ruleset + PMD AST/JavaMetrics; karmaşıklık ve kalite ayrı skorlanır. */
    PMD_NATIVE;

    static ScanPipeline parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PMD_NATIVE;
        }
        String n = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (n.equals("PMD") || n.equals("PMD_NATIVE") || n.equals("NATIVE")) {
            return PMD_NATIVE;
        }
        throw new IllegalArgumentException("Unknown pipeline: " + raw + " (yalnızca pmd-native)");
    }
}
