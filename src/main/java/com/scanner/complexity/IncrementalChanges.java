package com.scanner.complexity;

import java.util.List;

/** Diff since the last scan recorded in analyzer-state.json (incremental mode only). */
public record IncrementalChanges(
        boolean comparedToPreviousScan,
        int filesAdded,
        int filesRemoved,
        int filesModified,
        int filesCosmeticOnly,
        int filesUnchanged,
        int methodsAdded,
        int methodsRemoved,
        int methodsModified,
        List<FilePathChange> fileChanges,
        List<MethodPathChange> methodChanges) {

    public IncrementalChanges {
        fileChanges = fileChanges == null ? List.of() : List.copyOf(fileChanges);
        methodChanges = methodChanges == null ? List.of() : List.copyOf(methodChanges);
    }

    public enum ChangeKind {
        ADDED, REMOVED, MODIFIED, COSMETIC
    }

    public record FilePathChange(String path, ChangeKind kind) {
    }

    public record MethodPathChange(
            String file,
            String className,
            String methodSignature,
            int startLine,
            int endLine,
            ChangeKind kind) {
    }

    static IncrementalChanges empty() {
        return new IncrementalChanges(false, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }
}
