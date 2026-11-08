package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.IncrementalChanges.ChangeKind;
import com.scanner.complexity.IncrementalChanges.FilePathChange;
import com.scanner.complexity.IncrementalChanges.MethodPathChange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IncrementalChangeDetector {

    private IncrementalChangeDetector() {
    }

    static IncrementalChanges detect(
            AnalyzerState previous,
            List<FileMetric> currentFiles,
            boolean hadPreviousScan,
            Map<String, String> semanticHashByPath) {
        if (!hadPreviousScan || previous == null || previous.files == null || previous.files.isEmpty()) {
            return IncrementalChanges.empty();
        }

        Map<String, AnalyzerState.FileState> prevByPath = previous.files;
        Set<String> prevPaths = prevByPath.keySet();
        Set<String> currPaths = new HashSet<>();
        for (FileMetric file : currentFiles) {
            currPaths.add(file.path());
        }

        List<FilePathChange> fileChanges = new ArrayList<>();
        List<MethodPathChange> methodChanges = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int modified = 0;
        int cosmetic = 0;
        int unchanged = 0;
        int methodsAdded = 0;
        int methodsRemoved = 0;
        int methodsModified = 0;

        for (String path : currPaths) {
            if (!prevPaths.contains(path)) {
                added++;
                fileChanges.add(new FilePathChange(path, ChangeKind.ADDED));
                countMethodsAs(methodChanges, currentFiles, path, ChangeKind.ADDED, null);
                methodsAdded += methodCountForFile(currentFiles, path);
            }
        }
        for (String path : prevPaths) {
            if (!currPaths.contains(path)) {
                removed++;
                fileChanges.add(new FilePathChange(path, ChangeKind.REMOVED));
                methodsRemoved += prevByPath.get(path).methods != null ? prevByPath.get(path).methods.size() : 0;
            }
        }

        for (FileMetric file : currentFiles) {
            AnalyzerState.FileState prev = prevByPath.get(file.path());
            if (prev == null) {
                continue;
            }
            String prevHash = prev.fileHash == null ? "" : prev.fileHash;
            String currHash = file.fileHash() == null ? "" : file.fileHash();
            if (prevHash.equals(currHash)) {
                unchanged++;
                continue;
            }
            String prevSemantic = prev.semanticFileHash == null ? "" : prev.semanticFileHash;
            String currSemantic = semanticHashByPath.getOrDefault(file.path(), "");
            if (!prevSemantic.isEmpty() && prevSemantic.equals(currSemantic)) {
                cosmetic++;
                fileChanges.add(new FilePathChange(file.path(), ChangeKind.COSMETIC));
                continue;
            }
            modified++;
            fileChanges.add(new FilePathChange(file.path(), ChangeKind.MODIFIED));
            MethodDiff diff = diffMethods(prev, file);
            methodsAdded += diff.added;
            methodsRemoved += diff.removed;
            methodsModified += diff.modified;
            methodChanges.addAll(diff.changes);
        }

        return new IncrementalChanges(true, added, removed, modified, cosmetic, unchanged,
                methodsAdded, methodsRemoved, methodsModified, fileChanges, methodChanges);
    }

    private record MethodDiff(int added, int removed, int modified, List<MethodPathChange> changes) {
    }

    private static MethodDiff diffMethods(AnalyzerState.FileState prev, FileMetric current) {
        Map<String, AnalyzerState.MethodState> prevMethods =
                prev.methods != null ? prev.methods : Map.of();
        Map<String, MethodMetric> currMethods = new HashMap<>();
        for (ClassMetric type : current.classes()) {
            for (MethodMetric method : type.methods()) {
                currMethods.put(type.name() + "#" + method.signature(), method);
            }
        }

        List<MethodPathChange> changes = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int modified = 0;

        for (Map.Entry<String, MethodMetric> entry : currMethods.entrySet()) {
            String key = entry.getKey();
            MethodMetric m = entry.getValue();
            String className = classNameFromKey(key);
            AnalyzerState.MethodState old = prevMethods.get(key);
            if (old == null) {
                added++;
                changes.add(new MethodPathChange(current.path(), className, m.signature(),
                        m.startLine(), m.endLine(), ChangeKind.ADDED));
                continue;
            }
            String oldHash = old.methodHash == null ? "" : old.methodHash;
            String newHash = m.methodHash() == null ? "" : m.methodHash();
            if (!oldHash.equals(newHash)) {
                modified++;
                changes.add(new MethodPathChange(current.path(), className, m.signature(),
                        m.startLine(), m.endLine(), ChangeKind.MODIFIED));
            }
        }
        for (String key : prevMethods.keySet()) {
            if (!currMethods.containsKey(key)) {
                removed++;
                changes.add(new MethodPathChange(current.path(), classNameFromKey(key),
                        signatureFromKey(key), 0, 0, ChangeKind.REMOVED));
            }
        }
        return new MethodDiff(added, removed, modified, changes);
    }

    private static void countMethodsAs(List<MethodPathChange> out, List<FileMetric> files, String path,
                                       ChangeKind kind, AnalyzerState.FileState prev) {
        for (FileMetric file : files) {
            if (!file.path().equals(path)) {
                continue;
            }
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    out.add(new MethodPathChange(path, type.name(), method.signature(),
                            method.startLine(), method.endLine(), kind));
                }
            }
            return;
        }
    }

    private static int methodCountForFile(List<FileMetric> files, String path) {
        for (FileMetric file : files) {
            if (file.path().equals(path)) {
                return file.methodCount();
            }
        }
        return 0;
    }

    private static String classNameFromKey(String classMethodKey) {
        int idx = classMethodKey.indexOf('#');
        return idx >= 0 ? classMethodKey.substring(0, idx) : classMethodKey;
    }

    private static String signatureFromKey(String classMethodKey) {
        int idx = classMethodKey.indexOf('#');
        return idx >= 0 ? classMethodKey.substring(idx + 1) : classMethodKey;
    }
}
