package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Shared {@link AnalyzerState} load/save and cache identity (parser + PMD-native). */
final class IncrementalStateSupport {

    static final int CACHE_SCHEMA_VERSION = 11;

    private IncrementalStateSupport() {
    }

    static String cacheIdentity(String pipeline, RiskCalculator calculator) {
        RiskProfile profile = calculator.profile();
        return "schema=" + CACHE_SCHEMA_VERSION
                + ";pipeline=" + pipeline
                + ";profile=" + profile.profileId()
                + ";model=" + profile.modelVersion();
    }

    static boolean cacheIdentityMatches(AnalyzerState state, String expectedIdentity) {
        if (state == null || state.cacheIdentity == null || state.cacheIdentity.isBlank()) {
            return false;
        }
        return state.cacheIdentity.equals(expectedIdentity);
    }

    static AnalyzerState load(Path stateFile, boolean ignoreCache) {
        if (stateFile == null || ignoreCache) {
            return new AnalyzerState();
        }
        return new StatePersistenceManager().load(stateFile);
    }

    static void persist(Path stateFile, String cacheIdentity, List<FileMetric> files,
                        Map<String, String> semanticHashesByPath) {
        if (stateFile == null) {
            return;
        }
        AnalyzerState newState = new AnalyzerState();
        newState.cacheIdentity = cacheIdentity;
        for (FileMetric file : files) {
            AnalyzerState.FileState fileState = new AnalyzerState.FileState();
            fileState.fileHash = file.fileHash();
            fileState.semanticFileHash = semanticHashesByPath.get(file.path());
            fileState.cachedFileMetric = file;
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    AnalyzerState.MethodState methodState = new AnalyzerState.MethodState();
                    String body = method.methodHash() != null && !method.methodHash().isEmpty()
                            ? method.methodHash()
                            : (method.bodyHash() == null ? "" : method.bodyHash());
                    methodState.methodHash = body;
                    methodState.bodyHash = body;
                    methodState.lastCalculatedRiskScore = method.riskScore();
                    fileState.methods.put(type.name() + "#" + method.signature(), methodState);
                }
            }
            newState.files.put(file.path(), fileState);
        }
        new StatePersistenceManager().save(stateFile, newState);
    }
}
