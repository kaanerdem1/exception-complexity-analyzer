package com.scanner.exception.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanner.exception.ScannerApp;
import com.scanner.exception.config.Config;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Taramaları sırayla (aynı anda tek tarama) arka planda çalıştırır. Klonlanan repolar work.dir'de
 * önbellek olarak kalır, her taramanın raporu output.dir/&lt;tarama numarası&gt; klasörüne yazılır.
 */
@Service
class ScanJobService {

    static final String JOB_FILE = "job.json";
    static final String LOG_FILE = "scan.log";
    static final String MASTER_REPORT = "exception_scan_report.xlsx";
    /** Veritabanı açıksa env.exception_scan_run.id (ör. 42), değilse zaman damgalı numara */
    private static final Pattern ID = Pattern.compile("[0-9]{1,18}|[0-9]{8}-[0-9]{6}-[0-9a-f]{4}");
    private static final Pattern SECRET = Pattern.compile("(?i).*(token|password|secret|key).*");
    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final ServerSettings settings;
    private final ObjectMapper json;
    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<String, ScanJob>();
    private final Map<String, Future<?>> pending = new ConcurrentHashMap<String, Future<?>>();
    // Tarayıcının logları ve klon klasörü paylaşıldığı için taramalar sırayla çalışır
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scan-runner");
        t.setDaemon(true);
        return t;
    });

    ScanJobService(ServerSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /** Önceki taramaları rapor klasöründen yükler; yarıda kalanları başarısız sayar. */
    @PostConstruct
    void loadPrevious() throws IOException {
        Path out = outputDir();
        Files.createDirectories(out);
        Files.createDirectories(Paths.get(settings.getWorkDir()));
        try (Stream<Path> dirs = Files.list(out)) {
            for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve(JOB_FILE))).collect(Collectors.toList())) {
                try {
                    // Eski sürümlerin yazdığı, artık olmayan alanlar (ör. request.calls) kaydı okunmaz yapmasın
                    ScanJob job = json.readerFor(ScanJob.class)
                            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(dir.resolve(JOB_FILE).toFile());
                    if (job.id == null || !job.id.equals(dir.getFileName().toString())) continue;
                    if (job.status == ScanJob.Status.QUEUED || job.status == ScanJob.Status.RUNNING) {
                        job.status = ScanJob.Status.FAILED;
                        job.error = "Sunucu tarama sırasında yeniden başladı";
                        job.finishedAt = Instant.now();
                        save(job);
                        if (job.scanRunId != null) {
                            // Kayıt tamamlanmadıysa (finished_at boş) yarım kalan taramanın kaydı silinir
                            try {
                                com.scanner.exception.db.PostgresExporter.deleteReservedRun(defaultConfig(), job.scanRunId);
                            } catch (Exception e) {
                                ScannerApp.log("Uyarı: yarım kalan taramanın kaydı silinemedi (scan_run_id="
                                        + job.scanRunId + "): " + e.getMessage());
                            }
                        }
                    }
                    jobs.put(job.id, job);
                } catch (IOException e) {
                    ScannerApp.log("Uyarı: " + dir.resolve(JOB_FILE) + " okunamadı: " + e.getMessage());
                }
            }
        }
        ScannerApp.log("Sunucu modu: ayar dosyası " + baseConfig().toAbsolutePath() + ", raporlar "
                + out.toAbsolutePath() + ", " + jobs.size() + " önceki tarama");
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }

    /** İsteği doğrular (hatalıysa IllegalArgumentException) ve sıraya koyar. */
    synchronized ScanJob submit(ScanRequest request, ScanJob.Trigger trigger) throws IOException {
        if (request == null) request = emptyRequest();
        Properties overrides = overrides(request);
        Config cfg = Config.load(baseConfig(), overrides); // ayar hatası varsa hemen 400 dönsün

        ScanJob job = new ScanJob();
        job.createdAt = Instant.now();
        job.id = null;
        job.scanRunId = reserveRun(cfg);
        if (job.scanRunId != null) {
            job.id = String.valueOf(job.scanRunId);
            if (jobs.containsKey(job.id) || Files.exists(jobDir(job.id))) {
                // Veritabanı sıfırlanıp id'ler baştan başladıysa eski rapor klasörüyle çakışmasın
                ScannerApp.log("Uyarı: " + job.id + " numaralı rapor klasörü zaten var; tarama numarası zaman damgalı verildi.");
                job.id = null;
            }
        }
        if (job.id == null) job.id = ID_TIME.format(job.createdAt) + "-" + UUID.randomUUID().toString().substring(0, 4);
        job.status = ScanJob.Status.QUEUED;
        job.trigger = trigger;
        job.request = request;
        Files.createDirectories(jobDir(job.id));
        save(job);
        jobs.put(job.id, job);
        pending.put(job.id, runner.submit(() -> execute(job, cfg)));
        return job;
    }

    /** Veritabanına yazılacak taramada tarama kaydını şimdiden açar; id tarama numarası olur. Açılamazsa null. */
    private static Long reserveRun(Config cfg) {
        if (!cfg.db.enabled || cfg.db.dryRun) return null;
        try {
            return com.scanner.exception.db.PostgresExporter.reserveRun(cfg);
        } catch (Exception e) {
            ScannerApp.log("Uyarı: tarama kaydı açılamadı, tarama numarası zaman damgalı verildi (scan_run_id tarama "
                    + "sonunda ayrıca oluşur): " + e.getMessage());
            return null;
        }
    }

    private void execute(ScanJob job, Config cfg) {
        synchronized (this) { // submit / delete ile yarışmasın
            pending.remove(job.id);
            if (!jobs.containsKey(job.id)) return;
            job.status = ScanJob.Status.RUNNING;
        }
        Path dir = jobDir(job.id);
        try (BufferedWriter log = Files.newBufferedWriter(dir.resolve(LOG_FILE), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            ScannerApp.logListener = line -> {
                try {
                    log.write(line);
                    log.newLine();
                    log.flush();
                } catch (IOException ignored) {
                    // log dosyasına yazılamasa da tarama sürer; satır pod loglarında da var
                }
            };
            job.startedAt = Instant.now();
            save(job);
            ScannerApp.log("Tarama başladı: " + job.id);
            job.summary = ScannerApp.scan(cfg, dir, job.scanRunId);
            job.status = ScanJob.Status.SUCCEEDED;
        } catch (Throwable e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            ScannerApp.log("HATA: tarama " + job.id + " başarısız: " + e);
            job.status = ScanJob.Status.FAILED;
            job.error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
        } finally {
            ScannerApp.logListener = null;
            job.finishedAt = Instant.now();
            try {
                save(job);
            } catch (IOException e) {
                ScannerApp.log("Uyarı: " + job.id + " durumu kaydedilemedi: " + e.getMessage());
            }
        }
    }

    /** Sırada bekleyen veya çalışan, verilen kaynaktan başlatılmış tarama var mı */
    boolean hasActive(ScanJob.Trigger trigger) {
        for (ScanJob j : jobs.values()) {
            if (j.trigger == trigger && (j.status == ScanJob.Status.QUEUED || j.status == ScanJob.Status.RUNNING)) {
                return true;
            }
        }
        return false;
    }

    List<ScanJob> list() {
        List<ScanJob> all = new ArrayList<ScanJob>(jobs.values());
        all.sort(Comparator.comparing((ScanJob j) -> j.createdAt).reversed());
        return all;
    }

    ScanJob get(String id) {
        ScanJob job = id == null ? null : jobs.get(id);
        if (job == null) throw new NoSuchElementException("Tarama bulunamadı: " + id);
        return job;
    }

    /** Sıradaki taramayı iptal eder veya biten taramayı raporlarıyla siler. Çalışan tarama silinemez. */
    synchronized void delete(String id) throws IOException {
        ScanJob job = get(id);
        if (job.status == ScanJob.Status.RUNNING) {
            throw new IllegalStateException("Çalışan tarama silinemez, bitmesini bekleyin: " + id);
        }
        Future<?> f = pending.remove(id);
        if (f != null) f.cancel(false);
        if (job.status == ScanJob.Status.QUEUED && job.scanRunId != null) {
            try {
                com.scanner.exception.db.PostgresExporter.deleteReservedRun(Config.load(baseConfig(), overrides(job.request)),
                        job.scanRunId);
            } catch (Exception e) {
                ScannerApp.log("Uyarı: iptal edilen taramanın kaydı silinemedi (scan_run_id=" + job.scanRunId + "): " + e.getMessage());
            }
        }
        jobs.remove(id);
        deleteRecursively(jobDir(id));
    }

    /** Taramanın rapor klasöründeki dosyalar (klasöre göre göreli yol ve boyut) */
    List<ReportFile> files(String id) throws IOException {
        Path dir = jobDir(get(id).id);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().equals(JOB_FILE))
                    .map(f -> new ReportFile(dir.relativize(f).toString().replace('\\', '/'), size(f)))
                    .sorted(Comparator.comparing(ReportFile::path))
                    .collect(Collectors.toList());
        }
    }

    /** Rapor klasörü içindeki bir dosya; klasör dışına çıkan yollar reddedilir. */
    Path file(String id, String relative) {
        Path dir = jobDir(get(id).id).toAbsolutePath().normalize();
        Path f = dir.resolve(relative == null ? "" : relative).normalize();
        if (!f.startsWith(dir) || f.equals(dir) || f.getFileName().toString().equals(JOB_FILE)
                || !Files.isRegularFile(f)) {
            throw new NoSuchElementException("Dosya bulunamadı: " + relative);
        }
        return f;
    }

    /** Sunucudaki temel ayarlar; token / şifre gibi değerler maskelenir */
    Map<String, String> effectiveSettings() throws IOException {
        Path base = baseConfig();
        Properties p = Files.exists(base) ? Config.readProperties(base) : new Properties();
        p.setProperty("work.dir", settings.getWorkDir());
        p.setProperty("output.dir", settings.getOutputDir());
        Map<String, String> out = new java.util.TreeMap<String, String>();
        for (String k : p.stringPropertyNames()) {
            String v = p.getProperty(k);
            out.put(k, SECRET.matcher(k).matches() && !v.isEmpty() ? "******" : v);
        }
        if (System.getenv("BITBUCKET_TOKEN") != null) out.put("bitbucket.token", "****** (BITBUCKET_TOKEN)");
        if (System.getenv("DB_PASSWORD") != null) out.put("db.password", "****** (DB_PASSWORD)");
        return out;
    }

    /** Veritabanındaki bir taramadan üretilen Excel raporu */
    record GeneratedReport(String fileName, byte[] content) { }

    /**
     * Excel raporunu veritabanındaki tarama sonuçlarından üretir: project boşsa taramanın tamamı, repo boşsa
     * projenin tüm repoları. scanRunId verilmezse en son tarama (repo verildiyse o reponun başarıyla tarandığı).
     */
    GeneratedReport dbReport(String project, String repo, Long scanRunId) throws IOException, java.sql.SQLException {
        Config cfg = dbConfig();
        com.scanner.exception.db.DbReportReader.Report report =
                new com.scanner.exception.db.DbReportReader(cfg).read(project, repo, scanRunId);
        boolean allProjects = project == null || project.trim().isEmpty();
        boolean oneRepo = !allProjects && repo != null && !repo.trim().isEmpty();
        String scope = allProjects ? "Tüm projeler"
                : oneRepo ? report.results.get(0).repo.id() : report.results.get(0).repo.projectKey;
        String fileName = (allProjects ? "exception_scan_report"
                : "report_" + com.scanner.exception.repo.GitRunner.safe(scope.replace('/', '_')))
                + "_" + report.scanRunId + ".xlsx";
        Path tmp = Files.createTempFile("scan-report-", ".xlsx");
        try {
            new com.scanner.exception.report.ExcelReportWriter(cfg, java.util.Date.from(report.startedAt), true)
                    .write(tmp, "Exception Kullanım Taraması - " + scope + " (scan_run_id=" + report.scanRunId + ")",
                            report.results);
            return new GeneratedReport(fileName, Files.readAllBytes(tmp));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Projenin bir taramadaki (verilmezse en son taramadaki) repoları ve sayıları */
    List<com.scanner.exception.db.DbReportReader.RepoRow> dbRepos(String project, Long scanRunId)
            throws IOException, java.sql.SQLException {
        return new com.scanner.exception.db.DbReportReader(dbConfig()).repos(project, scanRunId);
    }

    private Config dbConfig() throws IOException {
        Config cfg = defaultConfig();
        if (!cfg.db.enabled) {
            throw new IllegalArgumentException("Rapor veritabanından üretilir: scanner.properties içinde db.enabled=true olmalı");
        }
        return cfg;
    }

    /** Boş istekle (otomatik taramadaki gibi) yapılacak taramanın ayarları */
    Config defaultConfig() throws IOException {
        return Config.load(baseConfig(), overrides(emptyRequest()));
    }

    private static ScanRequest emptyRequest() {
        return new ScanRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private Properties overrides(ScanRequest request) {
        Properties p = request.toOverrides();
        p.setProperty("work.dir", settings.getWorkDir());
        p.setProperty("output.dir", settings.getOutputDir());
        p.setProperty("console.limit", "0"); // bulunanlar raporda; pod loguna dökülmesin
        return p;
    }

    private Path baseConfig() {
        return Paths.get(settings.getConfig());
    }

    private Path outputDir() {
        return Paths.get(settings.getOutputDir());
    }

    private Path jobDir(String id) {
        if (!ID.matcher(id).matches()) throw new NoSuchElementException("Tarama bulunamadı: " + id);
        return outputDir().resolve(id);
    }

    private void save(ScanJob job) throws IOException {
        Path dir = jobDir(job.id);
        Path tmp = dir.resolve(JOB_FILE + ".tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), job);
        Files.move(tmp, dir.resolve(JOB_FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static long size(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(p);
        }
    }

    /** Rapor klasöründeki bir dosya */
    record ReportFile(String path, long size) { }
}
