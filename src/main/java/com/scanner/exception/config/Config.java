package com.scanner.exception.config;

import com.scanner.exception.model.RepoInfo;
import com.scanner.exception.scan.UsagePattern;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** scanner.properties dosyasını okur. Token gibi gizli değerler ortam değişkeninden de verilebilir. */
public final class Config {

    public enum Source { SERVER, CLOUD, GIT, LOCAL }

    public final Source source;
    public final String baseUrl;
    public final String workspace;
    public final String username;
    public final String token;
    public final String gitUsername;
    public final Set<String> projects;
    public final Set<String> includeRepos;
    public final Set<String> excludeRepos;
    public final boolean includeArchived;
    public final String cloneProtocol;
    public final String branch;
    /** true: git.branch repoda yoksa varsayılan dal taranır (git.branch.fallback) */
    public final boolean gitBranchFallback;
    public final boolean sslInsecure;
    public final int gitTimeoutMinutes;
    public final Path workDir;
    public final Path outputDir;
    public final Path localDir;
    public final List<String> gitUrls;
    public final List<String> exceptionClasses;
    public final Set<String> ignoreClasses;
    public final boolean lenientMatch;
    public final Set<String> excludeDirs;
    public final Charset fallbackCharset;
    public final int threads;
    public final int gitThreads;
    public final boolean gitSparse;
    public final boolean perProjectReports;
    public final List<UsagePattern> patterns;
    public final boolean throwOnly;
    /** true: alt sınıflar tüm repolardan birlikte çıkarılır, bir repodaki alt sınıf diğerlerinde de aranır */
    public final boolean globalHierarchy;
    /** false: hiç Excel raporu yazılmaz (report.excel) */
    public final boolean reportExcel;
    /** Excel'de "Yutulan Exception'lar" sayfası (report.swallowed) */
    public final boolean reportSwallowed;
    /** Veritabanına (db.swallowed.table) yazılacak hata yutan catch blokları: scan.swallowed.scope */
    public final SwallowedScope swallowedScope;
    /** Log nesnesi / log metodu sayılan adlar (scan.swallowed.log.names), büyük/küçük harf duyarsız */
    public final List<String> swallowedLogNames;

    public enum SwallowedScope {
        /** Aranan exception'ı (veya repodaki alt sınıfını) yakalayanlar */
        TARGET,
        /** Tipine bakmadan hata yutan tüm catch blokları; içinde catch geçen her dosya ayrıştırılır */
        ALL,
        /** Hata yutan catch aranmaz */
        OFF
    }
    public final int consoleLimit;
    public final int maxFileKb;
    public final Db db;
    public final Ebml ebml;
    public final Complexity complexity;

    /** Ekran (.ebml), region (.ebml), Jasper rapor (.dsxml) ve process envanteri ayarları (ebml.*) */
    public static final class Ebml {

        public enum ProjectNameSource { REPO, REPO_SLUG, MODULE, BITBUCKET_PROJECT }

        public final boolean enabled;
        public final String regionPrefix;
        public final List<String> regionPackage;
        public final List<String> pagePackage;
        public final List<String> popupPackage;
        public final List<String> reportPackage;
        public final List<String> sourceRoots;
        public final boolean fileNameWithExtension;
        public final ProjectNameSource projectNameSource;
        public final boolean projectCaseInsensitive;
        public final boolean insertUnmatched;
        public final String projectTable;
        public final String projectIdColumn;
        public final String projectNameColumn;
        public final String screenTable;
        public final String popupTable;
        public final String regionTable;
        public final String reportTable;
        public final String processDir;
        public final String processTable;
        // ---- Mevcut ekran / popup / rapor / process tablolarında project_id güncellemesi
        public final boolean updateExisting;
        public final boolean updateOnlyEmpty;
        public final String existingProjectColumn;
        public final String existingScreenTable;
        public final String existingScreenNameColumn;
        public final String existingScreenTypeColumn;
        public final String existingScreenTypePage;
        public final String existingScreenTypeRegion;
        public final String existingPopupTable;
        public final String existingPopupNameColumn;
        public final String existingReportTable;
        public final String existingReportNameColumn;
        public final String existingProcessTable;
        public final String existingProcessNoColumn;
        public final String existingProcessNameColumn;

        Ebml(Properties p) {
            enabled = Boolean.parseBoolean(get(p, "ebml.enabled", "false"));
            regionPrefix = get(p, "ebml.region.prefix", "RG_");
            regionPackage = segments(get(p, "ebml.region.package", "ebml.region"));
            pagePackage = segments(get(p, "ebml.page.package", "ebml.page"));
            popupPackage = segments(get(p, "ebml.popup.package", "ebml.popup"));
            reportPackage = segments(get(p, "ebml.report.package", "ebml.report"));
            processDir = get(p, "ebml.process.dir", "process").trim();
            sourceRoots = list(get(p, "ebml.source.roots",
                    "src/main/java,src/main/resources,src/java,src/resources,JavaSource,source,resources,src"));
            fileNameWithExtension = Boolean.parseBoolean(get(p, "ebml.file.name.with.extension", "true"));
            projectNameSource = ProjectNameSource.valueOf(
                    get(p, "ebml.project.name.from", "REPO").toUpperCase(Locale.ROOT));
            projectCaseInsensitive = Boolean.parseBoolean(get(p, "ebml.project.case.insensitive", "true"));
            insertUnmatched = Boolean.parseBoolean(get(p, "ebml.insert.unmatched", "true"));
            String schema = get(p, "db.schema", "env");
            projectTable = Db.ident(get(p, "db.project.table", schema + ".project"), "db.project.table");
            projectIdColumn = Db.ident(get(p, "db.project.id.column", "id"), "db.project.id.column");
            projectNameColumn = Db.ident(get(p, "db.project.name.column", "project_name"), "db.project.name.column");
            screenTable = Db.ident(get(p, "db.screen.table", schema + ".all_screens"), "db.screen.table");
            popupTable = Db.ident(get(p, "db.popup.table", schema + ".all_popups"), "db.popup.table");
            regionTable = Db.ident(get(p, "db.region.table", schema + ".all_regions"), "db.region.table");
            reportTable = Db.ident(get(p, "db.report.table", schema + ".all_reports"), "db.report.table");
            processTable = Db.ident(get(p, "db.process.table", schema + ".all_processes"), "db.process.table");
            updateExisting = Boolean.parseBoolean(get(p, "ebml.update.existing", "true"));
            updateOnlyEmpty = Boolean.parseBoolean(get(p, "ebml.update.only.empty", "false"));
            existingProjectColumn = Db.ident(get(p, "db.existing.project.column", "project_id"), "db.existing.project.column");
            existingScreenTable = Db.ident(get(p, "db.existing.screen.table", schema + ".screen"), "db.existing.screen.table");
            existingScreenNameColumn = Db.ident(get(p, "db.existing.screen.name.column", "name"), "db.existing.screen.name.column");
            existingScreenTypeColumn = Db.ident(get(p, "db.existing.screen.type.column", "page_type"), "db.existing.screen.type.column");
            existingScreenTypePage = get(p, "db.existing.screen.type.page", "page").trim();
            existingScreenTypeRegion = get(p, "db.existing.screen.type.region", "region").trim();
            existingPopupTable = Db.ident(get(p, "db.existing.popup.table", schema + ".popup"), "db.existing.popup.table");
            existingPopupNameColumn = Db.ident(get(p, "db.existing.popup.name.column", "popup_name"), "db.existing.popup.name.column");
            existingReportTable = Db.ident(get(p, "db.existing.report.table", schema + ".report"), "db.existing.report.table");
            existingReportNameColumn = Db.ident(get(p, "db.existing.report.name.column", "report_name"), "db.existing.report.name.column");
            existingProcessTable = Db.ident(get(p, "db.existing.process.table", schema + ".process"), "db.existing.process.table");
            existingProcessNoColumn = Db.ident(get(p, "db.existing.process.no.column", "no"), "db.existing.process.no.column");
            existingProcessNameColumn = Db.ident(get(p, "db.existing.process.name.column", "name"), "db.existing.process.name.column");
        }

        /** "ebml.region" -> [ebml, region] (küçük harf) */
        private static List<String> segments(String pkg) {
            List<String> out = new ArrayList<String>();
            for (String s : pkg.split("[./]")) {
                if (!s.trim().isEmpty()) out.add(s.trim().toLowerCase(Locale.ROOT));
            }
            return Collections.unmodifiableList(out);
        }
    }

    /**
     * Kod karmaşıklığı analizi (complexity.*): exception taramasından sonra klonlanmış repolar üzerinde çalışır.
     * Tarama komut satırından, Swagger / REST'ten veya otomatik olarak başlatılmış olsun, complexity.enabled belirler.
     */
    public static final class Complexity {
        public final boolean enabled;
        /** PARSER (JavaParser, tam metrik), PMD (sadece PMD) veya BOTH */
        public final com.scanner.complexity.ComplexityRunner.Engine engine;
        public final com.github.javaparser.ParserConfiguration.LanguageLevel languageLevel;
        public final Charset charset;
        /** Raporlardaki en riskli metot sayısı */
        public final int top;
        /** true: JSON'da her metot (varsayılan); false: sadece dosya özetleri ve en riskli metotlar (sadece parser yolu) */
        public final boolean fullDetail;
        public final boolean duplicates;
        /** PMD CPD ile benzer (birebir olmayan) kod tespiti; tüm dosyaların token'larını bellekte tutar */
        public final boolean nearDuplicates;
        /** Bir repoda aynı anda ayrıştırılan dosya sayısı; 0 = işlemci sayısı kadar (bellek yetmezse düşürün) */
        public final int workers;
        /** JSON'un yanında okunabilir Markdown raporu da yazılsın mı */
        public final boolean markdown;
        /** Boş: yolun varsayılanı (parser: enterprise-java, pmd: enterprise-java-pmd) */
        public final String riskProfile;
        /** Repo başına analyzer-state.json (rapor klasöründe *-parser-analyzer-state.json / *-pmd-analyzer-state.json) */
        public final boolean incrementalCache;
        /** true: önceki state yok sayılır, koşu sonunda yeniden yazılır */
        public final boolean freshCache;
        /** Tarihli rapor klasörünün dışında; taramalar arası kalıcı (varsayılan output.dir/analyzer-cache) */
        public final Path cacheDirectory;
        public final List<String> include;
        public final List<String> exclude;
        /** JSON metot listesi ve DB ile aynı taban (db.method.risk.min.score). */
        public final double methodRiskMinScore;

        Complexity(Properties p) {
            enabled = Boolean.parseBoolean(get(p, "complexity.enabled", "false"));
            String eng = get(p, "complexity.engine", "pmd").trim().toUpperCase(Locale.ROOT);
            try {
                engine = com.scanner.complexity.ComplexityRunner.Engine.valueOf(eng);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("complexity.engine parser, pmd veya both olmalı: " + eng);
            }
            String level = get(p, "complexity.language.level", "JAVA_17").trim().toUpperCase(Locale.ROOT);
            languageLevel = com.github.javaparser.ParserConfiguration.LanguageLevel.valueOf(
                    level.startsWith("JAVA_") ? level : "JAVA_" + level);
            charset = Charset.forName(get(p, "complexity.encoding", "UTF-8").trim());
            top = Integer.parseInt(get(p, "complexity.top", "20").trim());
            String detail = get(p, "complexity.detail", "full").trim().toLowerCase(Locale.ROOT);
            if (!detail.equals("full") && !detail.equals("summary")) {
                throw new IllegalArgumentException("complexity.detail full veya summary olmalı: " + detail);
            }
            fullDetail = detail.equals("full");
            duplicates = Boolean.parseBoolean(get(p, "complexity.duplicates", "false"));
            nearDuplicates = Boolean.parseBoolean(get(p, "complexity.duplicates.near", "false"));
            markdown = Boolean.parseBoolean(get(p, "complexity.markdown", "false"));
            workers = Integer.parseInt(get(p, "complexity.workers", "0").trim());
            if (workers < 0) throw new IllegalArgumentException("complexity.workers 0 veya pozitif olmalı: " + workers);
            riskProfile = get(p, "complexity.risk.profile", "").trim();
            incrementalCache = Boolean.parseBoolean(get(p, "complexity.cache.enabled", "true"));
            freshCache = Boolean.parseBoolean(get(p, "complexity.cache.fresh", "false"));
            String cacheDir = get(p, "complexity.cache.dir", "").trim();
            cacheDirectory = cacheDir.isEmpty()
                    ? Paths.get(get(p, "output.dir", "./scanReports")).resolve("analyzer-cache")
                    : Paths.get(cacheDir);
            include = list(get(p, "complexity.include", ""));
            exclude = list(get(p, "complexity.exclude",
                    "**/src/test/**,**/*ibatis*,**/ibatis/**,**/*Stub.java"));
            methodRiskMinScore = Double.parseDouble(get(p, "db.method.risk.min.score", "30").trim());
            if (methodRiskMinScore < 0) {
                throw new IllegalArgumentException("db.method.risk.min.score negatif olamaz: " + methodRiskMinScore);
            }
        }
    }

    /** PostgreSQL envanter eşleştirmesi ve kayıt ayarları (db.*) */
    public static final class Db {
        public final boolean enabled;
        public final boolean dryRun;
        public final String url;
        public final String user;
        public final String password;
        public final String classTable;
        public final String classIdColumn;
        public final String classFqcnColumn;
        public final String classFilter;
        public final String methodTable;
        public final String methodIdColumn;
        public final String methodClassColumn;
        public final String methodNameColumn;
        public final String methodSignatureColumn;
        public final String usageTable;
        public final String typeTable;
        public final String runTable;
        public final String errorTable;
        public final boolean caseInsensitive;
        public final List<String> constructorNames;
        public final boolean insertUnmatched;
        public final String swallowedTable;
        /** Bu orandan düşük benzerlik grupları method_similarity'ye yazılmaz (tablonun alt sınır kısıtıyla aynı tutulur) */
        public final int similarityMinPercent;
        /** Bu skorun altındaki metotlar method_risk_analyze'e yazılmaz (DÜŞÜK bandı: &lt; 30) */
        public final double methodRiskMinScore;
        /** Taramada taranan her repo için bir satır (rapor API'si buradan okur) */
        public final String repoTable;
        /** Kod karmaşıklığı: metot bazlı risk ve kopya / benzer kod grupları (complexity.enabled=true ile) */
        public final String methodRiskTable;
        public final String methodSimilarityTable;
        /** Boşsa tarama kaydına "21.10.2026 11:50:34 tarihli tarama" yazılır */
        public final String note;
        /** Varsayılan açıklamadaki saatin saat dilimi */
        public final java.time.ZoneId noteZone;
        /** Otomatik tarama ön koşulu: deep-scanner'ın çalışma kayıtları (db.deep.scan.*) */
        public final String deepScanTable;
        public final String deepScanStatusColumn;
        public final String deepScanFinishedColumn;
        public final String deepScanSuccessStatus;
        /** Son bu kadar gün içinde başarılı deep-scanner kaydı yoksa otomatik tarama yapılmaz; 0 kontrolü kapatır */
        public final int deepScanMaxAgeDays;

        Db(Properties p) {
            enabled = Boolean.parseBoolean(get(p, "db.enabled", "false"));
            dryRun = Boolean.parseBoolean(get(p, "db.dry.run", "false"));
            url = get(p, "db.url", "");
            user = firstNonEmpty(System.getenv("DB_USER"), get(p, "db.user", ""));
            password = firstNonEmpty(System.getenv("DB_PASSWORD"), get(p, "db.password", ""));
            String schema = get(p, "db.schema", "env");
            classTable = ident(get(p, "db.class.table", schema + ".java_class"), "db.class.table");
            classIdColumn = ident(get(p, "db.class.id.column", "id"), "db.class.id.column");
            classFqcnColumn = ident(get(p, "db.class.fqcn.column", "fqcn"), "db.class.fqcn.column");
            classFilter = get(p, "db.class.filter", "");
            methodTable = ident(get(p, "db.method.table", schema + ".java_method"), "db.method.table");
            methodIdColumn = ident(get(p, "db.method.id.column", "id"), "db.method.id.column");
            methodClassColumn = ident(get(p, "db.method.class.column", "class_id"), "db.method.class.column");
            methodNameColumn = ident(get(p, "db.method.name.column", "name"), "db.method.name.column");
            String sig = get(p, "db.method.signature.column", "");
            methodSignatureColumn = sig.isEmpty() ? null : ident(sig, "db.method.signature.column");
            usageTable = ident(get(p, "db.usage.table", schema + ".exception_usage"), "db.usage.table");
            typeTable = ident(get(p, "db.usage.type.table", schema + ".exception_usage_type"), "db.usage.type.table");
            runTable = ident(get(p, "db.run.table", schema + ".exception_scan_run"), "db.run.table");
            errorTable = ident(get(p, "db.error.table", schema + ".exception_scan_error"), "db.error.table");
            caseInsensitive = Boolean.parseBoolean(get(p, "db.fqcn.case.insensitive", "false"));
            constructorNames = list(get(p, "db.method.constructor.names", "<init>,{class}"));
            insertUnmatched = Boolean.parseBoolean(get(p, "db.insert.unmatched", "true"));
            swallowedTable = ident(get(p, "db.swallowed.table", schema + ".swallowed_exceptions"), "db.swallowed.table");
            repoTable = ident(get(p, "db.repo.table", schema + ".exception_scan_repo"), "db.repo.table");
            methodRiskTable = ident(get(p, "db.method.risk.table", schema + ".method_risk_analyze"), "db.method.risk.table");
            methodSimilarityTable = ident(get(p, "db.method.similarity.table", schema + ".method_similarity"),
                    "db.method.similarity.table");
            similarityMinPercent = Integer.parseInt(get(p, "db.method.similarity.min.percent", "51").trim());
            methodRiskMinScore = Double.parseDouble(get(p, "db.method.risk.min.score", "30").trim());
            if (methodRiskMinScore < 0) {
                throw new IllegalArgumentException("db.method.risk.min.score negatif olamaz: " + methodRiskMinScore);
            }
            note = get(p, "db.note", "");
            noteZone = java.time.ZoneId.of(get(p, "db.note.zone", "Europe/Istanbul"));
            deepScanTable = ident(get(p, "db.deep.scan.table", schema + ".scan_run"), "db.deep.scan.table");
            deepScanStatusColumn = ident(get(p, "db.deep.scan.status.column", "status"), "db.deep.scan.status.column");
            deepScanFinishedColumn = ident(get(p, "db.deep.scan.finished.column", "finished_at"),
                    "db.deep.scan.finished.column");
            deepScanSuccessStatus = get(p, "db.deep.scan.success.status", "SUCCESS").trim();
            deepScanMaxAgeDays = Integer.parseInt(get(p, "db.deep.scan.max.age.days", "1").trim());
            if (deepScanMaxAgeDays < 0) {
                throw new IllegalArgumentException("db.deep.scan.max.age.days negatif olamaz: " + deepScanMaxAgeDays);
            }
            if (enabled && url.isEmpty()) {
                throw new IllegalArgumentException("db.enabled=true için db.url gerekli. "
                        + "Örnek: db.url=jdbc:postgresql://localhost:5432/envanter");
            }
        }

        /** Tablo ve sütun adları SQL'e yazıldığı için sadece harf, rakam, _ ve şema noktasına izin verilir */
        static String ident(String value, String key) {
            if (!value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
                throw new IllegalArgumentException(key + " geçersiz: " + value + " (örnek: env.java_class)");
            }
            return value;
        }
    }

    private Config(Properties p) {
        // Locale.ROOT: Türkçe locale'de i/İ dönüşümü büyük-küçük harf çevirmeyi bozmasın diye
        source = Source.valueOf(get(p, "source", "server").toUpperCase(Locale.ROOT));
        baseUrl = stripTrailingSlash(get(p, "bitbucket.url",
                source == Source.CLOUD ? "https://api.bitbucket.org" : ""));
        workspace = get(p, "bitbucket.workspace", "");
        username = firstNonEmpty(System.getenv("BITBUCKET_USERNAME"), get(p, "bitbucket.username", ""));
        token = firstNonEmpty(System.getenv("BITBUCKET_TOKEN"), get(p, "bitbucket.token", ""));
        gitUsername = get(p, "git.username", "");
        projects = new LinkedHashSet<String>(list(get(p, "bitbucket.projects", "")));
        includeRepos = new LinkedHashSet<String>(list(get(p, "bitbucket.include.repos", "")));
        excludeRepos = new LinkedHashSet<String>(list(get(p, "bitbucket.exclude.repos", "")));
        includeArchived = Boolean.parseBoolean(get(p, "bitbucket.include.archived", "false"));
        cloneProtocol = get(p, "git.protocol", "http").toLowerCase(Locale.ROOT);
        branch = get(p, "git.branch", "");
        gitBranchFallback = Boolean.parseBoolean(get(p, "git.branch.fallback", "true"));
        sslInsecure = Boolean.parseBoolean(get(p, "ssl.insecure", "false"));
        gitTimeoutMinutes = Integer.parseInt(get(p, "git.timeout.minutes", "15"));
        workDir = Paths.get(get(p, "work.dir", "./scannedRepos"));
        outputDir = Paths.get(get(p, "output.dir", "./scanReports"));
        String local = get(p, "local.dir", "");
        localDir = local.isEmpty() ? null : Paths.get(local);
        gitUrls = list(get(p, "git.urls", ""));
        exceptionClasses = list(get(p, "exception.classes", ""));
        ignoreClasses = new LinkedHashSet<String>(list(get(p, "exception.ignore.classes", "")));
        lenientMatch = Boolean.parseBoolean(get(p, "match.lenient", "false"));
        excludeDirs = new LinkedHashSet<String>(list(get(p, "scan.exclude.dirs",
                "target,build,bin,out,classes,node_modules,generated,generated-sources")));
        fallbackCharset = Charset.forName(get(p, "scan.fallback.charset", "windows-1254"));
        threads = Math.max(1, Integer.parseInt(get(p, "threads", "4")));
        gitThreads = Math.max(1, Integer.parseInt(get(p, "git.threads", "8")));
        gitSparse = Boolean.parseBoolean(get(p, "git.sparse", "false"));
        perProjectReports = Boolean.parseBoolean(get(p, "report.per.project", "true"));
        patterns = UsagePattern.load(p);
        throwOnly = Boolean.parseBoolean(get(p, "scan.throw.only", "false"));
        globalHierarchy = Boolean.parseBoolean(get(p, "scan.global.hierarchy", "true"));
        reportExcel = Boolean.parseBoolean(get(p, "report.excel", "true"));
        reportSwallowed = Boolean.parseBoolean(get(p, "report.swallowed", "true"));
        String scope = get(p, "scan.swallowed.scope", "TARGET").toUpperCase(Locale.ROOT);
        try {
            swallowedScope = SwallowedScope.valueOf(scope);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scan.swallowed.scope TARGET, ALL veya OFF olmalı: " + scope);
        }
        swallowedLogNames = list(get(p, "scan.swallowed.log.names", "log,logger"));
        consoleLimit = Integer.parseInt(get(p, "console.limit", "200"));
        maxFileKb = Integer.parseInt(get(p, "scan.max.file.kb", "2048"));
        db = new Db(p);
        ebml = new Ebml(p);
        complexity = new Complexity(p);
        validate();
    }

    /**
     * Ayar dosyasını okur ve komut satırından gelen değerleri üzerine yazar.
     * Komut satırında desen verilmişse dosyadaki desenler yok sayılır.
     * Dosya yoksa ve komut satırı değerleri varsa varsayılanlarla devam edilir.
     */
    public static Config load(Path file, Properties overrides) throws IOException {
        Properties p = new Properties();
        if (file != null && Files.exists(file)) {
            p = readProperties(file);
        } else if (overrides.isEmpty()) {
            throw new IOException("Ayar dosyası bulunamadı: "
                    + (file == null ? "scanner.properties" : file.toAbsolutePath().toString())
                    + ". Parametreler için: java -jar system-code-analyzer-1.0.0.jar --help");
        }
        // Komut satırında desen verilmişse dosyadaki desenler yok sayılır
        boolean patternsFromCli = false;
        for (String k : overrides.stringPropertyNames()) if (k.startsWith("pattern.")) patternsFromCli = true;
        if (patternsFromCli) {
            for (String k : new ArrayList<String>(p.stringPropertyNames())) {
                if (k.startsWith("pattern.")) p.remove(k);
            }
        }
        p.putAll(overrides);
        return new Config(p);
    }

    /** Ayar dosyasını Properties olarak okur (kodlama tespitiyle). */
    public static Properties readProperties(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = new StringReader(readText(file))) {
            p.load(r);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ayar dosyası okunamadı (" + file.toAbsolutePath() + "): " + e.getMessage(), e);
        }
        return p;
    }

    /**
     * Ayar dosyasını önce UTF-8 olarak okur; geçersiz karakter varsa (ör. TextEdit ile farklı
     * kodlamada kaydedilmişse) Türkçe karakter setiyle okur.
     */
    private static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1254"));
        }
    }

    private void validate() {
        if (exceptionClasses.isEmpty() && !ebml.enabled && !collectAllSwallowed() && !complexity.enabled) {
            throw new IllegalArgumentException("Aranacak bir şey tanımlanmadı. Ayar dosyasında exception.classes "
                    + "boş ve komut satırında --class verilmedi. Ayar dosyasının doğru klasörde olduğunu "
                    + "ve exception.classes satırının dolu olduğunu kontrol edin. "
                    + "Örnek: --class com.firma.framework.CustomException (sadece ekran/rapor envanteri için --ebml, "
                    + "sadece hata yutan catch blokları için scan.swallowed.scope=ALL, sadece kod karmaşıklığı için "
                    + "complexity.enabled=true)");
        }
        for (String c : exceptionClasses) {
            if (!c.contains(".")) {
                throw new IllegalArgumentException(
                        "exception.classes paket adıyla birlikte (tam nitelikli) yazılmalı: " + c);
            }
        }
        switch (source) {
            case SERVER:
                if (baseUrl.isEmpty()) throw new IllegalArgumentException("source=server için bitbucket.url gerekli");
                break;
            case CLOUD:
                if (workspace.isEmpty()) throw new IllegalArgumentException("source=cloud için bitbucket.workspace gerekli");
                break;
            case GIT:
                if (gitUrls.isEmpty()) throw new IllegalArgumentException("source=git için git.urls gerekli");
                break;
            case LOCAL:
                if (localDir == null) throw new IllegalArgumentException("source=local için local.dir gerekli");
                break;
            default:
                break;
        }
    }

    /**
     * bitbucket.exclude.repos: "repo-slug", "PROJE/repo-slug" veya "*" joker karakterli
     * kalip ("z_atil_*", "nova-*", "PROJE/test-*"). Buyuk-kucuk harf duyarsiz.
     */
    /**
     * bitbucket.include.repos: verilirse sadece eşleşen repolar taranır (bitbucket.exclude.repos yine uygulanır).
     * "PROJE/kalıp" sadece o projeyi daraltır; listede adı geçmeyen projelerin tüm repoları alınır.
     * Proje adı olmayan kalıp ("core-*") tüm projeleri daraltır. Yazımı exclude ile aynı.
     */
    /** Hata yutan catch blokları veritabanına ve/veya Excel'e yazılır; ikisi de kapalıysa aranmaz */
    public boolean collectSwallowed() {
        return (db.enabled || (reportExcel && reportSwallowed)) && swallowedScope != SwallowedScope.OFF;
    }

    public boolean collectAllSwallowed() {
        return collectSwallowed() && swallowedScope == SwallowedScope.ALL;
    }

    public boolean isIncluded(RepoInfo r) {
        boolean restricted = false;
        for (String e : includeRepos) {
            int slash = e.indexOf('/');
            if (slash < 0) {
                restricted = true;
                if (matchesGlob(e, r.slug)) return true;
            } else if (matchesGlob(e.substring(0, slash), r.projectKey)) {
                restricted = true;
                if (matchesGlob(e, r.id())) return true;
            }
        }
        return !restricted;
    }

    /** bitbucket.include.repos içinde hiçbir repoyla eşleşmeyen kalıplar (yazım hatası uyarısı için) */
    public List<String> unmatchedIncludes(List<RepoInfo> repos) {
        List<String> out = new ArrayList<String>();
        for (String e : includeRepos) {
            boolean found = false;
            for (RepoInfo r : repos) {
                if (e.indexOf('/') < 0 ? matchesGlob(e, r.slug) : matchesGlob(e, r.id())) {
                    found = true;
                    break;
                }
            }
            if (!found) out.add(e);
        }
        return out;
    }

    public boolean isExcluded(RepoInfo r) {
        for (String e : excludeRepos) {
            if (matchesGlob(e, r.slug) || matchesGlob(e, r.id())) return true;
        }
        return false;
    }

    private static boolean matchesGlob(String pattern, String value) {
        if (value == null) return false;
        if (pattern.indexOf('*') < 0) return pattern.equalsIgnoreCase(value);
        StringBuilder rx = new StringBuilder();
        String[] parts = pattern.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) rx.append(".*");
            rx.append(java.util.regex.Pattern.quote(parts[i]));
        }
        return java.util.regex.Pattern.compile(rx.toString(), java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value).matches();
    }

    private static String get(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<String>();
        for (String s : value.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return Collections.unmodifiableList(out);
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a.trim() : b;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
