package com.scanner.exception.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bir dosyanın ait olduğu modülü bulur: pom.xml / build.gradle / build.xml / .project bulunan en yakın üst klasör. */
final class ModuleResolver {

    static final String ROOT = "(kök)";
    private static final String[] BUILD_FILES = {"pom.xml", "build.gradle", "build.gradle.kts", "build.xml", ".project"};

    private ModuleResolver() { }

    static String moduleOf(Path root, Path dir, Map<Path, String> cache) {
        List<Path> visited = new ArrayList<Path>();
        String found = null;
        Path cur = dir;
        while (cur != null && cur.startsWith(root)) {
            String cached = cache.get(cur);
            if (cached != null) {
                found = cached;
                break;
            }
            visited.add(cur);
            if (hasBuildFile(cur)) {
                String rel = relative(root, cur);
                found = rel.isEmpty() ? ROOT : rel;
                break;
            }
            if (cur.equals(root)) break;
            cur = cur.getParent();
        }
        if (found == null) found = ROOT;
        for (Path v : visited) cache.put(v, found);
        return found;
    }

    private static boolean hasBuildFile(Path dir) {
        for (String b : BUILD_FILES) if (Files.exists(dir.resolve(b))) return true;
        return false;
    }

    static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
