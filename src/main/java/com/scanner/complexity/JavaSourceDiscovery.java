package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.FileError;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parser ve PMD taramalarında ortak .java dosya keşfi (include/exclude + build klasörleri). */
final class JavaSourceDiscovery {

    static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", ".idea", "target", "node_modules",
            "build", "out", "bin", "dist", ".gradle", "coverage",
            "generated", "generated-sources", "generated-test-sources",
            "__generated__", ".mvn", "vendor", "tmp");

    private JavaSourceDiscovery() {
    }

    record Result(List<Path> paths, int skippedByFilter, int skippedDirectorySubtrees) {
    }

    static Result collect(Path root, Path base, List<FileError> errors, ScanOptions scan) throws IOException {
        if (Files.isRegularFile(root)) {
            return new Result(List.of(root), 0, 0);
        }
        FileSystem fs = root.getFileSystem();
        PathGlobFilter filter = new PathGlobFilter(fs, scan.includeGlobs(), scan.excludeGlobs());
        List<Path> result = new ArrayList<>();
        int[] skipped = {0};
        int[] skippedIgnoredDirs = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    if (shouldSkipAsBuildOutputDirectory(root, dir,
                            scan.applyDefaultIgnoredDirectories())) {
                        skippedIgnoredDirs[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path probe = dir.resolve("__analyzer_probe__.java");
                    if (!filter.accept(root, probe)) {
                        skippedIgnoredDirs[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java")) {
                    if (filter.accept(root, file)) {
                        result.add(file);
                    } else {
                        skipped[0]++;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                errors.add(new FileError(relativePath(base, file), "Cannot access: " + exc.getMessage()));
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(result);
        return new Result(result, skipped[0], skippedIgnoredDirs[0]);
    }

    static String relativePath(Path base, Path file) {
        Path relative = base != null && file.startsWith(base) ? base.relativize(file) : file;
        return relative.toString().replace('\\', '/');
    }

    /** target/build/dist atlanır; src/main/java/.../build/util gibi paket parçaları kalır. */
    static boolean shouldSkipAsBuildOutputDirectory(Path scanRoot, Path dir, boolean applyDefaultIgnores) {
        if (!applyDefaultIgnores) {
            return false;
        }
        Path name = dir.getFileName();
        if (name == null || !IGNORED_DIRECTORIES.contains(name.toString())) {
            return false;
        }
        String relative = scanRoot.relativize(dir).toString().replace('\\', '/');
        if (isUnderJavaSourceTree(relative)) {
            return false;
        }
        Path parent = dir.getParent();
        return parent != null && isModuleBoundaryDirectory(scanRoot, parent);
    }

    private static boolean isModuleBoundaryDirectory(Path scanRoot, Path directory) {
        if (directory.equals(scanRoot)) {
            return true;
        }
        return Files.isRegularFile(directory.resolve("pom.xml"))
                || Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"))
                || Files.isDirectory(directory.resolve("src/main/java"))
                || Files.isDirectory(directory.resolve("src"));
    }

    private static boolean isUnderJavaSourceTree(String relativeUnixPath) {
        return relativeUnixPath.contains("/src/main/java/")
                || relativeUnixPath.contains("/src/test/java/")
                || relativeUnixPath.startsWith("src/main/java/")
                || relativeUnixPath.startsWith("src/test/java/");
    }
}
