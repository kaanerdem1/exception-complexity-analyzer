package com.scanner.exception.report;

import com.scanner.exception.config.Config;
import com.scanner.exception.model.CatchFinding;
import com.scanner.exception.model.EbmlFile;
import com.scanner.exception.model.RepoResult;
import com.scanner.exception.model.ScanError;
import com.scanner.exception.model.SubclassFinding;
import com.scanner.exception.model.UsageFinding;
import com.scanner.exception.scan.UsagePattern;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Tarama sonuçlarını ekiplere gönderilebilecek bir Excel dosyasına yazar. */
public final class ExcelReportWriter {

    /** Excel bir sayfada en fazla 65.530 köprü destekler; sonrası düz metin olarak yazılır. */
    private static final int MAX_LINKS_PER_SHEET = 65000;
    private static final Locale TR = new Locale("tr", "TR");
    /** Özet sayfasında sayıları gösterilen türler (sıra sütun sırasıdır) */
    private static final EbmlFile.Kind[] EBML_KINDS = {EbmlFile.Kind.SCREEN, EbmlFile.Kind.POPUP,
            EbmlFile.Kind.REGION, EbmlFile.Kind.REPORT, EbmlFile.Kind.PROCESS};

    private final Config cfg;
    private final Date scanDate;
    private final boolean ebml;
    private final boolean subclassSheet;

    public ExcelReportWriter(Config cfg, Date scanDate) {
        this(cfg, scanDate, false);
    }

    /**
     * @param fromDatabase rapor veritabanındaki taramadan üretiliyorsa true: tabloda tutulmayan EBML ve
     *                     "Alt Sınıflar" sayfaları yazılmaz (alt sınıf sayıları yine gösterilir)
     */
    public ExcelReportWriter(Config cfg, Date scanDate, boolean fromDatabase) {
        this.cfg = cfg;
        this.scanDate = scanDate;
        this.ebml = cfg.ebml.enabled && !fromDatabase;
        this.subclassSheet = !fromDatabase;
    }

    public void write(Path file, String title, List<RepoResult> results) throws IOException {
        // Akışlı (streaming) yazım: satırlar belleğe değil geçici dosyaya yazılır, büyük raporlarda bellek taşmaz
        SXSSFWorkbook wb = new SXSSFWorkbook(200);
        wb.setCompressTempFiles(true);
        try {
            Styles st = new Styles(wb);
            summary(wb, st, title, results);
            usages(wb, st, results);
            messages(wb, st, results);
            if (swallowedSheet()) swallowed(wb, st, results);
            if (subclassSheet) subclasses(wb, st, results);
            if (ebml) ebmlFiles(wb, st, results);
            errors(wb, st, results);

            // Detaylar ilk açılışta görünsün: Kullanımlar sayfası en başa alınır ve seçili açılır
            wb.setSheetOrder("Kullanımlar", 0);
            wb.setActiveSheet(0);
            wb.setSelectedTab(0);
            for (int i = 1; i < wb.getNumberOfSheets(); i++) wb.getSheetAt(i).setSelected(false);

            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        } finally {
            wb.dispose();
            wb.close();
        }
    }

    // ------------------------------------------------------------------ Özet

    private void summary(Workbook wb, Styles st, String title, List<RepoResult> results) {
        Sheet s = wb.createSheet("Özet");
        Row tr = s.createRow(0);
        Cell tc = tr.createCell(0);
        tc.setCellValue(title);
        tc.setCellStyle(st.title);

        List<String> names = patternNames();
        long files = 0, total = 0, subs = 0, errs = 0;
        long[] perPattern = new long[names.size()];
        for (RepoResult r : results) {
            files += r.javaFiles;
            total += r.usages.size();
            subs += r.subclassCount();
            errs += r.errors.size();
            for (int i = 0; i < names.size(); i++) perPattern[i] += r.count(names.get(i));
        }

        Row hint = s.createRow(1);
        Cell hc = hint.createCell(0);
        hc.setCellValue("Satır bazında detaylar (dosya, sınıf, metot, satır, kod) 'Kullanımlar' sayfasındadır.");
        Hyperlink toUsages = st.helper.createHyperlink(HyperlinkType.DOCUMENT);
        toUsages.setAddress("'Kullanımlar'!A1");
        hc.setHyperlink(toUsages);
        hc.setCellStyle(st.link);

        int row = 3;
        row = kv(s, st, row, "Tarama tarihi", new SimpleDateFormat("dd.MM.yyyy HH:mm").format(scanDate));
        row = kv(s, st, row, "Aranan exception sınıfları", String.join(", ", cfg.exceptionClasses));
        for (UsagePattern p : cfg.patterns) {
            row = kv(s, st, row, "Desen: " + p.name, "(" + p.argsText + ")");
        }
        if (cfg.throwOnly) row = kv(s, st, row, "Kapsam", "Sadece doğrudan throw edilenler");
        row = kv(s, st, row, "Taranan repo sayısı", results.size());
        row = kv(s, st, row, "Taranan Java dosyası", files);
        row = kv(s, st, row, "Toplam eşleşen kullanım", total);
        for (int i = 0; i < names.size(); i++) row = kv(s, st, row, "   " + names.get(i), perPattern[i]);
        if (swallowedSheet()) {
            Map<String, Long> kinds = new java.util.LinkedHashMap<String, Long>();
            for (CatchFinding.SwallowKind k : CatchFinding.SwallowKind.values()) kinds.put(k.name(), 0L);
            long sw = 0;
            for (RepoResult r : results) {
                for (CatchFinding c : r.swallowed) kinds.merge(c.swallowKind.name(), 1L, Long::sum);
                sw += r.swallowed.size();
            }
            row = kv(s, st, row, "Hata yutan catch blokları (scan.swallowed.scope=" + cfg.swallowedScope
                    + ", detaylar: 'Yutulan Exception'lar')", sw);
            for (Map.Entry<String, Long> en : kinds.entrySet()) row = kv(s, st, row, "   " + en.getKey(), en.getValue());
        }
        if (cfg.db.enabled) {
            Map<String, Integer> match = new java.util.TreeMap<String, Integer>();
            for (RepoResult r : results) {
                for (UsageFinding u : r.usages) {
                    String k = u.matchStatus.isEmpty() ? "(eşleştirilmedi)" : u.matchStatus;
                    Integer c = match.get(k);
                    match.put(k, c == null ? 1 : c + 1);
                }
            }
            for (Map.Entry<String, Integer> en : match.entrySet()) {
                row = kv(s, st, row, "   Veritabanı: " + en.getKey(), en.getValue().longValue());
            }
        }
        row = kv(s, st, row, "Türeyen alt sınıf tanımları", subs);
        if (ebml) {
            long all = 0;
            long[] perKind = new long[EBML_KINDS.length];
            for (int i = 0; i < EBML_KINDS.length; i++) {
                for (RepoResult r : results) perKind[i] += r.count(EBML_KINDS[i]);
                all += perKind[i];
            }
            row = kv(s, st, row, "Ekran / popup / region / rapor / process (detaylar: 'EBML Dosyaları')", all);
            for (int i = 0; i < EBML_KINDS.length; i++) row = kv(s, st, row, "   " + EBML_KINDS[i].label, perKind[i]);
            long un = 0;
            for (RepoResult r : results) un += r.count(EbmlFile.Kind.UNCLASSIFIED);
            if (un > 0) row = kv(s, st, row, "   " + EbmlFile.Kind.UNCLASSIFIED.label, un);
        }
        row = kv(s, st, row, "Tarama hataları", errs);
        row++;

        List<String> headers = new ArrayList<String>(Arrays.asList(
                "Proje", "Proje Adı", "Repo", "Dal", "Commit", "Java Dosyası", "Toplam Eşleşme"));
        List<Integer> widths = new ArrayList<Integer>(Arrays.asList(40, 28, 30, 16, 12, 12, 14));
        for (String n : names) {
            headers.add(n);
            widths.add(18);
        }
        if (swallowedSheet()) {
            headers.add("Yutulan Exception");
            widths.add(12);
        }
        if (ebml) {
            for (EbmlFile.Kind k : EBML_KINDS) {
                headers.add(k.label);
                widths.add(12);
            }
        }
        headers.add("Alt Sınıf");
        headers.add("Hata");
        widths.add(10);
        widths.add(8);
        int[] w = new int[widths.size()];
        for (int i = 0; i < w.length; i++) w[i] = widths.get(i);

        Table t = new Table(s, st, row, false, headers.toArray(new String[0]), w);
        List<RepoResult> sorted = new ArrayList<RepoResult>(results);
        sorted.sort(Comparator.comparingInt((RepoResult r) -> -r.usages.size()).thenComparing(r -> r.repo.id()));
        for (RepoResult r : sorted) {
            Row x = t.row();
            int c = 0;
            text(x, c++, r.repo.projectKey);
            text(x, c++, r.repo.projectName);
            text(x, c++, r.repo.slug);
            text(x, c++, r.repo.branch);
            text(x, c++, r.repo.commit.length() > 10 ? r.repo.commit.substring(0, 10) : r.repo.commit);
            num(x, c++, r.javaFiles);
            num(x, c++, r.usages.size());
            for (String n : names) num(x, c++, r.count(n));
            if (swallowedSheet()) num(x, c++, r.swallowed.size());
            if (ebml) {
                for (EbmlFile.Kind k : EBML_KINDS) num(x, c++, r.count(k));
            }
            num(x, c++, r.subclassCount());
            num(x, c, r.errors.size());
        }
        t.finish();
    }

    private List<String> patternNames() {
        List<String> names = new ArrayList<String>();
        if (cfg.patterns.isEmpty()) {
            if (!cfg.exceptionClasses.isEmpty()) names.add(UsagePattern.ALL);
        } else {
            for (UsagePattern p : cfg.patterns) names.add(p.name);
        }
        return names;
    }

    private static int kv(Sheet s, Styles st, int row, String label, Object value) {
        Row r = s.createRow(row);
        Cell k = r.createCell(0);
        k.setCellValue(label);
        k.setCellStyle(st.bold);
        Cell v = r.createCell(1);
        if (value instanceof Number) v.setCellValue(((Number) value).doubleValue());
        else v.setCellValue(clean(String.valueOf(value)));
        v.setCellStyle(st.base);
        return row + 1;
    }

    // ------------------------------------------------------------------ Kullanımlar

    private void usages(Workbook wb, Styles st, List<RepoResult> results) {
        Sheet s = wb.createSheet("Kullanımlar");
        boolean db = cfg.db.enabled;
        List<String> headers = new ArrayList<String>(Arrays.asList("Proje", "Repo", "Modül", "Dosya", "Sınıf",
                "Metot", "Satır", "Exception", "Eşleşen Desen", "Tip Kodu", "Yapılacak", "Id", "Mesaj Tipi",
                "Mesaj / Şablon", "Bağlam", "Test Kodu"));
        List<Integer> widths = new ArrayList<Integer>(Arrays.asList(14, 24, 20, 50, 40, 36, 7, 22, 24, 24, 28, 8,
                24, 60, 16, 10));
        if (db) {
            headers.addAll(Arrays.asList("DB Sınıf Id", "DB Metot Id", "Eşleşme", "Eşleşme Notu"));
            widths.addAll(Arrays.asList(12, 12, 18, 50));
        }
        headers.addAll(Arrays.asList("Kod", "Bağlantı"));
        widths.addAll(Arrays.asList(80, 18));
        int[] w = new int[widths.size()];
        for (int i = 0; i < w.length; i++) w[i] = widths.get(i);

        Table t = new Table(s, st, 0, true, headers.toArray(new String[0]), w);
        for (RepoResult r : results) {
            for (UsageFinding u : r.usages) {
                Row x = t.row();
                int c = 0;
                text(x, c++, u.projectKey);
                text(x, c++, u.repo);
                text(x, c++, u.module);
                text(x, c++, u.file);
                text(x, c++, u.className);
                text(x, c++, u.member);
                num(x, c++, u.line);
                text(x, c++, u.targetClass);
                text(x, c++, u.pattern);
                text(x, c++, u.typeCode);
                text(x, c++, u.action);
                text(x, c++, u.idValue);
                text(x, c++, u.messageKind == null ? "" : u.messageKind.label);
                text(x, c++, u.message);
                text(x, c++, u.context.name());
                text(x, c++, u.testCode ? "Evet" : "Hayır");
                if (db) {
                    if (u.dbClassId == null) text(x, c++, ""); else num(x, c++, u.dbClassId.longValue());
                    if (u.dbMethodId == null) text(x, c++, ""); else num(x, c++, u.dbMethodId.longValue());
                    text(x, c++, u.matchStatus);
                    text(x, c++, u.matchNote);
                }
                text(x, c++, u.expression);
                t.link(x, c, u.link);
            }
        }
        t.finish();
    }

    // ------------------------------------------------------------------ Mesajlar (katalog taslağı)

    private void messages(Workbook wb, Styles st, List<RepoResult> results) {
        Map<String, MsgGroup> groups = new LinkedHashMap<String, MsgGroup>();
        for (RepoResult r : results) {
            for (UsageFinding u : r.usages) {
                // message sadece metni kodda yazılı mesajlarda (sabit, birleştirme, format) doludur
                if (u.message.trim().isEmpty()) continue;
                String key = normalize(u.message);
                MsgGroup g = groups.get(key);
                if (g == null) {
                    g = new MsgGroup(u.message.trim(), u);
                    groups.put(key, g);
                }
                g.count++;
                g.repos.add(u.projectKey + "/" + u.repo);
                g.projects.add(u.projectKey);
                g.patterns.add(u.pattern);
                if (!u.idValue.isEmpty()) g.ids.add(u.idValue);
            }
        }
        List<MsgGroup> sorted = new ArrayList<MsgGroup>(groups.values());
        sorted.sort((a, b) -> b.count != a.count ? Integer.compare(b.count, a.count) : a.text.compareTo(b.text));

        Sheet s = wb.createSheet("Mesajlar");
        Row note = s.createRow(0);
        Cell nc = note.createCell(0);
        nc.setCellValue("Aynı anlamdaki mesajları birleştirip sarı sütunları doldurun; bu liste hata kataloğunun "
                + "taslağıdır. {0}, {1} birleştirilen değişkenleri gösterir. MessageFormat'ta tek tırnak (') "
                + "özel karakterdir, kullanıcı mesajlarında '' olarak yazılmalıdır.");
        nc.setCellStyle(st.italic);

        Table t = new Table(s, st, 2, true,
                new String[]{"Mesaj / Şablon", "Kullanım Sayısı", "Repo Sayısı", "Desen", "Id Değerleri", "Projeler",
                        "Örnek Konum", "Örnek Bağlantı", "Önerilen Hata Kodu", "Kullanıcı Mesajı (TR)",
                        "Kullanıcı Mesajı (EN)"},
                new int[]{70, 14, 12, 24, 14, 30, 60, 18, 20, 50, 50});
        for (int i = 8; i <= 10; i++) s.getRow(2).getCell(i).setCellStyle(st.inputHeader);
        for (MsgGroup g : sorted) {
            Row x = t.row();
            text(x, 0, g.text);
            num(x, 1, g.count);
            num(x, 2, g.repos.size());
            text(x, 3, String.join(", ", g.patterns));
            text(x, 4, String.join(", ", g.ids));
            text(x, 5, String.join(", ", g.projects));
            text(x, 6, g.sample.projectKey + "/" + g.sample.repo + " - " + g.sample.file + ":" + g.sample.line);
            t.link(x, 7, g.sample.link);
            for (int i = 8; i <= 10; i++) {
                Cell c = x.createCell(i);
                c.setCellStyle(st.input);
            }
        }
        t.finish();
    }

    private static String normalize(String msg) {
        return msg.toLowerCase(TR).replaceAll("\\s+", " ").trim().replaceAll("[\\s.!:;,]+$", "");
    }

    private static final class MsgGroup {
        final String text;
        final UsageFinding sample;
        int count;
        final TreeSet<String> repos = new TreeSet<String>();
        final TreeSet<String> projects = new TreeSet<String>();
        final TreeSet<String> patterns = new TreeSet<String>();
        final TreeSet<String> ids = new TreeSet<String>();

        MsgGroup(String text, UsageFinding sample) {
            this.text = text;
            this.sample = sample;
        }
    }

    // ------------------------------------------------------------------ Catch blokları

    // ------------------------------------------------------------------ Yutulan exception'lar

    private boolean swallowedSheet() {
        return cfg.reportSwallowed && cfg.collectSwallowed();
    }

    /** env.swallowed_exceptions tablosuna yazılan (db.enabled=true ise) kayıtların aynısı */
    private void swallowed(Workbook wb, Styles st, List<RepoResult> results) {
        Sheet s = wb.createSheet("Yutulan Exception'lar");
        boolean db = cfg.db.enabled;
        List<String> headers = new ArrayList<String>(Arrays.asList("Proje", "Repo", "Modül", "Dosya", "Sınıf",
                "Metot", "Satır", "Yakalanan Tip", "Yutma Türü", "Not", "Test Kodu"));
        List<Integer> widths = new ArrayList<Integer>(Arrays.asList(14, 24, 20, 50, 40, 36, 7, 30, 16, 50, 10));
        if (db) {
            headers.addAll(Arrays.asList("DB Sınıf Id", "DB Metot Id", "Eşleşme", "Eşleşme Notu"));
            widths.addAll(Arrays.asList(12, 12, 18, 50));
        }
        headers.add("Bağlantı");
        widths.add(18);
        int[] w = new int[widths.size()];
        for (int i = 0; i < w.length; i++) w[i] = widths.get(i);
        Table t = new Table(s, st, 0, true, headers.toArray(new String[0]), w);
        for (RepoResult r : results) {
            for (CatchFinding f : r.swallowed) {
                Row x = t.row();
                int c = 0;
                text(x, c++, f.projectKey);
                text(x, c++, f.repo);
                text(x, c++, f.module);
                text(x, c++, f.file);
                text(x, c++, f.className);
                text(x, c++, f.member);
                num(x, c++, f.line);
                text(x, c++, f.caughtTypes);
                text(x, c++, f.swallowKind.name());
                text(x, c++, f.note());
                text(x, c++, f.testCode ? "Evet" : "Hayır");
                if (db) {
                    if (f.dbClassId == null) text(x, c++, ""); else num(x, c++, f.dbClassId.longValue());
                    if (f.dbMethodId == null) text(x, c++, ""); else num(x, c++, f.dbMethodId.longValue());
                    text(x, c++, f.matchStatus);
                    text(x, c++, f.matchNote);
                }
                t.link(x, c, f.link);
            }
        }
        t.finish();
    }

    // ------------------------------------------------------------------ Alt sınıflar

    private void subclasses(Workbook wb, Styles st, List<RepoResult> results) {
        Sheet s = wb.createSheet("Alt Sınıflar");
        Table t = new Table(s, st, 0, true,
                new String[]{"Proje", "Repo", "Modül", "Dosya", "Sınıf", "Üst Sınıf", "Satır", "Not", "Bağlantı"},
                new int[]{14, 24, 20, 50, 50, 40, 7, 70, 18});
        for (RepoResult r : results) {
            for (SubclassFinding sf : r.subclasses) {
                Row x = t.row();
                text(x, 0, sf.projectKey);
                text(x, 1, sf.repo);
                text(x, 2, sf.module);
                text(x, 3, sf.file);
                text(x, 4, sf.className);
                text(x, 5, sf.parent);
                num(x, 6, sf.line);
                text(x, 7, cfg.globalHierarchy ? "Tüm repolardaki kullanımları da tarandı."
                        : "Bu repodaki kullanımları da tarandı. Başka repolarda kullanılıyorsa "
                        + "exception.classes ayarına ekleyip taramayı tekrarlayın.");
                t.link(x, 8, sf.link);
            }
        }
        t.finish();
    }

    // ------------------------------------------------------------------ EBML dosyaları

    private void ebmlFiles(Workbook wb, Styles st, List<RepoResult> results) {
        Sheet s = wb.createSheet("EBML Dosyaları");
        boolean db = cfg.db.enabled;
        List<String> headers = new ArrayList<String>(Arrays.asList("Tür", "Kural", "Proje", "Repo", "Modül",
                "Paket", "Dosya Adı", "Dosya Yolu", "Process Id", "Process Adı", "Aranan Proje Adı"));
        List<Integer> widths = new ArrayList<Integer>(Arrays.asList(16, 22, 14, 24, 20, 50, 36, 70, 12, 36, 24));
        if (db) {
            headers.addAll(Arrays.asList("DB Proje Id", "Proje Eşleşmesi"));
            widths.addAll(Arrays.asList(12, 20));
        }
        headers.add("Bağlantı");
        widths.add(18);
        int[] w = new int[widths.size()];
        for (int i = 0; i < w.length; i++) w[i] = widths.get(i);

        Table t = new Table(s, st, 0, true, headers.toArray(new String[0]), w);
        for (RepoResult r : results) {
            for (EbmlFile e : r.ebmlFiles) {
                Row x = t.row();
                int c = 0;
                text(x, c++, e.kind.label);
                text(x, c++, e.rule);
                text(x, c++, e.projectKey);
                text(x, c++, e.repo);
                text(x, c++, e.module);
                text(x, c++, e.packageName);
                text(x, c++, e.fileName);
                text(x, c++, e.file);
                if (e.processId == null) text(x, c++, ""); else num(x, c++, e.processId.longValue());
                text(x, c++, e.processName);
                text(x, c++, e.projectName);
                if (db) {
                    if (e.projectId == null) text(x, c++, ""); else num(x, c++, e.projectId.longValue());
                    text(x, c++, e.kind == EbmlFile.Kind.UNCLASSIFIED ? "" : e.projectMatch);
                }
                t.link(x, c, e.link);
            }
        }
        t.finish();
    }

    // ------------------------------------------------------------------ Hatalar

    private void errors(Workbook wb, Styles st, List<RepoResult> results) {
        Sheet s = wb.createSheet("Tarama Hataları");
        Table t = new Table(s, st, 0, true,
                new String[]{"Proje", "Repo", "Aşama", "Dosya", "Hata"},
                new int[]{14, 30, 16, 60, 100});
        for (RepoResult r : results) {
            for (ScanError e : r.errors) {
                Row x = t.row();
                text(x, 0, e.projectKey);
                text(x, 1, e.repo);
                text(x, 2, e.stage.name());
                text(x, 3, e.file);
                text(x, 4, e.message);
            }
        }
        t.finish();
    }

    // ------------------------------------------------------------------ yardımcılar

    private static void text(Row r, int c, String v) {
        r.createCell(c).setCellValue(clean(v));
    }

    private static void num(Row r, int c, long v) {
        r.createCell(c).setCellValue((double) v);
    }

    /** Excel'in kabul etmediği kontrol karakterlerini atar ve hücre sınırına göre kısaltır. */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), 32000));
        for (int i = 0; i < s.length() && sb.length() < 32000; i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 0x20 && ch != 0xFFFE && ch != 0xFFFF) || ch == '\t' || ch == '\n' || ch == '\r';
            if (ok) sb.append(ch);
        }
        return sb.toString();
    }

    private static final class Table {
        private final Sheet sheet;
        private final Styles st;
        private final int headerRow;
        private final int cols;
        private int next;
        private int links;

        Table(Sheet sheet, Styles st, int headerRow, boolean freeze, String[] headers, int[] widths) {
            this.sheet = sheet;
            this.st = st;
            this.headerRow = headerRow;
            this.cols = headers.length;
            Row h = sheet.createRow(headerRow);
            for (int i = 0; i < headers.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(st.header);
                sheet.setColumnWidth(i, Math.min(255, widths[i]) * 256);
            }
            if (freeze) sheet.createFreezePane(0, headerRow + 1);
            this.next = headerRow + 1;
        }

        Row row() {
            return sheet.createRow(next++);
        }

        void link(Row r, int c, String url) {
            Cell cell = r.createCell(c);
            if (url == null || url.isEmpty()) {
                cell.setCellValue("");
                return;
            }
            if (links >= MAX_LINKS_PER_SHEET) {
                cell.setCellValue(url);
                return;
            }
            try {
                Hyperlink h = st.helper.createHyperlink(HyperlinkType.URL);
                h.setAddress(url);
                cell.setHyperlink(h);
                cell.setCellValue("Bitbucket'ta aç");
                cell.setCellStyle(st.link);
                links++;
            } catch (RuntimeException e) {
                cell.setCellValue(url);
            }
        }

        void finish() {
            if (next > headerRow + 1) {
                sheet.setAutoFilter(new CellRangeAddress(headerRow, next - 1, 0, cols - 1));
            }
        }
    }

    private static final class Styles {
        final CreationHelper helper;
        final CellStyle base;
        final CellStyle header;
        final CellStyle inputHeader;
        final CellStyle input;
        final CellStyle title;
        final CellStyle bold;
        final CellStyle italic;
        final CellStyle link;

        Styles(Workbook wb) {
            helper = wb.getCreationHelper();

            Font normal = font(wb, false, false, (short) 10, IndexedColors.BLACK.getIndex());
            wb.getCellStyleAt(0).setFont(normal); // varsayılan stil: Arial 10

            base = wb.createCellStyle();
            base.setFont(normal);

            header = wb.createCellStyle();
            header.setFont(font(wb, true, false, (short) 10, IndexedColors.WHITE.getIndex()));
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);

            inputHeader = wb.createCellStyle();
            inputHeader.cloneStyleFrom(header);
            inputHeader.setFont(font(wb, true, false, (short) 10, IndexedColors.BLACK.getIndex()));
            inputHeader.setFillForegroundColor(IndexedColors.GOLD.getIndex());

            input = wb.createCellStyle();
            input.setFont(normal);
            input.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            input.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            title = wb.createCellStyle();
            title.setFont(font(wb, true, false, (short) 14, IndexedColors.BLACK.getIndex()));

            bold = wb.createCellStyle();
            bold.setFont(font(wb, true, false, (short) 10, IndexedColors.BLACK.getIndex()));

            italic = wb.createCellStyle();
            italic.setFont(font(wb, false, true, (short) 10, IndexedColors.GREY_50_PERCENT.getIndex()));

            link = wb.createCellStyle();
            Font lf = font(wb, false, false, (short) 10, IndexedColors.BLUE.getIndex());
            lf.setUnderline(Font.U_SINGLE);
            link.setFont(lf);
        }

        private static Font font(Workbook wb, boolean bold, boolean italic, short size, short color) {
            Font f = wb.createFont();
            f.setFontName("Arial");
            f.setBold(bold);
            f.setItalic(italic);
            f.setFontHeightInPoints(size);
            f.setColor(color);
            return f;
        }
    }
}
