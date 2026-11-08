package com.scanner.exception.repo;

import com.scanner.exception.ScannerApp;
import com.scanner.exception.config.Config;
import com.scanner.exception.model.RepoInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Repoları sığ (--depth 1) olarak klonlar veya günceller. PATH'te git (2.31+) olmalıdır.
 * Token komut satırına yazılmaz; GIT_CONFIG_* ortam değişkenleriyle iletilir.
 *
 * git.sparse=true iken (varsayılan false) sadece taramada okunan dosyalar indirilir (.java, modül dosyaları,
 * EBML açıksa .ebml/.dsxml/processdefinition.xml): blob'suz partial clone + sparse checkout. Sunucu
 * partial clone desteklemiyorsa git normal klona döner; sadece diske yazılan dosyalar azalır.
 */
public final class GitRunner {

    private final Config cfg;

    public GitRunner(Config cfg) {
        this.cfg = cfg;
    }

    public void checkout(RepoInfo repo) throws IOException, InterruptedException {
        if (repo.cloneUrl == null || repo.cloneUrl.isEmpty()) {
            throw new IOException("Repo için clone adresi bulunamadı");
        }
        Path dir = cfg.workDir.resolve(safe(repo.projectKey)).resolve(safe(repo.slug)).toAbsolutePath();
        String url = repo.cloneUrl.replaceFirst("^(https?://)[^/@]+@", "$1");
        String branch = !repo.requestedBranch.isEmpty() ? repo.requestedBranch : cfg.branch;
        repo.missingBranch = "";

        if (Files.isDirectory(dir.resolve(".git"))) {
            String ref = branch.isEmpty() ? "HEAD" : branch;
            try {
                checkFilterSupport(run(dir, "git", "fetch", "--depth", "1", "--quiet", "origin", ref));
            } catch (IOException e) {
                if (!fallBack(repo, branch, e)) throw e;
                checkFilterSupport(run(dir, "git", "fetch", "--depth", "1", "--quiet", "origin", "HEAD"));
            }
            if (cfg.gitSparse) {
                // Sadece gerekli dosyalar; desenler değişmiş olabilir (ör. EBML açıldı), her seferinde yazılır
                writeSparsePatterns(dir, sparsePatterns());
                run(dir, "git", "reset", "--hard", "--quiet", "FETCH_HEAD");
                // Desenler değiştiyse (ör. EBML açıldı, önceden tam klonlanmıştı) çalışma klasörüne uygula
                run(dir, "git", "read-tree", "-mu", "HEAD");
            } else {
                run(dir, "git", "reset", "--hard", "--quiet", "FETCH_HEAD");
                Path sparseFile = dir.resolve(".git").resolve("info").resolve("sparse-checkout");
                if (Files.exists(sparseFile)) {
                    // Önceden git.sparse=true ile klonlanmış: tüm dosyaları geri getir
                    writeSparsePatterns(dir, Arrays.asList("/*"));
                    run(dir, "git", "read-tree", "-mu", "HEAD");
                    run(dir, "git", "config", "core.sparseCheckout", "false");
                    Files.delete(sparseFile);
                }
            }
        } else {
            try {
                cloneRepo(url, dir, branch);
            } catch (IOException e) {
                if (!fallBack(repo, branch, e)) throw e;
                cloneRepo(url, dir, "");
            }
            if (cfg.gitSparse) {
                writeSparsePatterns(dir, sparsePatterns());
                // Gerekli dosyaların içerikleri burada tek seferde indirilir
                run(dir, "git", "read-tree", "-mu", "HEAD");
            }
        }
        repo.localPath = dir;
        try {
            readHead(repo);
        } catch (IOException e) {
            throw new IOException("HEAD okunamadı (repo boş olabilir): " + e.getMessage(), e);
        }
        // Önceden klonlanmış repoda yerel dalın adı ilk klonlamadaki dal olarak kalır; taranan dal yazılır
        if (!repo.missingBranch.isEmpty()) {
            String def = remoteDefaultBranch(dir);
            if (def != null) repo.branch = def;
        } else if (!branch.isEmpty()) {
            repo.branch = branch;
        }
    }

    /** Uzak reponun varsayılan dalı ("ref: refs/heads/master	HEAD"); okunamazsa null */
    private String remoteDefaultBranch(Path dir) {
        try {
            for (String line : run(dir, "git", "ls-remote", "--symref", "origin", "HEAD").split("\n")) {
                if (line.startsWith("ref: refs/heads/")) {
                    return line.substring("ref: refs/heads/".length()).split("\\s")[0];
                }
            }
        } catch (IOException e) {
            // dal adı olmadan da taranabilir
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void cloneRepo(String url, Path dir, String branch) throws IOException, InterruptedException {
        if (Files.exists(dir)) deleteRecursively(dir);
        Files.createDirectories(dir.getParent());
        List<String> cmd = new ArrayList<String>(Arrays.asList(
                "git", "clone", "--depth", "1", "--single-branch", "--quiet"));
        if (cfg.gitSparse) {
            cmd.add("--filter=blob:none");
            cmd.add("--no-checkout");
        }
        if (!branch.isEmpty()) {
            cmd.add("--branch");
            cmd.add(branch);
        }
        cmd.add(url);
        cmd.add(dir.toString());
        checkFilterSupport(run(dir.getParent(), cmd.toArray(new String[0])));
    }

    /** git'in "dal yok" mesajları: clone "Remote branch X not found", fetch "couldn't find remote ref X" */
    private static final java.util.regex.Pattern MISSING_BRANCH = java.util.regex.Pattern.compile(
            "(?i)remote branch \\S+ not found|couldn't find remote ref");

    /**
     * İstenen dal repoda yoksa ve git.branch.fallback=true ise varsayılan dala düşülür: true döner, uyarı yazılır
     * ve repo.missingBranch doldurulur. Başka bir hata veya dal verilmemişse false (hata olduğu gibi atılır).
     */
    private boolean fallBack(RepoInfo repo, String branch, IOException e) {
        if (branch.isEmpty() || !cfg.gitBranchFallback || !MISSING_BRANCH.matcher(String.valueOf(e.getMessage())).find()) {
            return false;
        }
        repo.missingBranch = branch;
        ScannerApp.log("Uyarı: " + repo.id() + " reposunda '" + branch + "' dalı yok, varsayılan dal taranıyor "
                + "(git.branch.fallback=false ile bu repolar atlanır).");
        return true;
    }

    /** Taramada okunan dosyalar (JavaSourceScanner, ModuleResolver, EbmlScanner) */
    private List<String> sparsePatterns() {
        List<String> p = new ArrayList<String>(Arrays.asList(
                "*.java", "pom.xml", "build.gradle", "build.gradle.kts", "build.xml", ".project"));
        if (cfg.ebml.enabled) {
            p.addAll(Arrays.asList("*.ebml", "*.EBML", "*.dsxml", "*.DSXML", "processdefinition.xml"));
        }
        return p;
    }

    private void writeSparsePatterns(Path dir, List<String> patterns) throws IOException, InterruptedException {
        run(dir, "git", "config", "core.sparseCheckout", "true");
        Path info = dir.resolve(".git").resolve("info");
        Files.createDirectories(info);
        Files.write(info.resolve("sparse-checkout"),
                (String.join("\n", patterns) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static final java.util.concurrent.atomic.AtomicBoolean FILTER_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Sunucu partial clone desteklemiyorsa bir kez uyarır (git yine de tam klonlar) */
    private void checkFilterSupport(String gitOutput) {
        if (cfg.gitSparse && gitOutput.contains("filtering not recognized by server")
                && FILTER_WARNED.compareAndSet(false, true)) {
            ScannerApp.log("Uyarı: git sunucusu partial clone (--filter) desteklemiyor; repolar tam indiriliyor, "
                    + "diske sadece gerekli dosyalar yazılıyor.");
        }
    }

    /** Yerel moddaki klasörler için: git reposuysa dal ve commit bilgisini okur, değilse sessizce geçer. */
    public void readHeadQuietly(RepoInfo repo) {
        if (repo.localPath == null || !Files.exists(repo.localPath.resolve(".git"))) return;
        try {
            readHead(repo);
        } catch (Exception ignored) {
            // git bilgisi olmadan da taranabilir
        }
    }

    private void readHead(RepoInfo repo) throws IOException, InterruptedException {
        repo.commit = run(repo.localPath, "git", "rev-parse", "HEAD").trim();
        repo.branch = run(repo.localPath, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    private String run(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());

        Map<String, String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (!env.containsKey("GIT_SSH_COMMAND")) env.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");

        List<String[]> gitConfig = new ArrayList<String[]>();
        // source=git (GitHub vb.) için makinedeki git kimlik bilgileri / SSH anahtarı kullanılır
        String auth = cfg.source == Config.Source.GIT ? null : BitbucketClient.gitAuthHeader(cfg);
        if (auth != null && !"ssh".equals(cfg.cloneProtocol)) {
            gitConfig.add(new String[]{"http.extraHeader", "Authorization: " + auth});
        }
        if (cfg.sslInsecure) gitConfig.add(new String[]{"http.sslVerify", "false"});
        if (!gitConfig.isEmpty()) {
            env.put("GIT_CONFIG_COUNT", String.valueOf(gitConfig.size()));
            for (int i = 0; i < gitConfig.size(); i++) {
                env.put("GIT_CONFIG_KEY_" + i, gitConfig.get(i)[0]);
                env.put("GIT_CONFIG_VALUE_" + i, gitConfig.get(i)[1]);
            }
        }

        final Process p = pb.start();
        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (out) {
                        out.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // süreç sonlandığında akış kapanır
            }
        });
        reader.setDaemon(true);
        reader.start();

        if (!p.waitFor(cfg.gitTimeoutMinutes, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("git zaman aşımına uğradı: " + String.join(" ", cmd));
        }
        reader.join(5000);
        String text;
        synchronized (out) {
            text = out.toString();
        }
        if (p.exitValue() != 0) {
            throw new IOException("git " + cmd[1] + " hata kodu " + p.exitValue() + ": "
                    + BitbucketClient.abbreviate(text.trim(), 500));
        }
        return text;
    }

    public static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = new ArrayList<Path>();
            walk.forEach(paths::add);
            paths.sort(Comparator.reverseOrder());
            for (Path p : paths) {
                p.toFile().setWritable(true); // Windows'ta git nesneleri salt okunur olabilir
                Files.deleteIfExists(p);
            }
        }
    }
}
