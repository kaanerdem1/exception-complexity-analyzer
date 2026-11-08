package com.scanner.complexity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root of the persisted two-stage lazy-parsing cache ({@code analyzer-state.json}). Serialised
 * and deserialised by {@link StatePersistenceManager} via Gson.
 */
final class AnalyzerState {

    String lastScanTimestamp;
    /** {@link ProjectAnalyzer} cache identity — mismatch invalidates cache automatically. */
    String cacheIdentity;
    Map<String, FileState> files = new LinkedHashMap<>();
    /** Modül kökü (pom/Gradle) → dosya listesi byte-hash parmak izi. */
    Map<String, ModuleState> modules = new LinkedHashMap<>();

    static final class ModuleState {
        String fingerprint;
    }

    /**
     * Per-file cache entry. {@code methods} mirrors the lightweight schema (method key -&gt;
     * normalised-body hash + last granular risk score) for quick inspection/diffing of the JSON
     * file; {@code cachedFileMetric} holds the full previous {@link AnalysisReport.FileMetric} so
     * a whole file — or an individual unchanged method inside a changed file — can be reused
     * verbatim without re-running JavaParser or the risk algorithms. Method keys are
     * {@code "ClassName#signature"} (class-qualified, to avoid collisions between identically
     * named methods in different nested classes of the same file).
     */
    static final class FileState {
        String fileHash;
        /** AST tabanlı özet; yorum/boşluk-only değişiklikleri ayırt etmek için. */
        String semanticFileHash;
        Map<String, MethodState> methods = new LinkedHashMap<>();
        AnalysisReport.FileMetric cachedFileMetric;
    }

    static final class MethodState {
        /** Cache: gövde hash (bodyHash ile aynı). */
        String methodHash;
        String bodyHash;
        double lastCalculatedRiskScore;
    }
}
