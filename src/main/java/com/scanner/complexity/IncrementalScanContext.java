package com.scanner.complexity;

import java.util.Map;
import java.util.Set;

/** Incremental koşusu öncesi modül parmak izi + önceden okunmuş dosya byte hash'leri. */
final class IncrementalScanContext {

    private final Map<String, String> byteHashByRelativePath;
    private final Set<String> bulkReuseRelativePaths;
    private final Map<String, String> moduleFingerprints;
    private final int modulesUnchanged;
    private final int modulesScanned;

    IncrementalScanContext(
            Map<String, String> byteHashByRelativePath,
            Set<String> bulkReuseRelativePaths,
            Map<String, String> moduleFingerprints,
            int modulesUnchanged,
            int modulesScanned) {
        this.byteHashByRelativePath = byteHashByRelativePath;
        this.bulkReuseRelativePaths = bulkReuseRelativePaths;
        this.moduleFingerprints = moduleFingerprints;
        this.modulesUnchanged = modulesUnchanged;
        this.modulesScanned = modulesScanned;
    }

    String byteHash(String relativePath) {
        return byteHashByRelativePath.get(relativePath);
    }

    boolean bulkReuse(String relativePath) {
        return bulkReuseRelativePaths.contains(relativePath);
    }

    Map<String, String> moduleFingerprints() {
        return moduleFingerprints;
    }

    int modulesUnchanged() {
        return modulesUnchanged;
    }

    int modulesScanned() {
        return modulesScanned;
    }
}
