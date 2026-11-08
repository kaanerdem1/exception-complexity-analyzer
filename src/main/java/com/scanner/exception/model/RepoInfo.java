package com.scanner.exception.model;

import com.scanner.exception.config.Config;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Path;

/** Taranacak bir repo ve tarama sırasında öğrenilen bilgiler (yerel klasör, dal, commit). */
public final class RepoInfo {

    public final Config.Source source;
    public final String projectKey;
    public final String projectName;
    public final String slug;
    public final String name;
    public final String cloneUrl;
    /** Server: .../projects/KEY/repos/slug/browse, Cloud: https://bitbucket.org/ws/slug */
    public final String webUrl;

    /** Bu repo için özel olarak istenen dal (ör. GitHub .../tree/develop adresinden) */
    public String requestedBranch = "";

    /** İstenen dal repoda bulunamadı ve varsayılan dal tarandıysa istenen dalın adı (git.branch.fallback) */
    public String missingBranch = "";

    public Path localPath;
    public String branch = "";
    public String commit = "";
    /** Klonlama / güncelleme ve tarama süreleri (log için) */
    public volatile long cloneMillis;
    public volatile long scanMillis;

    public RepoInfo(Config.Source source, String projectKey, String projectName, String slug,
             String name, String cloneUrl, String webUrl) {
        this.source = source;
        this.projectKey = projectKey;
        this.projectName = projectName;
        this.slug = slug;
        this.name = name;
        this.cloneUrl = cloneUrl;
        this.webUrl = webUrl;
    }

    public String id() {
        return projectKey + "/" + slug;
    }

    /** Dosyadaki satıra giden Bitbucket bağlantısı. Commit'e sabitlendiği için satır numaraları kaymaz. */
    public String fileLink(String relPath, int line) {
        if (webUrl == null || webUrl.isEmpty()) return null;
        String ref = !commit.isEmpty() ? commit : branch;
        String path = encodePath(relPath);
        boolean hasLine = line > 0;
        if (source == Config.Source.SERVER) {
            return webUrl + "/" + path + (ref.isEmpty() ? "" : "?at=" + urlEncode(ref)) + (hasLine ? "#" + line : "");
        }
        if (source == Config.Source.CLOUD) {
            return webUrl + "/src/" + (ref.isEmpty() ? "HEAD" : urlEncode(ref)) + "/" + path
                    + (hasLine ? "#lines-" + line : "");
        }
        if (source == Config.Source.GIT && !ref.isEmpty()) {
            String anchor = hasLine ? "#L" + line : "";
            if (webUrl.contains("github")) return webUrl + "/blob/" + urlEncode(ref) + "/" + path + anchor;
            if (webUrl.contains("gitlab")) return webUrl + "/-/blob/" + urlEncode(ref) + "/" + path + anchor;
        }
        return null;
    }

    /** Satır numarası olmadan dosyanın kendisine giden bağlantı */
    public String fileLink(String relPath) {
        return fileLink(relPath, 0);
    }

    private static String encodePath(String rel) {
        StringBuilder sb = new StringBuilder();
        for (String seg : rel.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(urlEncode(seg));
        }
        return sb.toString();
    }

    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
