package com.scanner.complexity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Loads and saves the two-stage lazy-parsing cache ({@code analyzer-state.json}): per-file raw
 * byte hash, per-method normalised-body hash and last granular risk score, plus the full previous
 * {@link AnalysisReport.FileMetric} needed to reuse a file or an individual method without
 * re-running JavaParser or the risk algorithms.
 */
final class StatePersistenceManager {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    AnalyzerState load(Path stateFile) {
        if (stateFile == null || !Files.isRegularFile(stateFile)) {
            return new AnalyzerState();
        }
        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8);
            AnalyzerState state = gson.fromJson(json, AnalyzerState.class);
            if (state == null) {
                return new AnalyzerState();
            }
            if (state.files == null) {
                state.files = new java.util.LinkedHashMap<>();
            }
            if (state.modules == null) {
                state.modules = new java.util.LinkedHashMap<>();
            }
            return state;
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("[ÖNBELLEK] ⚠ State dosyası okunamadı: " + stateFile
                    + " — " + e.getMessage() + ". Boş önbellekle devam ediliyor.");
            return new AnalyzerState();
        }
    }

    void save(Path stateFile, AnalyzerState state) {
        if (stateFile == null) {
            return;
        }
        state.lastScanTimestamp = Instant.now().toString();
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(stateFile, gson.toJson(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[ÖNBELLEK] ✗ State dosyası yazılamadı: " + stateFile
                    + " — " + e.getMessage());
        }
    }
}
