package com.scanner.exception.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanner.exception.config.Config;
import com.scanner.exception.model.RepoInfo;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bitbucket Server/Data Center (REST 1.0) ve Bitbucket Cloud (REST 2.0) üzerinden repo listesini alır. */
public final class BitbucketClient {

    private final Config cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public BitbucketClient(Config cfg) {
        this.cfg = cfg;
    }

    public List<RepoInfo> listRepositories() throws IOException {
        return cfg.source == Config.Source.CLOUD ? listCloud() : listServer();
    }

    // ------------------------------------------------------------------ Server / Data Center

    private List<RepoInfo> listServer() throws IOException {
        List<String> keys = new ArrayList<String>(cfg.projects);
        if (keys.isEmpty()) {
            for (JsonNode p : pagedServer("/rest/api/1.0/projects")) {
                keys.add(p.path("key").asText());
            }
        }
        // Projelerin repo listeleri paralel çekilir; sonuç proje sırasıyla birleştirilir
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(keys.size(), 8)));
        try {
            List<Future<List<RepoInfo>>> futures = new ArrayList<Future<List<RepoInfo>>>();
            for (final String key : keys) futures.add(pool.submit(() -> listServerProject(key)));
            List<RepoInfo> repos = new ArrayList<RepoInfo>();
            for (Future<List<RepoInfo>> f : futures) {
                try {
                    repos.addAll(f.get());
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof IOException) throw (IOException) c;
                    throw new IOException(String.valueOf(c), c);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Repo listesi alınırken kesildi", e);
                }
            }
            return repos;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<RepoInfo> listServerProject(String key) throws IOException {
        String linkName = "ssh".equals(cfg.cloneProtocol) ? "ssh" : "http";
        List<RepoInfo> repos = new ArrayList<RepoInfo>();
        for (JsonNode r : pagedServer("/rest/api/1.0/projects/" + RepoInfo.urlEncode(key) + "/repos")) {
            if (!cfg.includeArchived && r.path("archived").asBoolean(false)) continue;
            repos.add(new RepoInfo(Config.Source.SERVER,
                    r.path("project").path("key").asText(key),
                    r.path("project").path("name").asText(key),
                    r.path("slug").asText(),
                    r.path("name").asText(),
                    cloneLink(r.path("links").path("clone"), linkName),
                    firstHref(r.path("links").path("self"))));
        }
        return repos;
    }

    private List<JsonNode> pagedServer(String path) throws IOException {
        List<JsonNode> all = new ArrayList<JsonNode>();
        int start = 0;
        while (true) {
            JsonNode page = get(cfg.baseUrl + path + "?limit=100&start=" + start);
            for (JsonNode v : page.path("values")) all.add(v);
            if (page.path("isLastPage").asBoolean(true)) break;
            start = page.path("nextPageStart").asInt();
        }
        return all;
    }

    // ------------------------------------------------------------------ Cloud

    private List<RepoInfo> listCloud() throws IOException {
        String linkName = "ssh".equals(cfg.cloneProtocol) ? "ssh" : "https";
        List<RepoInfo> repos = new ArrayList<RepoInfo>();
        String url = cfg.baseUrl + "/2.0/repositories/" + RepoInfo.urlEncode(cfg.workspace) + "?pagelen=100";
        while (url != null && !url.isEmpty()) {
            JsonNode page = get(url);
            for (JsonNode r : page.path("values")) {
                String projectKey = r.path("project").path("key").asText("");
                if (!cfg.projects.isEmpty() && !cfg.projects.contains(projectKey)) continue;
                repos.add(new RepoInfo(Config.Source.CLOUD,
                        projectKey,
                        r.path("project").path("name").asText(projectKey),
                        r.path("slug").asText(),
                        r.path("name").asText(),
                        cloneLink(r.path("links").path("clone"), linkName),
                        r.path("links").path("html").path("href").asText("")));
            }
            url = page.path("next").asText(null);
        }
        return repos;
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode get(String url) throws IOException {
        for (int attempt = 1; ; attempt++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            if (cfg.sslInsecure && con instanceof HttpsURLConnection) {
                Insecure.apply((HttpsURLConnection) con);
            }
            con.setRequestProperty("Accept", "application/json");
            String auth = apiAuthHeader(cfg);
            if (auth != null) con.setRequestProperty("Authorization", auth);
            con.setConnectTimeout(30000);
            con.setReadTimeout(120000);

            int code = con.getResponseCode();
            if ((code == 429 || code == 503) && attempt < 6) {
                // Hız sınırı: Retry-After kadar ya da artan sürelerle bekleyip tekrar dene
                long waitSec = parseLong(con.getHeaderField("Retry-After"), attempt * 10L);
                con.disconnect();
                sleep(waitSec);
                continue;
            }
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code >= 400) {
                throw new IOException("Bitbucket " + code + " döndü (" + url + "): " + abbreviate(body, 300));
            }
            return mapper.readTree(body);
        }
    }

    static String apiAuthHeader(Config c) {
        return header(c.username, c.token);
    }

    static String gitAuthHeader(Config c) {
        return header(c.gitUsername.isEmpty() ? c.username : c.gitUsername, c.token);
    }

    private static String header(String user, String token) {
        if (token == null || token.isEmpty()) return null;
        if (user == null || user.isEmpty()) return "Bearer " + token;
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    private static String cloneLink(JsonNode links, String name) {
        for (JsonNode l : links) {
            if (name.equalsIgnoreCase(l.path("name").asText())) return l.path("href").asText();
        }
        return links.size() > 0 ? links.get(0).path("href").asText() : null;
    }

    private static String firstHref(JsonNode node) {
        if (node.isArray()) return node.size() > 0 ? node.get(0).path("href").asText("") : "";
        return node.path("href").asText("");
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void sleep(long seconds) throws IOException {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bekleme kesildi", e);
        }
    }

    public static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** ssl.insecure=true için sertifika doğrulamasını kapatır. Sadece geçici kullanım içindir. */
    private static final class Insecure {
        private static SSLSocketFactory factory;

        static synchronized void apply(HttpsURLConnection con) throws IOException {
            try {
                if (factory == null) {
                    TrustManager[] trustAll = {new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) { }
                        public void checkServerTrusted(X509Certificate[] c, String a) { }
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }};
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, trustAll, new SecureRandom());
                    factory = ctx.getSocketFactory();
                }
                con.setSSLSocketFactory(factory);
                con.setHostnameVerifier((host, session) -> true);
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }
    }
}
