package com.scanner.exception.db;

import com.scanner.exception.config.Config;
import com.scanner.exception.model.CatchFinding;
import com.scanner.exception.model.EbmlFile;
import com.scanner.exception.model.RepoResult;
import com.scanner.exception.model.ScanError;
import com.scanner.exception.model.UsageFinding;
import com.scanner.exception.scan.UsagePattern;

import java.net.InetAddress;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * PostgreSQL'deki sınıf/metot envanterini okur ve tarama sonuçlarını kullanım tablosuna yazar.
 * Tüm kayıt tek transaction'da yapılır: yazma yarıda hata verirse hiçbir şey kaydedilmez.
 */
public final class PostgresExporter implements DbCatalog, AutoCloseable {

    private static final int CHUNK = 1000;
    private static final int BATCH = 500;

    private final Config cfg;
    private final Config.Db db;
    private final Connection con;

    public PostgresExporter(Config cfg) throws SQLException {
        this.cfg = cfg;
        this.db = cfg.db;
        this.con = connect(db);
        this.con.setAutoCommit(false);
    }

    static Connection connect(Config.Db db) throws SQLException {
        Properties props = new Properties();
        if (!db.user.isEmpty()) props.setProperty("user", db.user);
        if (!db.password.isEmpty()) props.setProperty("password", db.password);
        props.setProperty("ApplicationName", "system-code-analyzer");
        props.setProperty("connectTimeout", "20");
        return DriverManager.getConnection(db.url, props);
    }

    // =====================================================================
    //  Okuma
    // =====================================================================

    public Map<String, List<ClassRow>> findClasses(Collection<String> fqcns) throws SQLException {
        String column = db.caseInsensitive ? "lower(" + db.classFqcnColumn + ")" : db.classFqcnColumn;
        String sql = "SELECT " + db.classIdColumn + ", " + db.classFqcnColumn
                + " FROM " + db.classTable
                + " WHERE " + column + " = ANY(?)"
                + (db.classFilter.isEmpty() ? "" : " AND (" + db.classFilter + ")");

        Map<String, List<ClassRow>> out = new HashMap<String, List<ClassRow>>();
        List<String> all = new ArrayList<String>(fqcns);
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
            PreparedStatement ps = con.prepareStatement(sql);
            try {
                Array arr = con.createArrayOf("text", chunk.toArray());
                ps.setArray(1, arr);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        String fqcn = rs.getString(2);
                        String key = db.caseInsensitive ? fqcn.toLowerCase(Locale.ENGLISH) : fqcn;
                        List<ClassRow> list = out.get(key);
                        if (list == null) {
                            list = new ArrayList<ClassRow>();
                            out.put(key, list);
                        }
                        list.add(new ClassRow(rs.getLong(1), fqcn));
                    }
                } finally {
                    rs.close();
                }
                arr.free();
            } finally {
                ps.close();
            }
        }
        return out;
    }

    public Map<Long, List<MethodRow>> findMethods(Collection<Long> classIds) throws SQLException {
        String sql = "SELECT " + db.methodIdColumn + ", " + db.methodClassColumn + ", " + db.methodNameColumn
                + (db.methodSignatureColumn == null ? "" : ", " + db.methodSignatureColumn)
                + " FROM " + db.methodTable
                + " WHERE " + db.methodClassColumn + " = ANY(?)";

        Map<Long, List<MethodRow>> out = new HashMap<Long, List<MethodRow>>();
        List<Long> all = new ArrayList<Long>(classIds);
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<Long> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
            PreparedStatement ps = con.prepareStatement(sql);
            try {
                Array arr = con.createArrayOf("int8", chunk.toArray());
                ps.setArray(1, arr);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        long classId = rs.getLong(2);
                        List<MethodRow> list = out.get(Long.valueOf(classId));
                        if (list == null) {
                            list = new ArrayList<MethodRow>();
                            out.put(Long.valueOf(classId), list);
                        }
                        String sig = db.methodSignatureColumn == null ? null : rs.getString(4);
                        list.add(new MethodRow(rs.getLong(1), classId, rs.getString(3), sig));
                    }
                } finally {
                    rs.close();
                }
                arr.free();
            } finally {
                ps.close();
            }
        }
        return out;
    }

    // =====================================================================
    //  Yazma
    // =====================================================================

    public static final class InsertResult {
        public long runId;
        public int inserted;
        public int skipped;
        public int screens;
        public int popups;
        public int regions;
        public int reports;
        public int processes;
        public int ebmlSkipped;
        /** env.exception_scan_error'a yazılan hata sayısı; tablo yoksa -1 */
        public int errors;
        /** env.swallowed_exceptions'a yazılan hata yutan catch bloğu sayısı; tablo yoksa veya kapalıysa -1 */
        public int swallowed;
        public int swallowedSkipped;
        /** catch_kind'e göre yazılan sayılar */
        public final Map<String, Integer> swallowedByKind = new java.util.TreeMap<String, Integer>();
        /** false: tarama tablosunda swallowed_error_count sütunu yok, sayı yazılmadı */
        public boolean swallowedCountWritten;
        /** env.exception_scan_repo'ya yazılan repo sayısı; tablo yoksa -1 */
        public int repos;
        /** Mevcut ekran / popup / rapor / process tablolarındaki project_id güncellemesinin özeti */
        public final List<String> existing = new ArrayList<String>();
    }

    /**
     * @param startedAt taramanın gerçekten başladığı an (env.exception_scan_run.started_at). Kayıtlar tek
     *                  transaction'da yazıldığı için started_at veritabanındaki now() ile doldurulamaz:
     *                  now() transaction'ın başlangıcını döndürür.
     */
    public InsertResult insert(List<RepoResult> results, java.time.Instant startedAt) throws SQLException {
        return insert(results, startedAt, null);
    }

    /**
     * @param reservedRunId taramaya sıraya alınırken açılmış kayıt (reserveRun; sunucuda tarama numarası) varsa
     *                      o kayıt doldurulur, yoksa yeni kayıt açılır
     */
    public InsertResult insert(List<RepoResult> results, java.time.Instant startedAt, Long reservedRunId)
            throws SQLException {
        try {
            upsertTypes();
            InsertResult ir = new InsertResult();
            ir.runId = reservedRunId != null ? fillReservedRun(reservedRunId, results, startedAt)
                    : insertRun(results, startedAt);

            String sql = "INSERT INTO " + db.usageTable + " (scan_run_id, class_id, method_id, usage_type_code,"
                    + " match_status, match_note, project_key, repo, module, file_path, line_no, class_fqcn,"
                    + " method_signature, exception_class, error_code, message, usage_context, is_test,"
                    + " code_snippet, link) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            PreparedStatement ps = con.prepareStatement(sql);
            try {
                int pending = 0;
                for (RepoResult r : results) {
                    for (UsageFinding u : r.usages) {
                        if (!db.insertUnmatched && DbMatcher.CLASS_NOT_FOUND.equals(u.matchStatus)) {
                            ir.skipped++;
                            continue;
                        }
                        int i = 1;
                        ps.setLong(i++, ir.runId);
                        setLong(ps, i++, u.dbClassId);
                        setLong(ps, i++, u.dbMethodId);
                        ps.setString(i++, u.typeCode);
                        ps.setString(i++, u.matchStatus.isEmpty() ? DbMatcher.CLASS_NOT_FOUND : u.matchStatus);
                        ps.setString(i++, cut(u.matchNote, 1000));
                        ps.setString(i++, cut(u.projectKey, 100));
                        ps.setString(i++, cut(u.repo, 200));
                        ps.setString(i++, cut(u.module, 300));
                        ps.setString(i++, cut(u.file, 1000));
                        ps.setInt(i++, u.line);
                        ps.setString(i++, cut(u.className, 1000));
                        ps.setString(i++, cut(u.member, 1000));
                        ps.setString(i++, cut(u.targetClass, 300));
                        Integer code = toInt(u.idValue);
                        if (code == null) ps.setNull(i++, Types.INTEGER);
                        else ps.setInt(i++, code.intValue());
                        ps.setString(i++, u.message);
                        ps.setString(i++, u.context.name());
                        ps.setBoolean(i++, u.testCode);
                        ps.setString(i++, u.expression);
                        ps.setString(i++, cut(u.link, 2000));
                        ps.addBatch();
                        ir.inserted++;
                        if (++pending >= BATCH) {
                            ps.executeBatch();
                            pending = 0;
                        }
                    }
                }
                if (pending > 0) ps.executeBatch();
            } finally {
                ps.close();
            }

            if (cfg.ebml.enabled) insertEbml(results, ir);
            if (cfg.ebml.enabled && cfg.ebml.updateExisting) updateExisting(results, ir);
            ir.errors = insertErrors(results, ir.runId);
            insertSwallowed(results, ir);
            insertRepos(results, ir);

            // Sütun sonradan eklendi: SQL dosyasının eski sürümüyle kurulan tabloda yoksa yazılmaz
            boolean catchCount = ir.swallowed >= 0 && hasColumn(db.runTable, "swallowed_error_count");
            ir.swallowedCountWritten = catchCount;
            PreparedStatement fin = con.prepareStatement("UPDATE " + db.runTable
                    + " SET finished_at = clock_timestamp(), usage_count = ?"
                    + (catchCount ? ", swallowed_error_count = ?" : "")
                    + (cfg.ebml.enabled ? ", screen_count = ?, popup_count = ?, region_count = ?, report_count = ?,"
                    + " process_count = ?" : "")
                    + " WHERE id = ?");
            try {
                int i = 1;
                fin.setInt(i++, ir.inserted);
                if (catchCount) fin.setInt(i++, ir.swallowed);
                if (cfg.ebml.enabled) {
                    fin.setInt(i++, ir.screens);
                    fin.setInt(i++, ir.popups);
                    fin.setInt(i++, ir.regions);
                    fin.setInt(i++, ir.reports);
                    fin.setInt(i++, ir.processes);
                }
                fin.setLong(i, ir.runId);
                fin.executeUpdate();
            } finally {
                fin.close();
            }
            con.commit();
            return ir;
        } catch (SQLException e) {
            rollbackQuietly();
            throw unwrap(e);
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        }
    }

    /** Ayar dosyasındaki desenleri kullanım tipi tablosuna yazar (varsa günceller). */
    private void upsertTypes() throws SQLException {
        Map<String, Object[]> types = new LinkedHashMap<String, Object[]>();
        if (cfg.patterns.isEmpty() && !cfg.exceptionClasses.isEmpty()) {
            types.put(UsagePattern.ALL_TYPE, new Object[]{UsagePattern.ALL, "İncelenmeli", "*", "EXCEPTION", Boolean.TRUE});
        }
        for (UsagePattern p : cfg.patterns) {
            types.put(p.type, new Object[]{p.name, p.action, p.argsText, "EXCEPTION", Boolean.valueOf(p.legacy)});
        }
        String sql = "INSERT INTO " + db.typeTable
                + " (type_code, type_name, action, args_spec, source_kind, is_legacy, updated_at)"
                + " VALUES (?,?,?,?,?,?,now())"
                + " ON CONFLICT (type_code) DO UPDATE SET type_name = EXCLUDED.type_name,"
                + " action = EXCLUDED.action, args_spec = EXCLUDED.args_spec,"
                + " source_kind = EXCLUDED.source_kind, is_legacy = EXCLUDED.is_legacy, updated_at = now()";
        if (types.isEmpty()) return; // sadece ekran/rapor envanteri çıkarılıyor
        PreparedStatement ps = con.prepareStatement(sql);
        try {
            for (Map.Entry<String, Object[]> en : types.entrySet()) {
                Object[] v = en.getValue();
                ps.setString(1, en.getKey());
                ps.setString(2, cut((String) v[0], 200));
                ps.setString(3, cut((String) v[1], 200));
                ps.setString(4, cut((String) v[2], 500));
                ps.setString(5, (String) v[3]);
                ps.setBoolean(6, ((Boolean) v[4]).booleanValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } finally {
            ps.close();
        }
    }

    // =====================================================================
    //  Ekran, region, Jasper rapor ve process envanteri
    // =====================================================================

    /** Dosyaların ana proje adını env.project tablosunda arar ve project_id'yi doldurur. */
    public void resolveProjects(List<EbmlFile> files) throws SQLException {
        Config.Ebml eb = cfg.ebml;
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        for (EbmlFile f : files) {
            for (String n : f.projectNameCandidates) names.add(projectKey(n));
        }
        Map<String, List<Long>> found = new HashMap<String, List<Long>>();
        if (!names.isEmpty()) {
            String column = eb.projectCaseInsensitive ? "lower(" + eb.projectNameColumn + ")" : eb.projectNameColumn;
            String sql = "SELECT " + eb.projectIdColumn + ", " + eb.projectNameColumn + " FROM " + eb.projectTable
                    + " WHERE " + column + " = ANY(?) ORDER BY " + eb.projectIdColumn;
            PreparedStatement ps = con.prepareStatement(sql);
            try {
                Array arr = con.createArrayOf("text", names.toArray());
                ps.setArray(1, arr);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        String key = projectKey(rs.getString(2));
                        List<Long> ids = found.get(key);
                        if (ids == null) {
                            ids = new ArrayList<Long>();
                            found.put(key, ids);
                        }
                        ids.add(Long.valueOf(rs.getLong(1)));
                    }
                } finally {
                    rs.close();
                }
                arr.free();
            } finally {
                ps.close();
            }
        }
        for (EbmlFile f : files) {
            f.projectId = null;
            f.projectMatch = EbmlFile.PROJECT_NOT_FOUND;
            for (String n : f.projectNameCandidates) {
                List<Long> ids = found.get(projectKey(n));
                if (ids == null || ids.isEmpty()) continue;
                f.projectId = ids.get(0); // sorgu id sırasıyla döner: birden fazlaysa en küçüğü
                f.projectName = n;
                f.projectMatch = ids.size() == 1 ? EbmlFile.PROJECT_MATCHED : EbmlFile.PROJECT_AMBIGUOUS;
                break;
            }
        }
    }

    private String projectKey(String name) {
        String n = name == null ? "" : name.trim();
        return cfg.ebml.projectCaseInsensitive ? n.toLowerCase(Locale.ENGLISH) : n;
    }

    private void insertEbml(List<RepoResult> results, InsertResult ir) throws SQLException {
        Config.Ebml eb = cfg.ebml;
        String common = " (scan_run_id, project_id, project_name, project_match, repo, module, package_name,"
                + " file_name, file_path, link";
        PreparedStatement screens = con.prepareStatement("INSERT INTO " + eb.screenTable + common
                + ") VALUES (?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement popups = con.prepareStatement("INSERT INTO " + eb.popupTable + common
                + ") VALUES (?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement regions = con.prepareStatement("INSERT INTO " + eb.regionTable + common
                + ", match_rule) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement reports = con.prepareStatement("INSERT INTO " + eb.reportTable + common
                + ") VALUES (?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement processes = con.prepareStatement("INSERT INTO " + eb.processTable
                + " (scan_run_id, project_id, project_name, project_match, repo, module, process_id, process_name,"
                + " folder_name, file_path, link) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
        try {
            for (RepoResult r : results) {
                for (EbmlFile f : r.ebmlFiles) {
                    PreparedStatement ps;
                    switch (f.kind) {
                        case SCREEN: ps = screens; break;
                        case POPUP: ps = popups; break;
                        case REGION: ps = regions; break;
                        case REPORT: ps = reports; break;
                        case PROCESS: ps = processes; break;
                        default: continue; // sınıflandırılmayanlar sadece raporda
                    }
                    if (!eb.insertUnmatched && f.projectId == null) {
                        ir.ebmlSkipped++;
                        continue;
                    }
                    int i = 1;
                    ps.setLong(i++, ir.runId);
                    setLong(ps, i++, f.projectId);
                    ps.setString(i++, cut(f.projectName, 300));
                    ps.setString(i++, f.projectMatch.isEmpty() ? EbmlFile.PROJECT_NOT_FOUND : f.projectMatch);
                    ps.setString(i++, cut(f.repo, 200));
                    ps.setString(i++, cut(f.module, 300));
                    if (f.kind == EbmlFile.Kind.PROCESS) {
                        ps.setLong(i++, f.processId.longValue());
                        ps.setString(i++, f.processName.isEmpty() ? null : cut(f.processName, 500));
                        ps.setString(i++, cut(f.fileName, 500));
                        ps.setString(i++, cut(f.file, 1000));
                        ps.setString(i, cut(f.link, 2000));
                        ps.addBatch();
                        ir.processes++;
                        continue;
                    }
                    ps.setString(i++, cut(f.packageName, 1000));
                    ps.setString(i++, cut(f.fileName, 500));
                    ps.setString(i++, cut(f.file, 1000));
                    ps.setString(i++, cut(f.link, 2000));
                    if (f.kind == EbmlFile.Kind.REGION) ps.setString(i, f.rule);
                    ps.addBatch();
                    if (f.kind == EbmlFile.Kind.SCREEN) ir.screens++;
                    else if (f.kind == EbmlFile.Kind.POPUP) ir.popups++;
                    else if (f.kind == EbmlFile.Kind.REGION) ir.regions++;
                    else ir.reports++;
                }
            }
            screens.executeBatch();
            popups.executeBatch();
            regions.executeBatch();
            reports.executeBatch();
            processes.executeBatch();
        } finally {
            screens.close();
            popups.close();
            regions.close();
            reports.close();
            processes.close();
        }
    }

    // =====================================================================
    //  Mevcut ekran / popup / rapor / process tablolarında project_id güncellemesi
    // =====================================================================

    /** Bir hedef tablonun (env.screen page, env.popup, ...) güncelleme sonucu */
    private static final class Target {
        final String label;
        int files;
        int found;
        int ambiguous;
        int updated;
        String error;

        Target(String label) {
            this.label = label;
        }

        public String toString() {
            if (error != null) return label + ": atlandı (" + error + ")";
            return label + ": " + files + " dosya, " + found + " tanesi tabloda bulundu, " + updated + " satır güncellendi"
                    + (files - found - ambiguous > 0 ? ", " + (files - found - ambiguous) + " tanesi tabloda yok" : "")
                    + (ambiguous > 0 ? ", aynı adı farklı projelerde olan " + ambiguous + " dosya güncellenmedi" : "");
        }
    }

    /** Anahtar sütunu ve sırayla denenecek anahtarlar; dosyanın tabloda bulunması için biri yeterli */
    private interface Keys {
        List<String> of(EbmlFile f);
    }

    /**
     * Taramada bulunan dosyaların project_id değerini mevcut env.screen, env.popup, env.report ve
     * env.process tablolarına yazar. Her tablo ayrı savepoint'te güncellenir: tablo veya sütun yoksa
     * sadece o tablo atlanır, tarama kaydı yine yapılır.
     */
    private void updateExisting(List<RepoResult> results, InsertResult ir) throws SQLException {
        Config.Ebml eb = cfg.ebml;
        List<EbmlFile> screens = new ArrayList<EbmlFile>(), regions = new ArrayList<EbmlFile>(),
                popups = new ArrayList<EbmlFile>(), reports = new ArrayList<EbmlFile>(), processes = new ArrayList<EbmlFile>();
        for (RepoResult r : results) {
            for (EbmlFile f : r.ebmlFiles) {
                switch (f.kind) {
                    case SCREEN: screens.add(f); break;
                    case REGION: regions.add(f); break;
                    case POPUP: popups.add(f); break;
                    case REPORT: reports.add(f); break;
                    case PROCESS: processes.add(f); break;
                    default: break;
                }
            }
        }
        Keys fileName = new Keys() {
            public List<String> of(EbmlFile f) {
                String n = f.file.substring(f.file.lastIndexOf('/') + 1);
                List<String> k = new ArrayList<String>();
                k.add(n);
                if (n.lastIndexOf('.') > 0) k.add(n.substring(0, n.lastIndexOf('.')));
                return k;
            }
        };
        Keys processNo = new Keys() {
            public List<String> of(EbmlFile f) {
                List<String> k = new ArrayList<String>();
                k.add(String.valueOf(f.processId));
                if (!f.processCode.isEmpty()) k.add(f.processCode);
                return k;
            }
        };
        Keys processName = new Keys() {
            public List<String> of(EbmlFile f) {
                List<String> k = new ArrayList<String>();
                if (!f.processShortName.isEmpty()) {
                    k.add(f.processShortName);
                    k.add(f.processShortName + ".par");
                }
                return k;
            }
        };

        String screenLabel = eb.existingScreenTable + "." + eb.existingScreenNameColumn;
        ir.existing.add(update(new Target(screenLabel + " (" + eb.existingScreenTypeColumn + "=" + eb.existingScreenTypePage + ")"),
                screens, eb.existingScreenTable, eb.existingScreenNameColumn, eb.existingScreenTypeColumn,
                eb.existingScreenTypePage, fileName, null, null).toString());
        ir.existing.add(update(new Target(screenLabel + " (" + eb.existingScreenTypeColumn + "=" + eb.existingScreenTypeRegion + ")"),
                regions, eb.existingScreenTable, eb.existingScreenNameColumn, eb.existingScreenTypeColumn,
                eb.existingScreenTypeRegion, fileName, null, null).toString());
        ir.existing.add(update(new Target(eb.existingPopupTable + "." + eb.existingPopupNameColumn),
                popups, eb.existingPopupTable, eb.existingPopupNameColumn, null, null, fileName, null, null).toString());
        ir.existing.add(update(new Target(eb.existingReportTable + "." + eb.existingReportNameColumn),
                reports, eb.existingReportTable, eb.existingReportNameColumn, null, null, fileName, null, null).toString());
        ir.existing.add(update(new Target(eb.existingProcessTable + "." + eb.existingProcessNoColumn + " / "
                        + eb.existingProcessNameColumn),
                processes, eb.existingProcessTable, eb.existingProcessNoColumn, null, null, processNo,
                eb.existingProcessNameColumn, processName).toString());
    }

    /**
     * Dosyaları önce keyColumn ile, orada bulunamayanları (varsa) fallbackColumn ile eşleştirip
     * project_id'yi günceller. Karşılaştırma büyük/küçük harf ve baştaki/sondaki boşluklara duyarsızdır.
     */
    private Target update(Target t, List<EbmlFile> all, String table, String keyColumn, String typeColumn, String typeValue,
                          Keys keys, String fallbackColumn, Keys fallbackKeys) throws SQLException {
        List<EbmlFile> files = new ArrayList<EbmlFile>();
        for (EbmlFile f : all) if (f.projectId != null) files.add(f);
        t.files = files.size();
        if (files.isEmpty()) return t;

        Savepoint sp = con.setSavepoint();
        try {
            if (!exists(table)) {
                con.releaseSavepoint(sp);
                t.error = "tablo yok";
                return t;
            }
            java.util.Set<EbmlFile> ambiguous = new java.util.HashSet<EbmlFile>();
            List<EbmlFile> notFound = match(t, files, table, keyColumn, typeColumn, typeValue, keys, ambiguous);
            if (fallbackColumn != null && !notFound.isEmpty()) {
                match(t, notFound, table, fallbackColumn, typeColumn, typeValue, fallbackKeys, ambiguous);
            }
            t.ambiguous = ambiguous.size();
            con.releaseSavepoint(sp);
        } catch (SQLException e) {
            con.rollback(sp);
            String msg = String.valueOf(unwrap(e).getMessage());
            t.error = msg.split("\\R", 2)[0].trim();
            t.found = 0;
            t.updated = 0;
            t.ambiguous = 0;
        }
        return t;
    }

    /** Eşleştirip günceller; tabloda bulunamayan (ve belirsiz olmayan) dosyaları döndürür. */
    private List<EbmlFile> match(Target t, List<EbmlFile> files, String table, String column, String typeColumn,
                                 String typeValue, Keys keys, java.util.Set<EbmlFile> ambiguous) throws SQLException {
        // Anahtar -> projeler; aynı ad farklı projelerde geçiyorsa hangisi olduğu bilinemez, o ad güncellenmez
        Map<String, java.util.Set<Long>> byKey = new LinkedHashMap<String, java.util.Set<Long>>();
        Map<String, String> original = new HashMap<String, String>();
        for (EbmlFile f : files) {
            for (String k : keys.of(f)) {
                String key = k.trim().toLowerCase(Locale.ROOT);
                if (key.isEmpty()) continue;
                java.util.Set<Long> p = byKey.get(key);
                if (p == null) {
                    p = new java.util.LinkedHashSet<Long>();
                    byKey.put(key, p);
                    original.put(key, k.trim());
                }
                p.add(f.projectId);
            }
        }
        List<String> ks = new ArrayList<String>();
        List<Long> pids = new ArrayList<Long>();
        for (Map.Entry<String, java.util.Set<Long>> en : byKey.entrySet()) {
            if (en.getValue().size() != 1) continue;
            ks.add(original.get(en.getKey()));
            pids.add(en.getValue().iterator().next());
        }
        // Her iki tarafta da PostgreSQL lower() kullanılır: Türkçe karakterlerde Java ile farklı sonuç vermesin
        String cond = " lower(trim(t." + column + "::text)) = lower(v.k)"
                + (typeColumn == null ? "" : " AND lower(trim(t." + typeColumn + "::text)) = lower(?)");
        java.util.Set<String> found = new java.util.HashSet<String>();
        if (!ks.isEmpty()) {
            PreparedStatement q = con.prepareStatement("SELECT DISTINCT v.k FROM unnest(?::text[]) AS v (k)"
                    + " WHERE EXISTS (SELECT 1 FROM " + table + " t WHERE" + cond + ")");
            try {
                Array arr = con.createArrayOf("text", ks.toArray());
                q.setArray(1, arr);
                if (typeColumn != null) q.setString(2, typeValue);
                ResultSet rs = q.executeQuery();
                try {
                    while (rs.next()) found.add(rs.getString(1).trim().toLowerCase(Locale.ROOT));
                } finally {
                    rs.close();
                }
                arr.free();
            } finally {
                q.close();
            }

            String pc = cfg.ebml.existingProjectColumn;
            PreparedStatement u = con.prepareStatement("UPDATE " + table + " t SET " + pc + " = v.pid"
                    + " FROM unnest(?::text[], ?::bigint[]) AS v (k, pid) WHERE" + cond
                    + (cfg.ebml.updateOnlyEmpty ? " AND t." + pc + " IS NULL" : " AND t." + pc + " IS DISTINCT FROM v.pid"));
            try {
                Array ka = con.createArrayOf("text", ks.toArray());
                Array pa = con.createArrayOf("int8", pids.toArray());
                u.setArray(1, ka);
                u.setArray(2, pa);
                if (typeColumn != null) u.setString(3, typeValue);
                t.updated += u.executeUpdate();
                ka.free();
                pa.free();
            } finally {
                u.close();
            }
        }

        List<EbmlFile> notFound = new ArrayList<EbmlFile>();
        for (EbmlFile f : files) {
            boolean hit = false, amb = false;
            for (String k : keys.of(f)) {
                String key = k.trim().toLowerCase(Locale.ROOT);
                if (found.contains(key)) hit = true;
                java.util.Set<Long> p = byKey.get(key);
                if (p != null && p.size() > 1) amb = true;
            }
            if (hit) {
                t.found++;
                ambiguous.remove(f);
            } else if (amb) {
                ambiguous.add(f);
                notFound.add(f);
            } else {
                notFound.add(f);
            }
        }
        return notFound;
    }

    /**
     * Tarama hatalarını yazar. Tablo henüz oluşturulmamışsa (SQL dosyası yeni sürümle çalıştırılmamışsa)
     * kullanımların yazılması engellenmesin diye atlanır ve -1 döner.
     */
    private int insertErrors(List<RepoResult> results, long runId) throws SQLException {
        if (!exists(db.errorTable)) return -1;
        PreparedStatement ps = con.prepareStatement("INSERT INTO " + db.errorTable
                + " (scan_run_id, project_key, repo, stage, file_path, message) VALUES (?,?,?,?,?,?)");
        int count = 0;
        try {
            int pending = 0;
            for (RepoResult r : results) {
                for (ScanError e : r.errors) {
                    ps.setLong(1, runId);
                    ps.setString(2, cut(e.projectKey, 100));
                    ps.setString(3, cut(e.repo, 200));
                    ps.setString(4, e.stage.name());
                    ps.setString(5, e.file.isEmpty() ? null : cut(e.file, 1000));
                    ps.setString(6, e.message);
                    ps.addBatch();
                    count++;
                    if (++pending >= BATCH) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
            }
            if (pending > 0) ps.executeBatch();
        } finally {
            ps.close();
        }
        return count;
    }

    /**
     * Hata yutan (boş, sadece loglayan veya loglayıp return eden) catch bloklarını yazar. Tablo henüz oluşturulmamışsa kullanımların yazılması
     * engellenmesin diye atlanır, sayı -1 olur.
     */
    private void insertSwallowed(List<RepoResult> results, InsertResult ir) throws SQLException {
        if (cfg.swallowedScope == Config.SwallowedScope.OFF || !exists(db.swallowedTable)) {
            ir.swallowed = -1;
            return;
        }
        PreparedStatement ps = con.prepareStatement("INSERT INTO " + db.swallowedTable
                + " (scan_run_id, class_id, method_id, match_status, match_note, project_key, repo, file_path, line_no,"
                + " class_fqcn, method_signature, exception_class, catch_kind, link) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        try {
            int pending = 0;
            for (RepoResult r : results) {
                for (CatchFinding c : r.swallowed) {
                    if (!db.insertUnmatched && DbMatcher.CLASS_NOT_FOUND.equals(c.matchStatus)) {
                        ir.swallowedSkipped++;
                        continue;
                    }
                    int i = 1;
                    ps.setLong(i++, ir.runId);
                    setLong(ps, i++, c.dbClassId);
                    setLong(ps, i++, c.dbMethodId);
                    ps.setString(i++, c.matchStatus.isEmpty() ? DbMatcher.CLASS_NOT_FOUND : c.matchStatus);
                    ps.setString(i++, cut(c.matchNote, 1000));
                    ps.setString(i++, cut(c.projectKey, 100));
                    ps.setString(i++, cut(c.repo, 200));
                    ps.setString(i++, cut(c.file, 1000));
                    ps.setInt(i++, c.line);
                    ps.setString(i++, cut(c.className, 1000));
                    ps.setString(i++, cut(c.member, 1000));
                    ps.setString(i++, cut(c.caughtTypes, 300));
                    ps.setString(i++, c.swallowKind.name());
                    ps.setString(i++, cut(c.link, 2000));
                    ir.swallowedByKind.merge(c.swallowKind.name(), 1, Integer::sum);
                    ps.addBatch();
                    ir.swallowed++;
                    if (++pending >= BATCH) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
            }
            if (pending > 0) ps.executeBatch();
        } finally {
            ps.close();
        }
    }

    /** Taranan her repo için bir satır: sayılar, dal / commit ve durum (bulgusu olmayan repolar da yazılır) */
    private void insertRepos(List<RepoResult> results, InsertResult ir) throws SQLException {
        if (!exists(db.repoTable)) {
            ir.repos = -1;
            return;
        }
        PreparedStatement ps = con.prepareStatement("INSERT INTO " + db.repoTable
                + " (scan_run_id, project_key, project_name, repo, branch, commit_id, status, java_file_count,"
                + " target_file_count, parse_failure_count, usage_count, swallowed_error_count,"
                + " custom_exception_class_count, error_count, clone_seconds, scan_seconds)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        try {
            for (RepoResult r : results) {
                int i = 1;
                ps.setLong(i++, ir.runId);
                ps.setString(i++, cut(r.repo.projectKey, 100));
                ps.setString(i++, cut(r.repo.projectName, 300));
                ps.setString(i++, cut(r.repo.slug, 200));
                ps.setString(i++, cut(r.repo.branch, 200));
                ps.setString(i++, cut(r.repo.commit, 64));
                ps.setString(i++, repoStatus(r));
                ps.setInt(i++, r.javaFiles);
                ps.setInt(i++, r.filesMentioningTarget);
                ps.setInt(i++, r.parseFailures);
                ps.setInt(i++, countWritten(r.usages));
                if (ir.swallowed < 0) ps.setNull(i++, Types.INTEGER);
                else ps.setInt(i++, countWritten(r.swallowed));
                ps.setInt(i++, r.subclasses.size());
                ps.setInt(i++, r.errors.size());
                ps.setInt(i++, (int) (r.repo.cloneMillis / 1000));
                ps.setInt(i++, (int) (r.repo.scanMillis / 1000));
                ps.addBatch();
                ir.repos++;
            }
            ps.executeBatch();
        } finally {
            ps.close();
        }
    }

    /** SCANNED; repo klonlanamadıysa CLONE_FAILED, taranamadıysa SCAN_FAILED */
    static String repoStatus(RepoResult r) {
        for (ScanError e : r.errors) {
            if (e.stage == ScanError.Stage.CLONE) return "CLONE_FAILED";
        }
        for (ScanError e : r.errors) {
            if (e.stage == ScanError.Stage.SCAN || e.stage == ScanError.Stage.OUT_OF_MEMORY
                    || e.stage == ScanError.Stage.FILE_LISTING) return "SCAN_FAILED";
        }
        return "SCANNED";
    }

    /** Tabloya yazılan bulgu sayısı (db.insert.unmatched=false ise sınıfı bulunamayanlar sayılmaz) */
    private int countWritten(List<? extends com.scanner.exception.model.Located> findings) {
        if (db.insertUnmatched) return findings.size();
        int n = 0;
        for (com.scanner.exception.model.Located l : findings) {
            if (!DbMatcher.CLASS_NOT_FOUND.equals(l.matchStatus)) n++;
        }
        return n;
    }

    /** table: "sema.tablo" veya "tablo" (search_path'teki) */
    private boolean hasColumn(String table, String column) throws SQLException {
        PreparedStatement ps = con.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_attribute"
                + " WHERE attrelid = to_regclass(?) AND attname = ? AND attnum > 0 AND NOT attisdropped)");
        try {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            try {
                return rs.next() && rs.getBoolean(1);
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private boolean exists(String table) throws SQLException {
        PreparedStatement ps = con.prepareStatement("SELECT to_regclass(?) IS NOT NULL");
        try {
            ps.setString(1, table);
            ResultSet rs = ps.executeQuery();
            try {
                return rs.next() && rs.getBoolean(1);
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private long insertRun(List<RepoResult> results, java.time.Instant startedAt) throws SQLException {
        int javaFiles = 0;
        for (RepoResult r : results) javaFiles += r.javaFiles;
        String sql = "INSERT INTO " + db.runTable
                + " (started_at, source, repo_count, java_file_count, exception_classes, note, run_by, host)"
                + " VALUES (?,?,?,?,?,?,?,?) RETURNING id";
        PreparedStatement ps = con.prepareStatement(sql);
        try {
            ps.setObject(1, java.time.OffsetDateTime.ofInstant(startedAt, java.time.ZoneOffset.UTC));
            ps.setString(2, cfg.source.name());
            ps.setInt(3, results.size());
            ps.setInt(4, javaFiles);
            ps.setString(5, String.join(",", cfg.exceptionClasses));
            ps.setString(6, db.note.isEmpty() ? defaultNote(startedAt) : db.note);
            ps.setString(7, cut(System.getProperty("user.name"), 100));
            ps.setString(8, cut(hostName(), 200));
            ResultSet rs = ps.executeQuery();
            try {
                rs.next();
                return rs.getLong(1);
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    /** reserveRun ile açılmış (henüz bitmemiş) kaydı taramanın bilgileriyle doldurur */
    private long fillReservedRun(long runId, List<RepoResult> results, java.time.Instant startedAt) throws SQLException {
        int javaFiles = 0;
        for (RepoResult r : results) javaFiles += r.javaFiles;
        PreparedStatement ps = con.prepareStatement("UPDATE " + db.runTable
                + " SET started_at = ?, source = ?, repo_count = ?, java_file_count = ?, exception_classes = ?, note = ?,"
                + " run_by = ?, host = ? WHERE id = ? AND finished_at IS NULL");
        try {
            ps.setObject(1, java.time.OffsetDateTime.ofInstant(startedAt, java.time.ZoneOffset.UTC));
            ps.setString(2, cfg.source.name());
            ps.setInt(3, results.size());
            ps.setInt(4, javaFiles);
            ps.setString(5, String.join(",", cfg.exceptionClasses));
            ps.setString(6, db.note.isEmpty() ? defaultNote(startedAt) : db.note);
            ps.setString(7, cut(System.getProperty("user.name"), 100));
            ps.setString(8, cut(hostName(), 200));
            ps.setLong(9, runId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Tarama kaydı bulunamadı veya tamamlanmış: " + db.runTable + ".id=" + runId);
            }
            return runId;
        } finally {
            ps.close();
        }
    }

    /**
     * Sunucuda tarama sıraya alınırken tarama kaydını açar; dönen id tarama numarası olur. Kayıt tarama bitince
     * insert(..., reservedRunId) ile doldurulur; o zamana kadar finished_at boştur (görünümler bu kayıtları saymaz).
     */
    public static long reserveRun(Config cfg) throws SQLException {
        try (Connection c = connect(cfg.db);
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + cfg.db.runTable
                     + " (started_at, source, exception_classes, note, run_by, host) VALUES (now(), ?, ?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, cfg.source.name());
            ps.setString(2, String.join(",", cfg.exceptionClasses));
            ps.setString(3, "Sırada");
            ps.setString(4, cut(System.getProperty("user.name"), 100));
            ps.setString(5, cut(hostName(), 200));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** reserveRun ile açılıp doldurulmamış kaydı siler (iptal, hata, deneme modu); tamamlanmış kayda dokunmaz */
    public static boolean deleteReservedRun(Config cfg, long runId) throws SQLException {
        try (Connection c = connect(cfg.db);
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + cfg.db.runTable
                     + " WHERE id = ? AND finished_at IS NULL")) {
            ps.setLong(1, runId);
            return ps.executeUpdate() > 0;
        }
    }

    /** db.note verilmediğinde tarama kaydına yazılan açıklama: "21.10.2026 11:50:34 tarihli tarama" */
    String defaultNote(java.time.Instant startedAt) {
        return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(db.noteZone)
                .format(startedAt) + " tarihli tarama";
    }

    public void close() {
        try {
            con.close();
        } catch (SQLException ignored) {
            // kapanırken oluşan hata önemsiz
        }
    }

    // =====================================================================
    //  Kod karmaşıklığı (env.method_risk_analyze, env.method_similarity)
    // =====================================================================

    /** Bir reponun bir analiz yolunun (parser / PMD) kayıt sonucu */
    public static final class ComplexityInsert {
        /** Yazılan metot sayısı; tablo yoksa veya uyumsuzsa -1 */
        public int methods;
        /** java_method_id'si bulunan metot sayısı */
        public int methodsMatched;
        /** Yazılan kopya / benzer kod grubu sayısı; tablo yoksa veya uyumsuzsa -1 */
        public int groups;
        /** Tablodaki bir CHECK kısıtına uymadığı için atlanan grup sayısı (ör. benzerlik oranı alt sınırı) */
        public int groupsSkipped;
        /** db.method.similarity.min.percent'ten düşük olduğu için yazılmayan grup sayısı */
        public int groupsBelowMin;
        /** risk_score &lt; db.method.risk.min.score olduğu için yazılmayan metot */
        public int methodsBelowMinScore;
        /** basit getter/setter olduğu için yazılmayan metot */
        public int methodsSkippedAccessor;
        /** aynı method_uid bu insert'te / taramada ikinci kez geldiği için atlanan */
        public int methodsSkippedDuplicateUid;
        /** Yazılamadıysa nedeni (tablo yok, scan_run_id tipi uyumsuz, atlanan grupların kısıtı); taramada bir kez loglanır */
        public final List<String> warnings = new ArrayList<String>();
        /** Bu repoda bir tablonun kaydı hata verdiyse hata mesajı (o tablo geri alınır, diğeri yazılır) */
        public final List<String> errors = new ArrayList<String>();
    }

    /** Analizdeki metot: sınıf / metot envanteriyle (java_method_id) eşleştirmek için */
    private static final class AnalyzedMethod extends com.scanner.exception.model.Located {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Bir reponun karmaşıklık analizini scanRunId'li taramaya bağlı yazar. Metotlar ve kopya / benzer kod grupları
     * ayrı transaction'larda yazılır: birinin hatası diğerini geri almaz. Metotlar env.java_class / env.java_method
     * ile eşleştirilip java_method_id doldurulur. Tablolarda project_key ve repo sütunları varsa onlar da yazılır.
     */
    public ComplexityInsert insertComplexity(long scanRunId, com.scanner.exception.model.RepoInfo repo,
                                             com.scanner.complexity.AnalysisReport report) throws Exception {
        ComplexityInsert ci = new ComplexityInsert();
        boolean parser = !"pmd-native".equals(com.scanner.complexity.ComplexityJson.pipeline(report));
        try {
            ci.methods = insertMethodRisks(scanRunId, repo, report, parser, ci);
            con.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            ci.methods = 0;
            ci.errors.add(db.methodRiskTable + ": " + unwrap(e).getMessage());
        }
        try {
            ci.groups = insertSimilarities(scanRunId, repo, report, parser, ci);
            con.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            ci.groups = 0;
            ci.groupsSkipped = 0;
            ci.errors.add(db.methodSimilarityTable + ": " + unwrap(e).getMessage());
        }
        return ci;
    }

    /** scan_run_id env.exception_scan_run.id'ye (bigint) bağlanır; başka tipteyse (ör. uuid) yazılmaz */
    private boolean usable(String table, ComplexityInsert ci) throws SQLException {
        if (!exists(table)) {
            ci.warnings.add(table + " tablosu yok, yazılmadı");
            return false;
        }
        String type = columnType(table, "scan_run_id");
        if (!"bigint".equals(type)) {
            ci.warnings.add(table + ".scan_run_id sütunu " + type + "; " + db.runTable + ".id'ye bağlanması için bigint "
                    + "olmalı, yazılmadı (bkz. README: Kod karmaşıklığı analizi)");
            return false;
        }
        return true;
    }

    private int insertMethodRisks(long scanRunId, com.scanner.exception.model.RepoInfo repo,
                                  com.scanner.complexity.AnalysisReport report, boolean parser, ComplexityInsert ci)
            throws Exception {
        String table = db.methodRiskTable;
        if (!usable(table, ci)) return -1;
        boolean repoColumns = hasColumn(table, "project_key") && hasColumn(table, "repo");

        List<RiskInsertRow> rows = new ArrayList<RiskInsertRow>();
        java.util.Set<String> seenUid = new java.util.LinkedHashSet<String>();
        java.util.Set<String> seenLine = new java.util.LinkedHashSet<String>();
        for (com.scanner.complexity.AnalysisReport.FileMetric f : report.files()) {
            for (com.scanner.complexity.AnalysisReport.ClassMetric c : f.classes()) {
                for (com.scanner.complexity.MethodMetric m : c.methods()) {
                    if (!shouldPersistMethodRisk(m, ci)) {
                        continue;
                    }
                    String uid = com.scanner.complexity.ComplexityJson.methodUid(f.path(), c.name(), m);
                    String lineKey = f.path() + "#" + c.name() + "#" + m.name() + "#" + m.startLine();
                    if (!seenUid.add(uid) || !seenLine.add(lineKey)) {
                        ci.methodsSkippedDuplicateUid++;
                        continue;
                    }
                    rows.add(new RiskInsertRow(f, c, m, analyzedMethod(f, c, m), uid));
                }
            }
        }
        if (!rows.isEmpty()) {
            List<AnalyzedMethod> refs = new ArrayList<AnalyzedMethod>(rows.size());
            for (RiskInsertRow row : rows) {
                refs.add(row.ref);
            }
            new DbMatcher(db.caseInsensitive, db.constructorNames).resolveAll(refs, this);
        }

        boolean qualityColumn = hasColumn(table, "quality_score");
        if (!qualityColumn) {
            ci.warnings.add(table + ".quality_score yok; sql/method_risk_quality_score.sql çalıştırılınca kalite skoru da yazılır");
        }
        PreparedStatement ps = con.prepareStatement("INSERT INTO " + table
                + " (scan_run_id, method_uid, file_path, class_name, method_signature, start_line, end_line, is_parser,"
                + " is_pmd, ncss_loc, risk_score, risk_level"
                + (qualityColumn ? ", quality_score" : "")
                + ", risk_inputs_json, body_hash, structural_hash, java_method_id,"
                + " created_at" + (repoColumns ? ", project_key, repo" : "")
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?" + (qualityColumn ? ",?" : "") + ",?::jsonb,?,?,?,now()"
                + (repoColumns ? ",?,?" : "") + ")"
                + " ON CONFLICT (scan_run_id, method_uid, is_parser, is_pmd) DO NOTHING");
        int count = 0;
        try {
            int pending = 0;
            for (RiskInsertRow row : rows) {
                int i = 1;
                ps.setLong(i++, scanRunId);
                ps.setString(i++, row.uid);
                ps.setString(i++, row.file.path());
                ps.setString(i++, row.type.name());
                ps.setString(i++, row.method.signature());
                ps.setInt(i++, row.method.startLine());
                ps.setInt(i++, row.method.endLine());
                ps.setBoolean(i++, parser);
                ps.setBoolean(i++, !parser);
                ps.setInt(i++, row.method.codeLines());
                ps.setBigDecimal(i++, java.math.BigDecimal.valueOf(row.method.riskScore())
                        .setScale(2, java.math.RoundingMode.HALF_UP));
                ps.setString(i++, row.method.riskLevel() == null ? null : row.method.riskLevel().name());
                if (qualityColumn) {
                    ps.setBigDecimal(i++, java.math.BigDecimal.valueOf(row.method.qualityScore())
                            .setScale(2, java.math.RoundingMode.HALF_UP));
                }
                ps.setString(i++, com.scanner.complexity.ComplexityJson.riskInputs(report, row.method));
                ps.setString(i++, row.method.bodyHash());
                ps.setString(i++, row.method.structuralHash());
                setLong(ps, i++, row.ref.dbMethodId);
                if (repoColumns) {
                    ps.setString(i++, repo.projectKey);
                    ps.setString(i, repo.slug);
                }
                if (row.ref.dbMethodId != null) ci.methodsMatched++;
                ps.addBatch();
                count++;
                if (++pending >= BATCH) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) ps.executeBatch();
        } finally {
            ps.close();
        }
        return count;
    }

    private static final class RiskInsertRow {
        final com.scanner.complexity.AnalysisReport.FileMetric file;
        final com.scanner.complexity.AnalysisReport.ClassMetric type;
        final com.scanner.complexity.MethodMetric method;
        final AnalyzedMethod ref;
        final String uid;

        RiskInsertRow(com.scanner.complexity.AnalysisReport.FileMetric file,
                      com.scanner.complexity.AnalysisReport.ClassMetric type,
                      com.scanner.complexity.MethodMetric method, AnalyzedMethod ref, String uid) {
            this.file = file;
            this.type = type;
            this.method = method;
            this.ref = ref;
            this.uid = uid;
        }
    }

    /** DÜŞÜK skor ve basit getter/setter env.method_risk_analyze'e yazılmaz. */
    private boolean shouldPersistMethodRisk(com.scanner.complexity.MethodMetric m, ComplexityInsert ci) {
        if (com.scanner.complexity.MethodAccessorFilter.isTrivialShortAccessor(m)
                || com.scanner.complexity.MethodAccessorFilter.isSimpleGetterOrSetter(m)) {
            ci.methodsSkippedAccessor++;
            return false;
        }
        if (m.riskScore() < db.methodRiskMinScore) {
            ci.methodsBelowMinScore++;
            return false;
        }
        return true;
    }

    private int insertSimilarities(long scanRunId, com.scanner.exception.model.RepoInfo repo,
                                   com.scanner.complexity.AnalysisReport report, boolean parser, ComplexityInsert ci)
            throws SQLException {
        String table = db.methodSimilarityTable;
        if (!usable(table, ci)) return -1;
        boolean repoColumns = hasColumn(table, "project_key") && hasColumn(table, "repo");
        PreparedStatement ps = con.prepareStatement("INSERT INTO " + table
                + " (scan_run_id, group_key, is_parser, is_pmd, similarity_type, similarity_percent, shared_ncss_lines,"
                + " members_json, meta_json, created_at" + (repoColumns ? ", project_key, repo" : "")
                + ") VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,now()" + (repoColumns ? ",?,?" : "") + ")");
        int count = 0;
        // Gruplar azdır; tek tek yazılır ki tablodaki bir CHECK kısıtına (ör. benzerlik oranı alt sınırı) uymayan
        // grup atlanıp diğerleri yazılabilsin
        java.util.Map<String, Integer> skippedBy = new java.util.TreeMap<String, Integer>();
        try {
            for (com.scanner.complexity.AnalysisReport.DuplicateGroup g : report.duplicateGroups()) {
                if (g.similarityPercent() < db.similarityMinPercent) {
                    ci.groupsBelowMin++;
                    continue;
                }
                int i = 1;
                ps.setLong(i++, scanRunId);
                ps.setString(i++, g.groupId());
                ps.setBoolean(i++, parser);
                ps.setBoolean(i++, !parser);
                ps.setString(i++, g.similarityType());
                ps.setShort(i++, (short) Math.max(0, Math.min(Short.MAX_VALUE, g.similarityPercent())));
                ps.setInt(i++, g.duplicatedLines());
                ps.setString(i++, com.scanner.complexity.ComplexityJson.members(g));
                ps.setString(i++, com.scanner.complexity.ComplexityJson.groupMeta(report, g));
                if (repoColumns) {
                    ps.setString(i++, repo.projectKey);
                    ps.setString(i, repo.slug);
                }
                java.sql.Savepoint sp = con.setSavepoint();
                try {
                    ps.executeUpdate();
                    con.releaseSavepoint(sp);
                    count++;
                } catch (SQLException e) {
                    if (!"23514".equals(e.getSQLState())) throw e; // check_violation dışındaki hatalar
                    con.rollback(sp);
                    ci.groupsSkipped++;
                    String constraint = e instanceof org.postgresql.util.PSQLException
                            && ((org.postgresql.util.PSQLException) e).getServerErrorMessage() != null
                            ? ((org.postgresql.util.PSQLException) e).getServerErrorMessage().getConstraint() : null;
                    skippedBy.merge(constraint == null ? "CHECK" : constraint, 1, Integer::sum);
                }
            }
        } finally {
            ps.close();
        }
        for (String c : skippedBy.keySet()) {
            ci.warnings.add(table + ": " + c + " kısıtına uymayan gruplar atlanıyor (ör. similarity_percent alt sınırın altında)");
        }
        return count;
    }

    /** "getRate(String, List<Long>)" -> getRate + [String, List<Long>]; sınıf adı paketle birleştirilir */
    private static AnalyzedMethod analyzedMethod(com.scanner.complexity.AnalysisReport.FileMetric f,
                                                 com.scanner.complexity.AnalysisReport.ClassMetric c,
                                                 com.scanner.complexity.MethodMetric m) {
        AnalyzedMethod a = new AnalyzedMethod();
        String pkg = f.packageName() == null ? "" : f.packageName();
        a.packageName = pkg;
        a.className = pkg.isEmpty() ? c.name() : pkg + "." + c.name();
        a.file = f.path();
        List<String> params = new ArrayList<String>();
        String sig = m.signature() == null ? "" : m.signature();
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (open >= 0 && close > open) {
            int depth = 0;
            StringBuilder cur = new StringBuilder();
            for (char ch : sig.substring(open + 1, close).toCharArray()) {
                if (ch == '<') depth++;
                if (ch == '>') depth--;
                if (ch == ',' && depth == 0) {
                    if (cur.toString().trim().length() > 0) params.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
            if (cur.toString().trim().length() > 0) params.add(cur.toString().trim());
        }
        boolean ctor = "CONSTRUCTOR".equalsIgnoreCase(m.kind());
        a.memberRef = new com.scanner.exception.model.MemberRef(ctor
                ? com.scanner.exception.model.MemberRef.Kind.CONSTRUCTOR : com.scanner.exception.model.MemberRef.Kind.METHOD,
                m.name(), params);
        return a;
    }

    /** Sütunun tipi (format_type), ör. bigint, uuid; sütun yoksa null */
    private String columnType(String table, String column) throws SQLException {
        PreparedStatement ps = con.prepareStatement("SELECT format_type(atttypid, atttypmod) FROM pg_attribute"
                + " WHERE attrelid = to_regclass(?) AND attname = ? AND attnum > 0 AND NOT attisdropped");
        try {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            try {
                return rs.next() ? rs.getString(1) : null;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    // =====================================================================
    //  Yardımcılar
    // =====================================================================

    private void rollbackQuietly() {
        try {
            con.rollback();
        } catch (SQLException ignored) {
            // asıl hata raporlanır
        }
    }

    /** Batch hatalarında PostgreSQL asıl sebebi getNextException içinde verir */
    private static SQLException unwrap(SQLException e) {
        SQLException next = e.getNextException();
        return next != null ? new SQLException(e.getMessage() + " -> " + next.getMessage(), next) : e;
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT);
        else ps.setLong(i, v.longValue());
    }

    private static Integer toInt(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            long v = Long.decode(s.replace("_", "").replaceAll("[lL]$", ""));
            return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? Integer.valueOf((int) v) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }
}
