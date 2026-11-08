package com.scanner.complexity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/** Dosya yoluna göre en yakın pom.xml / Gradle modül dizinini bulur. */
final class ModuleRootIndex {

    private final Path scanRoot;
    private final ConcurrentHashMap<Path, String> cache = new ConcurrentHashMap<>();

    private ModuleRootIndex(Path scanRoot) {
        this.scanRoot = scanRoot;
    }

    static ModuleRootIndex forScanRoot(Path scanRoot) {
        return new ModuleRootIndex(scanRoot.toAbsolutePath().normalize());
    }

    String moduleRoot(String relativeUnixPath) {
        if (relativeUnixPath == null || relativeUnixPath.isEmpty()) {
            return ".";
        }
        Path file = scanRoot.resolve(relativeUnixPath.replace("/", scanRoot.getFileSystem().getSeparator()));
        Path dir = file.getParent();
        if (dir == null) {
            return legacyFirstSegment(relativeUnixPath);
        }
        return cache.computeIfAbsent(dir, this::resolveModuleRoot);
    }

    private String resolveModuleRoot(Path fromDir) {
        Path current = fromDir;
        while (current != null && current.startsWith(scanRoot)) {
            if (isModuleRoot(current)) {
                Path rel = scanRoot.relativize(current);
                if (rel.toString().isEmpty()) {
                    return ".";
                }
                return rel.toString().replace('\\', '/');
            }
            current = current.getParent();
        }
        if (fromDir.startsWith(scanRoot)) {
            Path rel = scanRoot.relativize(fromDir);
            String first = firstPathSegment(rel.toString().replace('\\', '/'));
            return first.isEmpty() ? "." : first;
        }
        return legacyFirstSegment(fromDir.toString());
    }

    private static boolean isModuleRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                || Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    private static String legacyFirstSegment(String relativeUnixPath) {
        int slash = relativeUnixPath.indexOf('/');
        return slash > 0 ? relativeUnixPath.substring(0, slash) : relativeUnixPath;
    }

    private static String firstPathSegment(String relativeUnixPath) {
        int slash = relativeUnixPath.indexOf('/');
        return slash > 0 ? relativeUnixPath.substring(0, slash) : relativeUnixPath;
    }
}
