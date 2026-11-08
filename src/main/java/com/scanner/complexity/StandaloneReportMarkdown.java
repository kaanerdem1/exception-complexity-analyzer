package com.scanner.complexity;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.DuplicateGroup;
import com.scanner.complexity.AnalysisReport.DuplicateMember;
import com.scanner.complexity.AnalysisReport.DuplicateStatistics;
import com.scanner.complexity.AnalysisReport.FileMetric;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Türkçe, okunabilir standalone (parser) Markdown raporu. */
final class StandaloneReportMarkdown {

    private StandaloneReportMarkdown() {
    }

    static String render(AnalysisReport report) {
        StringWriter out = new StringWriter();
        try {
            write(report, out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toString();
    }

    static void write(AnalysisReport report, Writer out) throws IOException {
        ModuleRootIndex moduleRoots = ModuleRootIndex.forScanRoot(Path.of(report.analyzedPath()));
        AncestorIndex ancestors = new AncestorIndex();
        writeHeader(out, report.tool(), report.generatedAt(), report.analyzedPath(), report.parserLanguageLevel(),
                report.riskModel(), report.summary());
        writePmdRulesetSection(out, report.pmdRulesetPhase());
        writeIncrementalCacheSection(out, report.cacheStatistics(), report.incrementalChanges());
        writeIncrementalChanges(out, report.incrementalChanges());
        writeMethodTableFromMetrics(out, moduleRoots, report.files(), ancestors);
        writeDuplicateSection(out, report.duplicateStatistics(), report.duplicateGroups());
        writeAncestorAppendix(out, ancestors);
        writeErrors(out, report.errors());
        writeScanDiagnostics(out, report.scanDiagnostics());
        writeFooter(out);
    }

    /** JSON'u belleğe komple almadan Markdown üretir (dosya dosya {@code files} dizisi). */
    static void renderFromJson(Reader json, Writer out) throws IOException {
        JsonReader reader = new JsonReader(json);
        JsonMeta meta = new JsonMeta();
        meta.ancestors = new AncestorIndex();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "tool" -> meta.tool = reader.nextString();
                case "generatedAt" -> meta.generatedAt = reader.nextString();
                case "analyzedPath" -> meta.analyzedPath = reader.nextString();
                case "parserLanguageLevel" -> meta.parserLanguageLevel = reader.nextString();
                case "riskModel" -> meta.riskModel = readRiskModelJson(reader);
                case "summary" -> meta.summary = readSummaryJson(reader);
                case "topRiskyMethods" -> skipValue(reader);
                case "files" -> {
                    meta.filesSectionSeen = true;
                    flushHeader(out, meta);
                    streamMethodTableFromFilesJson(reader, out, moduleRoots(meta.analyzedPath), meta.ancestors);
                }
                case "errors" -> meta.errors = readErrorsJson(reader);
                case "scanDiagnostics" -> meta.scanDiagnostics = readScanDiagnosticsJson(reader);
                case "duplicateStatistics" -> meta.duplicateStatistics = readDuplicateStatisticsJson(reader);
                case "duplicateGroups" -> meta.duplicateGroups = readDuplicateGroupsJson(reader);
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        flushHeader(out, meta);
        if (!meta.filesSectionSeen) {
            writeMethodTableIntro(out, true);
        }
        writeDuplicateSection(out, meta.duplicateStatistics, meta.duplicateGroups);
        writeAncestorAppendix(out, meta.ancestors);
        writeErrors(out, meta.errors);
        writeScanDiagnostics(out, meta.scanDiagnostics);
        writeFooter(out);
    }

    private static void flushHeader(Writer out, JsonMeta meta) throws IOException {
        if (meta.headerWritten || meta.summary == null) {
            return;
        }
        writeHeader(out, meta.tool, meta.generatedAt, meta.analyzedPath, meta.parserLanguageLevel,
                meta.riskModel, meta.summary);
        meta.headerWritten = true;
    }

    private static ModuleRootIndex moduleRoots(String analyzedPath) {
        if (analyzedPath == null || analyzedPath.isBlank()) {
            return ModuleRootIndex.forScanRoot(Path.of("."));
        }
        return ModuleRootIndex.forScanRoot(Path.of(analyzedPath));
    }

    private static void writeHeader(Writer out, String tool, String generatedAt, String analyzedPath,
                                    String parserLevel, AnalysisReport.RiskModel riskModel,
                                    AnalysisReport.Summary s) throws IOException {
        out.write("# Parser analiz raporu\n\n");
        out.write("Bu rapor **Java kaynak kodunu** tarayıp her **metod** için karmaşıklık ve **teknik risk** özetler.\n\n");
        out.write("Ana tablolarda **tek Risk skoru**, **SınıfAdı.metod(…n param)** ve **kaynak `.java` dosya adı** ");
        out.write("görünür; tam dizin yolu **Metod hiyerarşi yolları** (M-kodu) bölümündedir.\n\n");
        if (riskModel != null) {
            out.write(describeRiskModelTr(riskModel) + "\n\n");
        }
        out.write("## Bu tablolarda ne görüyorsun?\n\n");
        out.write("### Sayılar (metodun kendisi)\n\n");
        out.write("- **Dallanma (CC):** Kaç farklı karar yolu var — `if`, `for`, `catch`, `&&` vb.\n");
        out.write("- **Kod satırı (LOC):** Metod gövdesindeki gerçek kod satırı (boş ve yorum hariç).\n");
        out.write("- **İç içe:** Blokların en derin katmanı (if içinde for içinde if…).\n");
        out.write("- **Okunabilirlik:** Kodu kafada takip etme zorluğu; iç içe yapılar daha ağır sayılır.\n");
        out.write("- **FOUT (dış çağrı çeşitliliği):** Metodun JDK/same-type filtreli, import-aware ");
        out.write("kaç farklı dış tipe/metoda dokunduğu (CK FAN-OUT / ATFD benzeri **katalog** metriği). ");
        out.write("Tablolarda ham sayı olarak görünür; **risk skoruna ve “Nedeni” sütununa girmez**.\n");
        out.write("- **Parametre:** Metod imzasındaki parametre adedi.\n");
        out.write("- **Primitive obsession (indeks):** İmzada primitive/String/boolean flag + gövdede string/primitive yoğunluğu.\n");
        out.write("- **Complex conditional:** Tek koşuldaki maksimum `&&` / `||` sayısı.\n\n");
        out.write("### Risk skoru ve seviye\n\n");
        out.write("- **Risk (0–100):** Enterprise v3’te dallanma, LOC, Halstead, kokular, lambda vb. **tüm boyutlar** ");
        out.write("0–100 alt skora çevrilir; **en kötü boyut** ile **ağırlıklı ortalama** birleşerek **tek skor** ");
        out.write("üretilir (profil YAML’da). Tabloda yalnızca bu nihai skor görünür; kırılım JSON `riskBreakdown`.\n");
        out.write("- **80+:** KRİTİK bandı; 100 tavan değil, üst sınır — ayırt için ince farklar korunur.\n");
        out.write("- **Neden (dominant):** Skoru en çok hangi **risk boyutu** şişirdi — örneğin “dallanma” veya “uzunluk”.\n");
        out.write("- **Dev metod:** Aşırı uzun veya aşırı karmaşık metod uyarısı.\n\n");
        out.write("Aşağıdaki tabloda **taranan her metod** risk skoruna göre sıralanır (DÜŞÜK dahil); ");
        out.write("legacy/Halstead/koku girdileri skor sütunlarında ham sayı olarak görünür. ");
        out.write("Halstead, kod kokuları, LCOM3, modül cache istatistikleri **JSON raporunda** (`--output=…json`).\n");
        out.write("Incremental tarama: `run-analyze` varsayılan `--state=analysis-output/analyzer-state.json`; ");
        out.write("yorum/boşluk-only değişikliklerde skor motoru **AST özeti aynıysa** atlanır.\n\n");

        out.write("## Tarama özeti\n\n");
        out.write("| Alan | Değer |\n|------|-------|\n");
        out.write("| Tarih | " + escapeCell(formatGeneratedAtDisplay(generatedAt)) + " |\n");
        out.write("| Taranan klasör | `" + nullSafe(analyzedPath) + "` |\n");
        out.write("| Java sürümü (parse) | " + nullSafe(parserLevel) + " |\n");
        out.write("| Dosya (tarandı / okundu / hata) | "
                + s.filesScanned() + " / " + s.filesParsed() + " / " + s.filesFailed() + " |\n");
        out.write("| Sınıf / metod sayısı | " + s.classCount() + " / " + s.methodCount() + " |\n");
        out.write("| Toplam kod satırı | " + s.totalCodeLines() + " |\n");
        out.write("| Ortalama / en yüksek dallanma (CC) | "
                + fmt(s.averageCyclomaticComplexity()) + " / " + s.maxCyclomaticComplexity() + " |\n");
        out.write("| Dev metod sayısı | " + s.godMethodCount() + " |\n");
        out.write("| **Proje risk skoru (LOC ort.)** | **" + fmt(s.projectRiskScore())
                + " (" + levelTr(s.projectRiskLevel()) + ")** |\n");
        out.write("| Metod risk p95 / p99 | " + fmt(s.methodRiskScoreP95()) + " / "
                + fmt(s.methodRiskScoreP99()) + " |\n");
        out.write("| YÜKSEK+KRİTİK metod LOC payı | " + fmt(s.highPlusCriticalLocRatio()) + " |\n");
        out.write("| KRİTİK metod / KLOC | " + fmt(s.criticalMethodsPerKloc()) + " |\n\n");

        Map<RiskLevel, Long> dist = s.methodRiskDistribution();
        out.write("### Metodların risk dağılımı\n\n");
        out.write("| DÜŞÜK | ORTA | YÜKSEK | KRİTİK |\n|-------|------|--------|--------|\n");
        out.write("| " + dist.get(RiskLevel.LOW) + " | " + dist.get(RiskLevel.MEDIUM) + " | "
                + dist.get(RiskLevel.HIGH) + " | " + dist.get(RiskLevel.CRITICAL) + " |\n\n");
        writeModuleSummaries(out, s.moduleSummaries());
        if (tool != null && !tool.isBlank()) {
            out.write("<!-- tool: " + tool + " -->\n\n");
        }
    }

    private static void writePmdRulesetSection(Writer out, AnalysisReport.PmdRulesetPhase phase)
            throws IOException {
        if (phase == null || phase.violationCount() == 0 && phase.violationsSample().isEmpty()) {
            return;
        }
        out.write("## Aşama 1 — PMD ruleset ihlalleri\n\n");
        out.write("Klasik PMD taraması (`PmdAnalysis`). Tam liste: `");
        out.write(escapeCell(phase.violationsXmlPath()));
        out.write("`. Ruleset: `");
        out.write(escapeCell(phase.rulesetPath()));
        out.write("`. Toplam **");
        out.write(String.valueOf(phase.violationCount()));
        out.write("** ihlal");
        if (phase.violationCount() > phase.violationsSample().size()) {
            out.write(" (tabloda ilk ");
            out.write(String.valueOf(phase.violationsSample().size()));
            out.write(" satır; geri kalan JSON `pmdRulesetPhase.violationsSample`)");
        }
        out.write(".\n\n");
        if (!phase.violationsSample().isEmpty()) {
            out.write("| Dosya | Satır | Kural | Açıklama |\n|-------|------:|-------|----------|\n");
            for (AnalysisReport.PmdViolationRow row : phase.violationsSample()) {
                out.write("| `");
                out.write(escapeCell(row.file()));
                out.write("` | ");
                out.write(String.valueOf(row.beginLine()));
                out.write(" | ");
                out.write(escapeCell(row.ruleName()));
                out.write(" | ");
                out.write(escapeCell(row.description()));
                out.write(" |\n");
            }
            out.write("\n");
        }
        out.write("## Aşama 2 — Metod risk ve metrikler\n\n");
        out.write("Parser hattı: parametreler, risk skoru, duplicate (aşağıdaki tablolar).\n\n");
    }

    private static void writeModuleSummaries(Writer out, List<AnalysisReport.ModuleRiskSummary> modules)
            throws IOException {
        if (modules == null || modules.isEmpty()) {
            return;
        }
        out.write("### Modül bazında risk (p95 / KRİTİK·KLOC)\n\n");
        out.write("| Modül | Metod | Kod satırı | LOC-ağırlıklı risk | p95 | KRİTİK/KLOC |\n");
        out.write("|-------|------:|-----------:|-------------------:|----:|------------:|\n");
        for (AnalysisReport.ModuleRiskSummary m : modules) {
            out.write("| `" + escapeCell(m.moduleRoot()) + "` | " + m.methodCount() + " | "
                    + m.codeLines() + " | " + fmt(m.locWeightedRiskScore()) + " | "
                    + fmt(m.methodRiskScoreP95()) + " | " + fmt(m.criticalMethodsPerKloc()) + " |\n");
        }
        out.write("\n");
    }

    private static void writeIncrementalCacheSection(
            Writer out, AnalysisReport.CacheStatistics cache, IncrementalChanges changes) throws IOException {
        if (cache == null) {
            return;
        }
        out.write("## Incremental cache (bu koşu)\n\n");
        out.write("| Gösterge | Değer |\n|----------|------:|\n");
        out.write("| Toplam dosya | " + cache.totalFiles() + " |\n");
        out.write("| Atlandı (dosya byte hash) | " + cache.filesSkippedViaFileHash() + " |\n");
        out.write("| Atlandı (modül toplu) | " + cache.filesSkippedViaModuleBulk() + " |\n");
        out.write("| Atlandı (yorum/boşluk — AST aynı) | " + cache.filesSemanticCosmeticOnly() + " |\n");
        out.write("| Yeniden analiz edilen dosya | " + cache.filesReanalyzed() + " |\n");
        out.write("| Değişmeyen modül sayısı | " + cache.modulesUnchanged() + " |\n");
        out.write("| Metod reuse / yeniden analiz | " + cache.methodsSkippedViaHash() + " / "
                + cache.methodsReanalyzed() + " |\n");
        out.write("| Süre (ms) | " + cache.scanTimeMillis() + " |\n\n");
        if (changes != null && !changes.comparedToPreviousScan()) {
            out.write("*İlk tarama veya cache sıfırlandı — önceki taramayla diff yok.*\n\n");
        }
    }

    private static void writeIncrementalChanges(Writer out, IncrementalChanges changes) throws IOException {
        if (changes == null || !changes.comparedToPreviousScan()) {
            return;
        }
        out.write("## Son taramaya göre değişiklikler\n\n");
        out.write("Önceki `analyzer-state.json` ile karşılaştırma. **Kozmetik** = dosya metni değişti ama ");
        out.write("AST özeti (kod anlamı) aynı — risk skoru önceki taramadan taşındı.\n\n");
        out.write("| Dosya | + | − | ~ kod | ~ kozmetik | = |\n");
        out.write("|-------|--:|--:|------:|-----------:|--:|\n");
        out.write("| **Toplam** | " + changes.filesAdded() + " | " + changes.filesRemoved() + " | "
                + changes.filesModified() + " | " + changes.filesCosmeticOnly() + " | "
                + changes.filesUnchanged() + " |\n\n");
        out.write("Metod: **+" + changes.methodsAdded() + "** eklendi, **−" + changes.methodsRemoved()
                + "** silindi, **~" + changes.methodsModified() + "** gövdesi değişti.\n\n");
        List<IncrementalChanges.FilePathChange> cosmeticFiles = changes.fileChanges().stream()
                .filter(f -> f.kind() == IncrementalChanges.ChangeKind.COSMETIC).toList();
        if (!cosmeticFiles.isEmpty()) {
            out.write("### Kozmetik değişen dosyalar (kod aynı)\n\n");
            int limit = 20;
            for (int i = 0; i < Math.min(cosmeticFiles.size(), limit); i++) {
                out.write("- `" + escapeCell(cosmeticFiles.get(i).path()) + "`\n");
            }
            if (cosmeticFiles.size() > limit) {
                out.write("- *(+" + (cosmeticFiles.size() - limit) + " dosya — JSON)*\n");
            }
            out.write("\n");
        }
        if (changes.methodChanges().isEmpty()) {
            return;
        }
        out.write("| Dosya | Metod | Satır | Durum |\n");
        out.write("|-------|-------|------:|-------|\n");
        int limit = 40;
        int n = 0;
        for (IncrementalChanges.MethodPathChange mc : changes.methodChanges()) {
            if (n++ >= limit) {
                out.write("| … | | | *(daha fazlası JSON `incrementalChanges`)* |\n");
                break;
            }
            String lines = mc.kind() == IncrementalChanges.ChangeKind.REMOVED
                    ? "—" : mc.startLine() + "–" + mc.endLine();
            String methodCell = MethodHierarchy.tableMethodLabel(mc.className(), mc.methodSignature());
            out.write("| `" + escapeCell(mc.file()) + "` | "
                    + formatTableMethodName(methodCell) + " | " + lines + " | "
                    + switch (mc.kind()) {
                case ADDED -> "yeni";
                case REMOVED -> "silindi";
                case MODIFIED -> "değişti";
                case COSMETIC -> "kozmetik";
            } + " |\n");
        }
        out.write("\n");
    }

    private static void writeMethodTableFromMetrics(Writer out, ModuleRootIndex moduleRoots,
                                                    List<FileMetric> files, AncestorIndex ancestors)
            throws IOException {
        MethodTableCollectResult collected = collectAllMethodRows(moduleRoots, files, ancestors);
        List<MethodRow> rows = collected.rows();
        rows.sort(Comparator.comparingDouble(MethodRow::score).reversed());
        writeMethodTableIntro(out, rows.isEmpty());
        for (MethodRow r : rows) {
            writeMethodRow(out, r);
        }
        if (collected.excludedSimpleAccessors() > 0) {
            out.write("\n*Getter/setter ve riski 30 altı metodlar tabloda gösterilmez: "
                    + collected.excludedSimpleAccessors() + " (JSON dosya listesi de aynı eşiği kullanır).*\n");
        }
        if (!rows.isEmpty()) {
            out.write("\n† FOUT: katalog metriği; risk skoruna dahil değil.\n\n");
        }
    }

    private static void writeMethodTableIntro(Writer out, boolean empty) throws IOException {
        out.write("## Metodlar (risk skoruna göre)\n\n");
        if (empty) {
            out.write("*Metod listesi yok — `--detail=full` ile tarayın (`summary` modunda Markdown tablosu doldurulmaz).*\n\n");
        } else {
            MethodRiskTableColumns.writeScoreInputLegend(out);
            MethodRiskTableColumns.writeMetricHeaderRow(out);
        }
    }

    private static void writeMethodRow(Writer out, MethodRow r) throws IOException {
        out.write("| " + fmt(r.score()) + " | " + levelTr(r.level()) + " | "
                + r.ref() + " | " + formatTableMethodName(r.tableLabel()) + " | `"
                + escapeCell(r.javaFile()) + "` |");
        MethodRiskTableColumns.writeMetricCells(out, r.scan(), r.foutCatalog());
        out.write(" " + escapeCell(RiskBreakdownUtil.driverLabelTr(r.dominantDriver())) + " | "
                + (r.god() ? "evet" : "hayır") + " |\n");
    }

    private record MethodTableCollectResult(List<MethodRow> rows, int excludedSimpleAccessors) {
    }

    private static MethodTableCollectResult collectAllMethodRows(ModuleRootIndex moduleRoots, List<FileMetric> files,
                                                                 AncestorIndex ancestors) {
        List<MethodRow> rows = new ArrayList<>();
        int excludedAccessors = 0;
        for (FileMetric file : files) {
            if (file.classes().isEmpty()) {
                continue;
            }
            for (ClassMetric type : file.classes()) {
                for (MethodMetric m : type.methods()) {
                    if (MethodAccessorFilter.omitFromPublishedReport(m,
                            MethodAccessorFilter.DEFAULT_PUBLISH_MIN_SCORE)) {
                        excludedAccessors++;
                        continue;
                    }
                    String chain = MethodHierarchy.breadcrumb(MethodHierarchy.ancestorPath(
                            moduleRoots, file.path(), file.packageName(), type.name(), m.signature()));
                    String tableLabel = MethodHierarchy.tableMethodLabel(type.name(), m.signature());
                    String ref = ancestors.register(chain, tableLabel);
                    String javaFile = MethodHierarchy.javaFileName(file.path());
                    MethodScanValues scan = MethodScanValues.fromMetric(m);
                    rows.add(new MethodRow(ref, tableLabel, javaFile, m.riskScore(), m.riskLevel(), scan,
                            m.outboundDistinctCalls(),
                            RiskBreakdownUtil.dominantDriver(m.riskBreakdown()), m.godMethod(),
                            m.riskBreakdown()));
                }
            }
        }
        return new MethodTableCollectResult(rows, excludedAccessors);
    }

    private static void streamMethodTableFromFilesJson(JsonReader reader, Writer out,
                                                       ModuleRootIndex moduleRoots, AncestorIndex ancestors)
            throws IOException {
        List<MethodRow> rows = new ArrayList<>();
        int excludedAccessors = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            excludedAccessors += readFileMetricsJson(reader, rows, moduleRoots, ancestors);
        }
        reader.endArray();
        rows.sort(Comparator.comparingDouble(MethodRow::score).reversed());
        writeMethodTableIntro(out, rows.isEmpty());
        for (MethodRow r : rows) {
            writeMethodRow(out, r);
        }
        if (excludedAccessors > 0) {
            out.write("\n*Getter/setter ve riski 30 altı metodlar tabloda gösterilmez: "
                    + excludedAccessors + " (JSON dosya listesi de aynı eşiği kullanır).*\n");
        }
        if (!rows.isEmpty()) {
            out.write("\n† FOUT: katalog metriği; risk skoruna dahil değil.\n\n");
        }
    }

    private static int readFileMetricsJson(JsonReader reader, List<MethodRow> rows,
                                           ModuleRootIndex moduleRoots, AncestorIndex ancestors)
            throws IOException {
        String path = "";
        String packageName = "";
        int excludedAccessors = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "path" -> path = reader.nextString();
                case "packageName" -> packageName = reader.nextString();
                case "classes" -> excludedAccessors += readClassesJson(reader, path, packageName, rows, moduleRoots,
                        ancestors);
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        return excludedAccessors;
    }

    private static int readClassesJson(JsonReader reader, String path, String packageName,
                                       List<MethodRow> rows, ModuleRootIndex moduleRoots,
                                       AncestorIndex ancestors) throws IOException {
        int excludedAccessors = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            String className = "";
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "name" -> className = reader.nextString();
                    case "methods" -> excludedAccessors += readMethodsJson(reader, path, packageName, className, rows,
                            moduleRoots, ancestors);
                    default -> skipValue(reader);
                }
            }
            reader.endObject();
        }
        reader.endArray();
        return excludedAccessors;
    }

    private static int readMethodsJson(JsonReader reader, String path, String packageName, String className,
                                       List<MethodRow> rows, ModuleRootIndex moduleRoots,
                                       AncestorIndex ancestors) throws IOException {
        reader.beginArray();
        int excludedAccessors = 0;
        while (reader.hasNext()) {
            MethodJson m = readMethodJson(reader);
            if (m.isSimpleAccessor()) {
                excludedAccessors++;
                continue;
            }
            String chain = MethodHierarchy.breadcrumb(MethodHierarchy.ancestorPath(
                    moduleRoots, path, packageName, className, m.signature));
            String tableLabel = MethodHierarchy.tableMethodLabel(className, m.signature);
            String ref = ancestors.register(chain, tableLabel);
            String driver = RiskBreakdownUtil.dominantDriver(m.breakdown);
            String javaFile = MethodHierarchy.javaFileName(path);
            rows.add(new MethodRow(ref, tableLabel, javaFile, m.score, m.level, m.toScanValues(),
                    m.foutCount, driver, m.god, m.breakdown));
        }
        reader.endArray();
        return excludedAccessors;
    }

    private static MethodJson readMethodJson(JsonReader reader) throws IOException {
        MethodJson m = new MethodJson();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "name" -> m.name = reader.nextString();
                case "kind" -> m.kind = reader.nextString();
                case "signature" -> m.signature = reader.nextString();
                case "cyclomaticComplexity" -> m.cc = reader.nextInt();
                case "codeLines" -> m.loc = reader.nextInt();
                case "maxNestingDepth" -> m.nest = reader.nextInt();
                case "parameterCount" -> m.parameterCount = reader.nextInt();
                case "cognitiveComplexity" -> m.cogCount = reader.nextInt();
                case "logicalStatements" -> m.logicalStatements = reader.nextInt();
                case "exitPoints" -> m.exitPoints = reader.nextInt();
                case "catchClauses" -> m.catchClauses = reader.nextInt();
                case "switchCases" -> m.switchCases = reader.nextInt();
                case "outboundDistinctCalls" -> m.foutCount = reader.nextInt();
                case "lambdaCount" -> m.lambdaCount = reader.nextInt();
                case "maxTryNestingDepth" -> m.maxTryNestingDepth = reader.nextInt();
                case "localVariableCount" -> m.localVariableCount = reader.nextInt();
                case "maxMethodCallChainLength" -> m.maxMethodCallChainLength = reader.nextInt();
                case "emptyCatchBlocks" -> m.emptyCatchBlocks = reader.nextInt();
                case "catchExceptionOrThrowable" -> m.catchExceptionOrThrowable = reader.nextInt();
                case "catchWithOnlyPrintStackTrace" -> m.catchWithOnlyPrintStackTrace = reader.nextInt();
                case "primitiveObsessionIndex" -> m.primitiveObsessionIndex = reader.nextInt();
                case "maxBooleanOperatorsInCondition" -> m.maxBooleanOperatorsInCondition = reader.nextInt();
                case "halstead" -> readHalsteadIntoMethodJson(reader, m);
                case "exceptionSmells" -> readExceptionSmellCounts(reader, m);
                case "codeSmells" -> readCodeSmellCounts(reader, m);
                case "godMethod" -> m.god = reader.nextBoolean();
                case "riskScore" -> m.score = reader.nextDouble();
                case "riskLevel" -> m.level = RiskLevel.valueOf(reader.nextString());
                case "riskBreakdown" -> m.breakdown = readBreakdownJson(reader);
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        return m;
    }

    private static void readHalsteadIntoMethodJson(JsonReader reader, MethodJson m) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "difficulty" -> m.halsteadDifficulty = reader.nextDouble();
                case "effort" -> m.halsteadEffort = reader.nextDouble();
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        m.halsteadDifficultyRounded = (int) Math.round(m.halsteadDifficulty);
        m.halsteadEffortRounded = (int) Math.min(Integer.MAX_VALUE, Math.round(m.halsteadEffort));
    }

    private static void readExceptionSmellCounts(JsonReader reader, MethodJson m) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            reader.beginObject();
            while (reader.hasNext()) {
                if ("type".equals(reader.nextName())) {
                    String type = reader.nextString();
                    if ("SWALLOWED_EXCEPTION".equals(type)) {
                        m.swallowedExceptionSmells++;
                    } else if ("GENERIC_EXCEPTION_CATCH".equals(type)) {
                        m.genericExceptionSmells++;
                    }
                } else {
                    skipValue(reader);
                }
            }
            reader.endObject();
        }
        reader.endArray();
    }

    private static void readCodeSmellCounts(JsonReader reader, MethodJson m) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            reader.beginObject();
            while (reader.hasNext()) {
                if ("type".equals(reader.nextName())) {
                    switch (reader.nextString()) {
                        case "RAW_TYPE" -> m.rawTypeUsage++;
                        case "STRING_CONCAT_IN_LOOP" -> m.stringConcatInLoop++;
                        case "HARDCODED_IP", "HARDCODED_SQL", "HARDCODED_URL" -> m.hardcodedLiteralCount++;
                        default -> { }
                    }
                } else {
                    skipValue(reader);
                }
            }
            reader.endObject();
        }
        reader.endArray();
    }

    private static RiskBreakdown readBreakdownJson(JsonReader reader) throws IOException {
        int cc = 0;
        int loc = 0;
        int nest = 0;
        int params = 0;
        double ccSub = 0;
        double locSub = 0;
        double nestSub = 0;
        double paramsSub = 0;
        double cognitiveSub = 0;
        double outboundSub = 0;
        double exitSub = 0;
        Map<String, Double> extraSubScores = Map.of();
        double dominant = 0;
        double blend = 0;
        double bonus = 0;
        double finalScore = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "cyclomaticComplexity" -> cc = reader.nextInt();
                case "codeLines" -> loc = reader.nextInt();
                case "maxNestingDepth" -> nest = reader.nextInt();
                case "parameterCount" -> params = reader.nextInt();
                case "ccSubScore" -> ccSub = reader.nextDouble();
                case "locSubScore" -> locSub = reader.nextDouble();
                case "nestingSubScore" -> nestSub = reader.nextDouble();
                case "paramsSubScore" -> paramsSub = reader.nextDouble();
                case "cognitiveSubScore" -> cognitiveSub = reader.nextDouble();
                case "outboundSubScore" -> outboundSub = reader.nextDouble();
                case "exitSubScore" -> exitSub = reader.nextDouble();
                case "extraSubScores" -> extraSubScores = readDoubleMap(reader);
                case "dominantSubScore" -> dominant = reader.nextDouble();
                case "weightedBlend" -> blend = reader.nextDouble();
                case "compoundBonus" -> bonus = reader.nextDouble();
                case "finalScore" -> finalScore = reader.nextDouble();
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        return new RiskBreakdown(cc, loc, nest, params, ccSub, locSub, nestSub, paramsSub,
                cognitiveSub, outboundSub, exitSub, extraSubScores, dominant, blend, bonus, finalScore);
    }

    private static Map<String, Double> readDoubleMap(JsonReader reader) throws IOException {
        Map<String, Double> map = new java.util.LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            map.put(reader.nextName(), reader.nextDouble());
        }
        reader.endObject();
        return map;
    }

    private static AnalysisReport.Summary readSummaryJson(JsonReader reader) throws IOException {
        int filesScanned = 0;
        int filesParsed = 0;
        int filesFailed = 0;
        int classCount = 0;
        int methodCount = 0;
        int totalCodeLines = 0;
        double avgCc = 0;
        int maxCc = 0;
        int godMethods = 0;
        double projectScore = 0;
        RiskLevel projectLevel = RiskLevel.LOW;
        double highLocRatio = 0;
        double criticalPerKloc = 0;
        double p95 = 0;
        double p99 = 0;
        List<AnalysisReport.ModuleRiskSummary> moduleSummaries = List.of();
        Map<RiskLevel, Long> dist = new EnumMap<>(RiskLevel.class);
        for (RiskLevel level : RiskLevel.values()) {
            dist.put(level, 0L);
        }
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "filesScanned" -> filesScanned = reader.nextInt();
                case "filesParsed" -> filesParsed = reader.nextInt();
                case "filesFailed" -> filesFailed = reader.nextInt();
                case "classCount" -> classCount = reader.nextInt();
                case "methodCount" -> methodCount = reader.nextInt();
                case "totalCodeLines" -> totalCodeLines = reader.nextInt();
                case "averageCyclomaticComplexity" -> avgCc = reader.nextDouble();
                case "maxCyclomaticComplexity" -> maxCc = reader.nextInt();
                case "godMethodCount" -> godMethods = reader.nextInt();
                case "projectRiskScore" -> projectScore = reader.nextDouble();
                case "projectRiskLevel" -> projectLevel = RiskLevel.valueOf(reader.nextString());
                case "highPlusCriticalLocRatio" -> highLocRatio = reader.nextDouble();
                case "criticalMethodsPerKloc" -> criticalPerKloc = reader.nextDouble();
                case "methodRiskScoreP95" -> p95 = reader.nextDouble();
                case "methodRiskScoreP99" -> p99 = reader.nextDouble();
                case "methodRiskDistribution" -> readDistributionJson(reader, dist);
                case "moduleSummaries" -> moduleSummaries = readModuleSummariesJson(reader);
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        return new AnalysisReport.Summary(filesScanned, filesParsed, filesFailed, classCount, methodCount,
                totalCodeLines, avgCc, maxCc, godMethods, projectScore, projectLevel, dist,
                highLocRatio, criticalPerKloc, p95, p99, moduleSummaries);
    }

    private static List<AnalysisReport.ModuleRiskSummary> readModuleSummariesJson(JsonReader reader)
            throws IOException {
        List<AnalysisReport.ModuleRiskSummary> list = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            String moduleRoot = "";
            int methodCount = 0;
            int codeLines = 0;
            double locWeighted = 0;
            double p95 = 0;
            double criticalPerKloc = 0;
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "moduleRoot" -> moduleRoot = reader.nextString();
                    case "methodCount" -> methodCount = reader.nextInt();
                    case "codeLines" -> codeLines = reader.nextInt();
                    case "locWeightedRiskScore" -> locWeighted = reader.nextDouble();
                    case "methodRiskScoreP95" -> p95 = reader.nextDouble();
                    case "criticalMethodsPerKloc" -> criticalPerKloc = reader.nextDouble();
                    default -> skipValue(reader);
                }
            }
            reader.endObject();
            list.add(new AnalysisReport.ModuleRiskSummary(moduleRoot, methodCount, codeLines,
                    locWeighted, p95, criticalPerKloc));
        }
        reader.endArray();
        return list;
    }

    private static void readDistributionJson(JsonReader reader, Map<RiskLevel, Long> dist) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            RiskLevel level = RiskLevel.valueOf(reader.nextName());
            dist.put(level, reader.nextLong());
        }
        reader.endObject();
    }

    private static AnalysisReport.RiskModel readRiskModelJson(JsonReader reader) throws IOException {
        String version = "";
        String formula = "";
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "version" -> version = reader.nextString();
                case "formula" -> formula = reader.nextString();
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        return new AnalysisReport.RiskModel(version, formula, Map.of(), Map.of());
    }

    private static List<AnalysisReport.FileError> readErrorsJson(JsonReader reader) throws IOException {
        List<AnalysisReport.FileError> errors = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            String file = "";
            String message = "";
            ScanErrorCategory category = ScanErrorCategory.UNEXPECTED;
            String className = "";
            String methodSignature = "";
            int startLine = -1;
            int endLine = -1;
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "file" -> file = reader.nextString();
                    case "message" -> message = reader.nextString();
                    case "category" -> category = ScanErrorCategory.valueOf(reader.nextString());
                    case "className" -> className = reader.nextString();
                    case "methodSignature" -> methodSignature = reader.nextString();
                    case "startLine" -> startLine = reader.nextInt();
                    case "endLine" -> endLine = reader.nextInt();
                    default -> skipValue(reader);
                }
            }
            reader.endObject();
            if (!className.isEmpty() || !methodSignature.isEmpty() || startLine >= 0 || endLine >= 0) {
                errors.add(new AnalysisReport.FileError(file, message, category, className, methodSignature, startLine,
                        endLine));
            } else {
                errors.add(new AnalysisReport.FileError(file, message, category));
            }
        }
        reader.endArray();
        return errors;
    }

    private static ScanDiagnostics readScanDiagnosticsJson(JsonReader reader) throws IOException {
        String status = "";
        String lastPhase = "";
        String fatalPhase = null;
        String fatalMsg = null;
        String lastFile = null;
        double ratio = 0;
        Map<String, Integer> byCat = new LinkedHashMap<>();
        List<String> tips = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "completionStatus" -> status = reader.nextString();
                case "lastPhaseTr" -> lastPhase = reader.nextString();
                case "fatalPhaseTr" -> fatalPhase = reader.nextString();
                case "fatalMessage" -> fatalMsg = reader.nextString();
                case "lastFileAttempted" -> lastFile = reader.nextString();
                case "parseFailureRatio" -> ratio = reader.nextDouble();
                case "errorsByCategory" -> {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        byCat.put(reader.nextName(), reader.nextInt());
                    }
                    reader.endObject();
                }
                case "recommendationsTr" -> tips = readStringArray(reader);
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        return new ScanDiagnostics(status, lastPhase, fatalPhase, fatalMsg, lastFile, ratio,
                Map.copyOf(byCat), List.copyOf(tips), lastPhase, fatalPhase, List.copyOf(tips));
    }

    private static List<String> readStringArray(JsonReader reader) throws IOException {
        List<String> list = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            list.add(reader.nextString());
        }
        reader.endArray();
        return list;
    }

    private static void writeErrors(Writer out, List<AnalysisReport.FileError> errors) throws IOException {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        out.write("## Okunamayan dosyalar\n\n");
        for (AnalysisReport.FileError e : errors) {
            String location = "";
            if (e.className() != null && !e.className().isEmpty()
                    && e.methodSignature() != null && !e.methodSignature().isEmpty()) {
                location = " — `" + escapeCell(e.className()) + "#" + escapeCell(e.methodSignature()) + "`";
                if (e.startLine() > 0) {
                    location += " (satır " + e.startLine();
                    if (e.endLine() > e.startLine()) {
                        location += "-" + e.endLine();
                    }
                    location += ")";
                }
            }
            out.write("- `" + escapeCell(e.file()) + "` *(kategori: " + e.category().labelTr() + ")*"
                    + location + ": " + escapeCell(e.message()) + "\n");
        }
        out.write("\n");
    }

    private static void writeDuplicateSection(Writer out, DuplicateStatistics stats,
                                              List<DuplicateGroup> groups) throws IOException {
        DuplicateStatistics s = stats == null
                ? new DuplicateStatistics(0, 0, 0, 0) : stats;
        List<DuplicateGroup> g = groups == null ? List.of() : groups;
        if (s.exactGroups() == 0 && s.nearMissGroups() == 0 && g.isEmpty()) {
            return;
        }
        out.write("## Tekrarlayan / benzer kod (duplicate)\n\n");
        out.write("Tarama bittikten sonra proje genelinde aranır: **birebir / isim değişmiş** kopyalar ");
        out.write("yapısal hash ile, **yakın benzer** kopyalar PMD CPD ile. **40 satırdan kısa** eşleşmeler ");
        out.write("rapora ve JSON’a girmez. `--no-duplicates` ile kapatılabilir.\n\n");
        out.write("**Benzerlik yüzdesi:** Birebir **%100**, yapısal klon **%95**. CPD’de yüzde = ");
        out.write("ortak satır ÷ metodun (veya konumun) satır aralığı (1–99); tür eşikleri **%90 / %75 / %60**.\n\n");
        out.write("**Nasıl okunur?** Her **grup** = aynı kod parçasının farklı yerlerde tekrarı.\n\n");
        out.write("### Özet\n\n");
        out.write("| Tür | Grup sayısı | Konum sayısı |\n|-----|------------:|-------------:|\n");
        for (DuplicateSimilarityKind kind : DuplicateSimilarityKind.values()) {
            int[] counts = DuplicateSimilarityKind.countGroups(g, kind);
            out.write("| " + escapeCell(kind.labelTr()) + " | " + counts[0] + " | " + counts[1] + " |\n");
        }
        out.write("\n");
        out.write("*JSON `duplicateStatistics`: `exactGroups` = birebir + yapısal; `nearMissGroups` = tüm ");
        out.write("CPD benzerlik türleri.*\n\n");
        out.write("*Çok grup çıkıyorsa (JSON yazıcı gibi tekrarlı `w.name` kalıpları) eşiği yükseltin: ");
        out.write("`--min-duplicate-tokens=80` (CPD minimum ortak parça eşiği) veya `--no-duplicates`.*\n\n");
        if (g.isEmpty()) {
            out.write("*Grup detayı yok — JSON `duplicateGroups`.*\n\n");
            return;
        }
        out.write("### Gruplar\n\n");
        int limit = 40;
        int shown = 0;
        for (DuplicateGroup rawGroup : g) {
            if (shown >= limit) {
                out.write("*(" + (g.size() - limit) + " grup daha — JSON `duplicateGroups`)*\n\n");
                break;
            }
            shown++;
            DuplicateGroup group = DuplicateDetectionEngine.consolidateGroup(rawGroup);
            List<DuplicateMember> members = group.members();
            out.write("#### " + escapeCell(group.groupId()) + " — "
                    + duplicateTypeLabelTr(group.similarityType()));
            out.write(" — benzerlik **%" + group.similarityPercent() + "**");
            if (DuplicateSimilarityKind.isCpdSimilarity(group.similarityType())) {
                if (group.duplicatedLines() > 0) {
                    out.write(" (ortak **" + group.duplicatedLines() + "** satır");
                    if (group.matchedTokenCount() > 0) {
                        out.write(", CPD eşiği ~" + group.matchedTokenCount());
                    }
                    out.write(")");
                } else if (group.matchedTokenCount() > 0) {
                    out.write(" (CPD eşiği ~" + group.matchedTokenCount() + ")");
                }
            } else if (group.duplicatedLines() > 0) {
                out.write(" (" + group.duplicatedLines() + " satır)");
            }
            out.write(" — **" + members.size() + " konum**\n\n");
            String headline = DuplicateSimilarity.pairHeadline(members);
            if (!headline.isEmpty()) {
                out.write(headline + "\n\n");
            }
            for (DuplicateMember member : members) {
                String methodLabel = DuplicateSimilarity.methodLabel(member);
                out.write("- **" + escapeCell(methodLabel) + "** — `" + escapeCell(member.file())
                        + "` satır **" + member.startLine() + "–" + member.endLine() + "**");
                if (member.method() != null && !member.method().isBlank()
                        && !member.method().equals(DuplicateSimilarity.simpleMethodName(member.method()))) {
                    out.write(" (`" + escapeCell(member.method()) + "`)");
                }
                out.write("\n");
            }
            out.write("\n");
        }
    }

    private static String duplicateTypeLabelTr(String similarityType) {
        String label = DuplicateSimilarityKind.labelTrFor(similarityType);
        return label == null || label.isBlank() ? nullSafe(similarityType) : label;
    }

    private static DuplicateStatistics readDuplicateStatisticsJson(JsonReader reader) throws IOException {
        int exactGroups = 0;
        int methodsInExact = 0;
        int nearMissGroups = 0;
        int methodsInNearMiss = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "exactGroups" -> exactGroups = reader.nextInt();
                case "methodsInExactGroups" -> methodsInExact = reader.nextInt();
                case "nearMissGroups" -> nearMissGroups = reader.nextInt();
                case "methodsInNearMissGroups" -> methodsInNearMiss = reader.nextInt();
                default -> skipValue(reader);
            }
        }
        reader.endObject();
        return new DuplicateStatistics(exactGroups, methodsInExact, nearMissGroups, methodsInNearMiss);
    }

    private static List<DuplicateGroup> readDuplicateGroupsJson(JsonReader reader) throws IOException {
        List<DuplicateGroup> groups = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            String groupId = "";
            String similarityType = "";
            int matchedTokenCount = 0;
            int duplicatedLines = 0;
            int similarityPercent = -1;
            List<DuplicateMember> members = List.of();
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "groupId" -> groupId = reader.nextString();
                    case "similarityType" -> similarityType = reader.nextString();
                    case "matchedTokenCount" -> matchedTokenCount = reader.nextInt();
                    case "duplicatedLines" -> duplicatedLines = reader.nextInt();
                    case "similarityPercent" -> similarityPercent = reader.nextInt();
                    case "members" -> members = readDuplicateMembersJson(reader);
                    default -> skipValue(reader);
                }
            }
            reader.endObject();
            if (similarityPercent < 0) {
                groups.add(new DuplicateGroup(groupId, similarityType, matchedTokenCount, duplicatedLines, members));
            } else {
                groups.add(new DuplicateGroup(groupId, similarityType, matchedTokenCount, duplicatedLines,
                        similarityPercent, members));
            }
        }
        reader.endArray();
        return groups;
    }

    private static List<DuplicateMember> readDuplicateMembersJson(JsonReader reader) throws IOException {
        List<DuplicateMember> members = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            String file = "";
            String className = "";
            String method = "";
            int startLine = 0;
            int endLine = 0;
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "file" -> file = reader.nextString();
                    case "className" -> className = reader.nextString();
                    case "method" -> method = reader.nextString();
                    case "startLine" -> startLine = reader.nextInt();
                    case "endLine" -> endLine = reader.nextInt();
                    default -> skipValue(reader);
                }
            }
            reader.endObject();
            members.add(new DuplicateMember(file, className, method, startLine, endLine));
        }
        reader.endArray();
        return members;
    }

    private static void writeScanDiagnostics(Writer out, ScanDiagnostics d) throws IOException {
        if (d == null) {
            return;
        }
        out.write("## Tarama tanıları\n\n");
        out.write("| Alan | Değer |\n|------|-------|\n");
        out.write("| Durum | `" + d.completionStatus() + "` |\n");
        out.write("| Son aşama | " + nullSafe(d.lastPhaseTr()) + " |\n");
        if (d.fatalPhaseTr() != null) {
            out.write("| Fatal aşama | " + escapeCell(d.fatalPhaseTr()) + " |\n");
            out.write("| Fatal mesaj | " + escapeCell(nullSafe(d.fatalMessage())) + " |\n");
        }
        if (d.lastFileAttempted() != null && !d.lastFileAttempted().isBlank()) {
            out.write("| Son işlenen dosya | `" + escapeCell(d.lastFileAttempted()) + "` |\n");
        }
        out.write("| Parse hata oranı | " + fmtPercent(d.parseFailureRatio()) + " |\n");
        if (!d.errorsByCategory().isEmpty()) {
            out.write("| Kategori sayıları | " + escapeCell(d.errorsByCategory().toString()) + " |\n");
        }
        out.write("\n**Öneriler:**\n\n");
        for (String tip : d.recommendationsTr()) {
            out.write("- " + tip + "\n");
        }
        out.write("\nParse hataları: LANGUAGE_LEVEL / encoding / kaynak dosyası.\n\n");
    }

    private static String fmtPercent(double ratio) {
        return String.format(Locale.US, "%.1f%%", ratio * 100);
    }

    private static void writeFooter(Writer out) throws IOException {
        out.write("---\n");
        out.write("*PMD ile karşılaştırmak için `pmd-raporu.md` dosyasına bakın; CC sayıları yakın olmalı, ");
        out.write("risk skoru ise sadece bu parser aracına özeldir.*\n");
    }

    private static void skipValue(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return;
        }
        reader.skipValue();
    }

    /** ISO `generatedAt` → yerel tarih-saat (rapor özeti; CMD/Mac aynı). */
    static String formatGeneratedAtDisplay(String generatedAt) {
        if (generatedAt == null || generatedAt.isBlank()) {
            return "-";
        }
        try {
            Instant instant = Instant.parse(generatedAt.trim());
            ZoneId zone = ZoneId.systemDefault();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .withZone(zone);
            return formatter.format(instant) + " (" + zone.getId() + ")";
        } catch (DateTimeParseException e) {
            return generatedAt;
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String levelTr(RiskLevel level) {
        return switch (level) {
            case LOW -> "DÜŞÜK";
            case MEDIUM -> "ORTA";
            case HIGH -> "YÜKSEK";
            case CRITICAL -> "KRİTİK";
        };
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    private static String escapeCell(String s) {
        return s.replace("|", "\\|");
    }

    private static String formatTableMethodName(String shortLabel) {
        return "`" + escapeCell(shortLabel) + "`";
    }

    private static void writeAncestorAppendix(Writer out, AncestorIndex ancestors) throws IOException {
        if (ancestors == null || ancestors.isEmpty()) {
            return;
        }
        out.write("## Metod hiyerarşi yolları\n\n");
        out.write("Tablodaki **M** kodunun dosya konumu (`modül › dosya › tam imza`). ");
        out.write("Aynı isimli metodlar M kodu ile ayrılır:\n\n");
        for (AncestorIndex.Entry e : ancestors.entriesInOrder()) {
            out.write("- **" + e.ref() + "** `" + escapeCell(e.tableLabel()) + "` → `"
                    + escapeCell(e.breadcrumb()) + "`\n");
        }
        out.write("\n");
    }

    /** Tam breadcrumb → M1, M2 … (düz satır listesinde). */
    private static final class AncestorIndex {
        private final LinkedHashMap<String, Entry> breadcrumbToEntry = new LinkedHashMap<>();
        private int next = 1;

        String register(String breadcrumb, String tableLabel) {
            return breadcrumbToEntry.computeIfAbsent(breadcrumb, k -> new Entry("M" + (next++), tableLabel, breadcrumb))
                    .ref();
        }

        boolean isEmpty() {
            return breadcrumbToEntry.isEmpty();
        }

        List<Entry> entriesInOrder() {
            return List.copyOf(breadcrumbToEntry.values());
        }

        private record Entry(String ref, String tableLabel, String breadcrumb) {
        }
    }

    private static String describeRiskModelTr(AnalysisReport.RiskModel riskModel) {
        String version = riskModel.version() == null ? "" : riskModel.version();
        String suffix = version.startsWith("v3")
                ? " — Halstead, kod kokuları, exception kokuları ve sınıf LCOM3/Ce dahil (~25 metod boyutu)."
                : ".";
        return "**Puanlama:** enterprise-java YAML (0–100); skor ≈ %65 en kötü boyut + %35 ağırlıklı "
                + "ortalama + ince ayırıcı; seviye 30 / 50 / 80 eşikleri" + suffix;
    }

    private record MethodRow(
            String ref,
            String tableLabel,
            String javaFile,
            double score,
            RiskLevel level,
            MethodScanValues scan,
            int foutCatalog,
            String dominantDriver,
            boolean god,
            RiskBreakdown breakdown) {
    }

    private static final class MethodJson {
        String name = "";
        String kind = "";
        String signature = "";
        double score;
        RiskLevel level = RiskLevel.LOW;
        int cc;
        int loc;
        int nest;
        int parameterCount;
        int cogCount;
        int logicalStatements;
        int exitPoints;
        int catchClauses;
        int switchCases;
        int foutCount;
        int lambdaCount;
        int maxTryNestingDepth;
        int localVariableCount;
        int maxMethodCallChainLength;
        int emptyCatchBlocks;
        int catchExceptionOrThrowable;
        int catchWithOnlyPrintStackTrace;
        int primitiveObsessionIndex;
        int maxBooleanOperatorsInCondition;
        double halsteadDifficulty;
        double halsteadEffort;
        int halsteadDifficultyRounded;
        int halsteadEffortRounded;
        int rawTypeUsage;
        int stringConcatInLoop;
        int hardcodedLiteralCount;
        int swallowedExceptionSmells;
        int genericExceptionSmells;
        boolean god;
        RiskBreakdown breakdown;

        MethodScanValues toScanValues() {
            return new MethodScanValues(cc, loc, nest, parameterCount, cogCount, logicalStatements, exitPoints,
                    catchClauses, switchCases, foutCount, lambdaCount, maxTryNestingDepth, localVariableCount,
                    maxMethodCallChainLength, emptyCatchBlocks, catchExceptionOrThrowable,
                    catchWithOnlyPrintStackTrace, primitiveObsessionIndex, maxBooleanOperatorsInCondition,
                    halsteadDifficultyRounded, halsteadEffortRounded, rawTypeUsage, stringConcatInLoop,
                    hardcodedLiteralCount, swallowedExceptionSmells, genericExceptionSmells);
        }

        boolean isSimpleAccessor() {
            return MethodAccessorFilter.isSimpleGetterOrSetter(kind, name, parameterCount, cc, loc, logicalStatements,
                    foutCount, catchClauses);
        }
    }

    /** Enterprise-java v3 metod tablosu sütunları (YAML blend_weights ile uyumlu). */
    private static final class MethodRiskTableColumns {

        record Column(String id, String header, String legendTr, ToIntFunction<MethodScanValues> value) {
        }

        static final List<Column> ENTERPRISE_V3 = List.of(
                new Column("branching", "CC", "Dallanma (siklomatik) — if/for/catch/&& …", MethodScanValues::cyclomaticComplexity),
                new Column("length", "Satır", "Kod satırı (LOC, yorum/boş hariç)", MethodScanValues::codeLines),
                new Column("nesting", "İçi", "Maksimum iç içe blok derinliği", MethodScanValues::maxNestingDepth),
                new Column("parameters", "Param", "Parametre sayısı", MethodScanValues::parameterCount),
                new Column("cognitive", "Cog", "Cognitive (okunabilirlik) karmaşıklığı", MethodScanValues::cognitiveComplexity),
                new Column("exitPoints", "Çıkış", "return / throw çıkış noktası sayısı", MethodScanValues::exitPoints),
                new Column("logicalStatements", "İfade", "Mantıksal ifade (statement) sayısı", MethodScanValues::logicalStatements),
                new Column("lambdaCount", "λ", "Lambda ifadesi sayısı", MethodScanValues::lambdaCount),
                new Column("switchCases", "Switch", "Switch kolu sayısı", MethodScanValues::switchCases),
                new Column("maxTryNestingDepth", "Try", "İç içe try derinliği", MethodScanValues::maxTryNestingDepth),
                new Column("localVariableCount", "Yerel", "Yerel değişken sayısı", MethodScanValues::localVariableCount),
                new Column("maxMethodCallChainLength", "Zincir", "Peş peşe metod çağrı zinciri uzunluğu",
                        MethodScanValues::maxMethodCallChainLength),
                new Column("catchClauses", "Catch", "catch bloğu sayısı", MethodScanValues::catchClauses),
                new Column("emptyCatchBlocks", "∅Catch", "Boş catch bloğu", MethodScanValues::emptyCatchBlocks),
                new Column("catchExceptionOrThrowable", "ExcCatch", "Exception/Throwable geniş catch",
                        MethodScanValues::catchExceptionOrThrowable),
                new Column("catchWithOnlyPrintStackTrace", "PST", "Yalnızca printStackTrace içeren catch",
                        MethodScanValues::catchWithOnlyPrintStackTrace),
                new Column("primitiveObsessionIndex", "Prim", "Primitive obsession indeksi", MethodScanValues::primitiveObsessionIndex),
                new Column("maxBooleanOperatorsInCondition", "&&‖", "Tek koşuldaki max && / || sayısı",
                        MethodScanValues::maxBooleanOperatorsInCondition),
                new Column("halsteadDifficulty", "H.Dif", "Halstead zorluk (yuvarlak)", MethodScanValues::halsteadDifficultyRounded),
                new Column("halsteadEffort", "H.Efor", "Halstead effort (yuvarlak)", MethodScanValues::halsteadEffortRounded),
                new Column("rawTypeUsage", "Raw", "Ham (raw) tip kullanımı", MethodScanValues::rawTypeUsage),
                new Column("stringConcatInLoop", "Concat", "Döngüde string birleştirme", MethodScanValues::stringConcatInLoop),
                new Column("hardcodedLiteralCount", "Sabit", "Gömülü sabit (IP/SQL/URL vb.)", MethodScanValues::hardcodedLiteralCount),
                new Column("swallowedExceptionSmells", "YutExc", "Yutulan exception kokusu", MethodScanValues::swallowedExceptionSmells),
                new Column("genericExceptionSmells", "GenExc", "Generic exception catch kokusu",
                        MethodScanValues::genericExceptionSmells));

        static void writeScoreInputLegend(Writer out) throws IOException {
            out.write("**Skor girdileri (enterprise-java v3):** Dokümantasyondaki legacy/Halstead/koku katmanının ");
            out.write("ham sayıları — her biri YAML eşiklerine göre 0–100 alt skora çevrilip nihai **Risk** ");
            out.write("sütununa girer (~25 metod boyutu). Sınıf düzeyi (LCOM3, Ce, god-class) JSON sınıf kaydında.\n\n");
            for (Column c : ENTERPRISE_V3) {
                out.write("- **" + c.header() + "** (`" + c.id() + "`): " + c.legendTr() + "\n");
            }
            out.write("- **FOUT†** (`outboundDistinctCalls`): Dış çağrı çeşitliliği — **katalog**; v3 metod skoruna ");
            out.write("**girmez** (YAML policy).\n\n");
        }

        static void writeMetricHeaderRow(Writer out) throws IOException {
            StringBuilder headers = new StringBuilder("| Risk | Seviye | Kod | Metod | Dosya |");
            StringBuilder sep = new StringBuilder("|-----:|--------|:---:|-------|-------|");
            for (Column c : ENTERPRISE_V3) {
                headers.append(' ').append(c.header()).append(" |");
                sep.append("---:|");
            }
            headers.append(" FOUT† | Nedeni | Dev? |");
            sep.append("------:|--------|:----:|");
            out.write(headers + "\n");
            out.write(sep + "\n");
        }

        static void writeMetricCells(Writer out, MethodScanValues scan, int foutCatalog) throws IOException {
            for (Column c : ENTERPRISE_V3) {
                out.write(" " + c.value().applyAsInt(scan) + " |");
            }
            out.write(" " + foutCatalog + " |");
        }
    }

    private static final class JsonMeta {
        String tool;
        String generatedAt;
        String analyzedPath;
        String parserLanguageLevel;
        AnalysisReport.RiskModel riskModel;
        AnalysisReport.Summary summary;
        List<AnalysisReport.FileError> errors = List.of();
        ScanDiagnostics scanDiagnostics;
        DuplicateStatistics duplicateStatistics;
        List<DuplicateGroup> duplicateGroups = List.of();
        boolean headerWritten;
        boolean filesSectionSeen;
        AncestorIndex ancestors = new AncestorIndex();
    }
}
