package com.scanner.exception.repo;

import com.scanner.exception.config.Config;
import com.scanner.exception.model.RepoInfo;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * source=local için klasör yapısından repo listesi çıkarır. Desteklenen yapılar:
 *   kök/.git                    -> kökün kendisi tek repo
 *   kök/repo/.git               -> her alt klasör bir repo
 *   kök/PROJE/repo/.git         -> tarayıcının work.dir yapısı (ağa çıkmadan yeniden tarama)
 *   kök/pom.xml, kök/src ...    -> kökün kendisi tek proje (git olmasa da, ör. zip'ten açılmış)
 *   kök/klasor (git değil)      -> düz kaynak klasörü, repo gibi taranır
 */
public final class LocalRepos {

    private LocalRepos() { }

    public static List<RepoInfo> list(Config cfg) throws IOException {
        Path root = cfg.localDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("local.dir bulunamadı: " + root);
        String rootName = root.getFileName() == null ? "LOCAL" : root.getFileName().toString();

        List<RepoInfo> repos = new ArrayList<RepoInfo>();
        if (isGit(root) || isProject(root)) {
            repos.add(repo(rootName, root));
            return repos;
        }
        for (Path level1 : subdirs(root)) {
            if (isGit(level1)) {
                repos.add(repo(rootName, level1));
                continue;
            }
            List<Path> gitChildren = new ArrayList<Path>();
            for (Path c : subdirs(level1)) if (isGit(c)) gitChildren.add(c);
            if (gitChildren.isEmpty()) {
                repos.add(repo(rootName, level1));
            } else {
                for (Path c : gitChildren) repos.add(repo(level1.getFileName().toString(), c));
            }
        }
        return repos;
    }

    private static RepoInfo repo(String project, Path dir) {
        String name = dir.getFileName().toString();
        RepoInfo r = new RepoInfo(Config.Source.LOCAL, project, project, name, name, null, null);
        r.localPath = dir;
        return r;
    }

    private static final String[] PROJECT_MARKERS =
            {"pom.xml", "build.gradle", "build.gradle.kts", "build.xml", ".project", "src"};

    private static boolean isProject(Path dir) {
        for (String m : PROJECT_MARKERS) if (Files.exists(dir.resolve(m))) return true;
        return false;
    }

    private static boolean isGit(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }

    private static List<Path> subdirs(Path dir) throws IOException {
        List<Path> out = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p) && !p.getFileName().toString().startsWith(".")) out.add(p);
            }
        }
        Collections.sort(out);
        return out;
    }
}
