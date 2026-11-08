package com.scanner.complexity;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Relative path glob include/exclude (monorepo filtreleri). */
final class PathGlobFilter {

    private final List<PathMatcher> includes;
    private final List<PathMatcher> excludes;

    PathGlobFilter(FileSystem fs, List<String> includeGlobs, List<String> excludeGlobs) {
        this.includes = compile(fs, includeGlobs);
        this.excludes = compile(fs, excludeGlobs);
    }

    boolean accept(Path scanRoot, Path file) {
        String relative = scanRoot.relativize(file).toString().replace('\\', '/');
        String relLower = relative.toLowerCase(Locale.ROOT);
        if (relLower.contains("ibatis")) {
            return false;
        }
        String fileName = file.getFileName() != null
                ? file.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (fileName.endsWith("stub.java")) {
            return false;
        }
        for (PathMatcher exclude : excludes) {
            if (matches(exclude, relative)) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (PathMatcher include : includes) {
            if (matches(include, relative)) {
                return true;
            }
        }
        return false;
    }

    /** Match relative path and root-anchored form ({@code /src/...}) for git-style globs. */
    static boolean matches(PathMatcher matcher, String relativeUnixPath) {
        if (matcher.matches(Path.of(relativeUnixPath))) {
            return true;
        }
        if (!relativeUnixPath.startsWith("/")) {
            return matcher.matches(Path.of("/" + relativeUnixPath));
        }
        return false;
    }

    private static List<PathMatcher> compile(FileSystem fs, List<String> globs) {
        Set<String> patterns = new LinkedHashSet<>();
        for (String glob : globs) {
            String trimmed = glob.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains("*") || trimmed.contains("?")) {
                patterns.add("glob:" + trimmed);
                if (trimmed.startsWith("**/")) {
                    patterns.add("glob:" + trimmed.substring(3));
                }
            } else {
                patterns.add("glob:**/" + trimmed + "/**");
                patterns.add("glob:" + trimmed + "/**");
            }
        }
        List<PathMatcher> matchers = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            matchers.add(fs.getPathMatcher(pattern));
        }
        return matchers;
    }
}
