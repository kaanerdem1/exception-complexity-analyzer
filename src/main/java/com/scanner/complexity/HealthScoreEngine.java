package com.scanner.complexity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.HealthScoreCatalog.Axis;
import com.scanner.complexity.HealthScoreCatalog.Kind;
import com.scanner.complexity.HealthScoreCatalog.Rule;
import com.scanner.complexity.HealthScoreCatalog.Scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * İki bağımsız skor. Karmaşıklık raporda yüksek = kötü (100 − ham). Kalite yüksek = iyi.
 * Sınıf = w1 × metot LOC ortalaması + w2 × sınıf kuralları. Modül = sınıf LOC ortalaması.
 */
public final class HealthScoreEngine {

    private static volatile HealthScoreEngine DEFAULT;

    private final HealthScoreCatalog catalog;

    public HealthScoreEngine(HealthScoreCatalog catalog) {
        this.catalog = catalog;
    }

    /** Dosya hash kopyasının kabul ettiği formül sürümü. Eski satırlarda bu alan yoktur. */
    public static int scoreSchema() {
        return IncrementalStateSupport.CACHE_SCHEMA_VERSION;
    }

    public static HealthScoreEngine get() {
        HealthScoreEngine current = DEFAULT;
        if (current != null) {
            return current;
        }
        synchronized (HealthScoreEngine.class) {
            if (DEFAULT == null) {
                try {
                    DEFAULT = new HealthScoreEngine(HealthScoreCatalog.loadDefault());
                } catch (Throwable failure) {
                    throw new IllegalStateException("Skor kataloğu açılamadı. " + rootText(failure));
                }
            }
            return DEFAULT;
        }
    }

    private static String rootText(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String msg = cursor.getMessage();
        if (msg == null || msg.isBlank()) {
            return cursor.getClass().getSimpleName();
        }
        int line = msg.indexOf('\n');
        String first = (line < 0 ? msg : msg.substring(0, line)).trim();
        if (first.length() > 160) {
            first = first.substring(0, 160);
        }
        return first;
    }

    public HealthScoreCatalog catalog() {
        return catalog;
    }

    /**
     * {@code elseIfChain} düz else-if kol sayısıdır. Derinlik sayımına girmez.
     * 50 kol derinlik kuralının birinci payını doldurur. İkinci pay yalnız gerçek derinlikten gelir.
     */
    public record Signals(int cyclomatic, int cognitive, int npath, int ncss, int parameters, int nesting,
                          int elseIfChain) {
        public Signals(int cyclomatic, int cognitive, int npath, int ncss, int parameters, int nesting) {
            this(cyclomatic, cognitive, npath, ncss, parameters, nesting, 0);
        }
    }

    /** Bu kadar düz else-if kolu, derinlik kuralının birinci payını doldurur. */
    private static final int ELSE_IF_CHAIN_FULL = 50;

    public record Hit(String ruleName, boolean lowWeight) {
        public Hit(String ruleName) {
            this(ruleName, false);
        }
    }

    public record MethodScore(
            double complexity,
            String complexityBand,
            RiskLevel complexityLevel,
            double quality,
            String qualityBand,
            List<String> notes,
            /** quality_inputs_json. riskFactors ve qualityScore aynı kalır. */
            String qualityInputsJson) {
    }

    public record MethodInput(int codeLines, double complexity, double quality, boolean excluded) {
    }

    public record ClassScore(
            double complexity,
            RiskLevel complexityLevel,
            String complexityBand,
            double quality,
            String qualityBand,
            int scoredLoc,
            List<String> notes) {
    }

    public record ClassInput(String name, String file, int codeLines, double complexity, double quality) {
    }

    public record FileScore(double complexity, RiskLevel complexityLevel, double quality) {
    }

    public MethodScore scoreMethod(Signals signals, List<Hit> hits, boolean excluded) {
        return scoreMethod(signals, hits, excluded, Set.of());
    }

    /**
     * {@code omittedSignals} içindeki metrik kurallar paydadan da çıkar. Ölçülemeyen
     * parametre 0 puan yazılmaz; metot kalan kurallarla skorlanır.
     */
    public MethodScore scoreMethod(Signals signals, List<Hit> hits, boolean excluded, Set<String> omittedSignals) {
        if (excluded) {
            return new MethodScore(0, "COK_IYI", RiskLevel.LOW, 100, "COK_IYI",
                    List.of("excludedFromHealthScore=true"), cleanQualityInputs());
        }
        List<String> notes = new ArrayList<>();
        double complexityRaw = average(Axis.COMPLEXITY, Scope.METHOD, signals, hits, false, omittedSignals, notes, true, null);
        double complexity = invert(complexityRaw);
        QualitySink sink = new QualitySink();
        double qualityRaw = average(Axis.QUALITY, Scope.METHOD, signals, hits, false, omittedSignals, notes, true, sink);
        return new MethodScore(complexity, complexityBand(complexity), complexityLevel(complexity),
                round(qualityRaw), qualityBand(qualityRaw), List.copyOf(notes), sink.json);
    }

    public ClassScore scoreClass(List<MethodInput> methods, List<Hit> classHits, boolean recordType) {
        long locSum = 0;
        long weightSum = 0;
        double complexityWeighted = 0;
        double qualityWeighted = 0;
        for (MethodInput method : methods) {
            if (method.excluded()) {
                continue;
            }
            long loc = Math.max(0, method.codeLines());
            long weight = Math.max(1, loc);
            locSum += loc;
            weightSum += weight;
            complexityWeighted += method.complexity() * weight;
            qualityWeighted += method.quality() * weight;
        }
        double methodComplexity = weightSum == 0 ? 0 : complexityWeighted / weightSum;
        double methodQuality = weightSum == 0 ? 100 : qualityWeighted / weightSum;
        List<String> notes = new ArrayList<>();
        double classComplexityRaw = average(Axis.COMPLEXITY, Scope.CLASS, Signals(0), classHits, recordType, Set.of(), notes, false, null);
        double classQualityRaw = average(Axis.QUALITY, Scope.CLASS, Signals(0), classHits, recordType, Set.of(), notes, false, null);
        double classComplexity = invert(classComplexityRaw);
        double classQuality = classQualityRaw;
        int w1 = catalog.w1();
        int w2 = catalog.w2();
        int denom = w1 + w2;
        double complexity = denom == 0 ? methodComplexity
                : (w1 * methodComplexity + w2 * classComplexity) / denom;
        double quality = denom == 0 ? methodQuality
                : (w1 * methodQuality + w2 * classQuality) / denom;
        complexity = round(complexity);
        quality = round(quality);
        return new ClassScore(complexity, complexityLevel(complexity), complexityBand(complexity),
                quality, qualityBand(quality), (int) locSum, List.copyOf(notes));
    }

    public FileScore scoreFile(List<ClassScore> classes) {
        long loc = 0;
        double complexity = 0;
        double quality = 0;
        for (ClassScore type : classes) {
            long weight = Math.max(0, type.scoredLoc());
            if (weight == 0) {
                continue;
            }
            loc += weight;
            complexity += type.complexity() * weight;
            quality += type.quality() * weight;
        }
        if (loc == 0) {
            return new FileScore(0, RiskLevel.LOW, 100);
        }
        double c = round(complexity / loc);
        double q = round(quality / loc);
        return new FileScore(c, complexityLevel(c), q);
    }

    public boolean godMethod(Signals signals) {
        Rule cc = catalog.find("CyclomaticComplexity");
        Rule loc = catalog.find("NcssCount");
        int ccMax = cc == null ? 24 : cc.metricMax();
        int locMax = loc == null ? 84 : loc.metricMax();
        return signals.cyclomatic() >= ccMax || signals.ncss() >= locMax;
    }

    public AnalysisReport.RiskModel riskModel() {
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, List<Integer>> thresholds = new LinkedHashMap<>();
        for (Rule rule : catalog.rules()) {
            weights.put(rule.id(), (double) rule.weight());
            if (rule.kind() == Kind.METRIC) {
                thresholds.put(rule.id(), List.of(rule.metricMax()));
            }
        }
        String formula = "karmaşıklık = risk kalemlerinin toplamı; tavanı aşan sayım logaritmik artar, "
                + "uçta pay ikiye katlanır, toplam 100'de durur; "
                + "kalite = 100 - düşüş (kritik 15, yüksek 10, orta 6; "
                + "stil kuralları 15, 10, 8, 7, 3, 2, sonrası 1; 64 adette basamak dolar, fazlası karekökle sürer; "
                + "ham düşüş 100'ü geçince kalite 0; "
                + "aynı kuralın tekrarı karekökle sürer, 16 adette bir dilim daha dolar ve durmaz; boş catch adet başı 10, 4 adette 40, sonrası 2'şer; "
                + "karmaşıklık kaliteyi kesmez); "
                + "sınıf = (w1*satır ağırlıklı metot + w2*sınıf kuralları)/(w1+w2); w1=" + catalog.w1()
                + " w2=" + catalog.w2()
                + "; metrik kolonları count'tur, complexityScore ve qualityScore puandır";
        return new AnalysisReport.RiskModel(catalog.version(), formula, weights, thresholds);
    }

    public List<Map<String, Object>> drill(List<FileMetric> files, Function<String, String> moduleOf) {
        Map<String, List<ClassMetricRef>> byModule = new LinkedHashMap<>();
        for (FileMetric file : files) {
            String module = moduleOf == null ? firstSegment(file.path()) : moduleOf.apply(file.path());
            for (ClassMetric type : file.classes()) {
                byModule.computeIfAbsent(module, k -> new ArrayList<>())
                        .add(new ClassMetricRef(file.path(), type));
            }
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (Map.Entry<String, List<ClassMetricRef>> entry : byModule.entrySet()) {
            List<ClassMetricRef> classes = new ArrayList<>(entry.getValue());
            classes.sort(Comparator.comparingDouble((ClassMetricRef c) -> c.type.riskScore()).reversed());
            int limit = Math.min(catalog.drillClasses(), classes.size());
            List<Map<String, Object>> worst = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                ClassMetricRef ref = classes.get(i);
                List<MethodMetric> methods = new ArrayList<>();
                for (MethodMetric method : ref.type.methods()) {
                    if (!method.excludedFromHealthScore()) {
                        methods.add(method);
                    }
                }
                methods.sort(Comparator.comparingDouble(MethodMetric::riskScore).reversed());
                int methodLimit = Math.min(catalog.drillMethods(), methods.size());
                List<Map<String, Object>> worstMethods = new ArrayList<>();
                for (int m = 0; m < methodLimit; m++) {
                    MethodMetric method = methods.get(m);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("signature", method.signature());
                    row.put("startLine", method.startLine());
                    row.put("complexityScore", method.riskScore());
                    row.put("qualityScore", method.qualityScore());
                    worstMethods.add(row);
                }
                Map<String, Object> classRow = new LinkedHashMap<>();
                classRow.put("file", ref.file);
                classRow.put("name", ref.type.name());
                classRow.put("complexityScore", ref.type.riskScore());
                classRow.put("qualityScore", ref.type.qualityScore());
                classRow.put("worstMethods", worstMethods);
                worst.add(classRow);
            }
            Map<String, Object> moduleRow = new LinkedHashMap<>();
            moduleRow.put("module", entry.getKey());
            moduleRow.put("worstClasses", worst);
            modules.add(moduleRow);
        }
        return modules;
    }

    /** Bu kadar adet bir stil basamağını doldurur. Fazlası aynı karekökle sürer, durmaz. */
    private static final int LOW_QUALITY_FULL_HITS = 64;
    /** İlk ihlalden sonraki adet bu kadar olunca bir dilim daha eklenir. 16 adet, iki ayrı kurala denktir. */
    private static final int EXTRA_HITS_FULL = 15;
    /** Dolu stil kuralları bu sırayla düşer. Altıncı 2, yedinci ve sonrası 1 puandır. */
    private static final double[] STYLE_STEPS = {15, 10, 8, 7, 3, 2};

    private double average(Axis axis, Scope scope, Signals signals, List<Hit> hits, boolean recordType,
                           Set<String> omittedSignals, List<String> notes, boolean explainTotal,
                           QualitySink qualitySink) {
        List<Rule> rules = catalog.rules(axis, scope);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Boolean> sealedOnly = new LinkedHashMap<>();
        if (hits != null) {
            for (Hit hit : hits) {
                for (Rule rule : rules) {
                    if (rule.kind() != Kind.VIOLATION || !matchesHit(rule, hit.ruleName())) {
                        continue;
                    }
                    counts.merge(rule.id(), 1, Integer::sum);
                    sealedOnly.merge(rule.id(), hit.lowWeight(), (left, right) -> left && right);
                }
            }
        }
        if (axis == Axis.QUALITY) {
            return qualityDeduction(rules, signals, counts, sealedOnly, recordType, omittedSignals, notes, explainTotal,
                    qualitySink);
        }
        double weightSum = 0;
        for (Rule rule : rules) {
            if (omittedMetric(rule, omittedSignals)) {
                continue;
            }
            weightSum += weightOf(rule, sealedOnly);
        }
        List<PointLine> parts = new ArrayList<>();
        if (weightSum > 0) {
            for (Rule rule : rules) {
                if (omittedMetric(rule, omittedSignals)) {
                    notes.add(rule.id() + " ölçülemedi, bu parametre atlandı");
                    continue;
                }
                int weight = weightOf(rule, sealedOnly);
                if (recordType && rule.skipOnRecord()) {
                    continue;
                }
                int hitsForRule = counts.getOrDefault(rule.id(), 0);
                double sub = ruleScore(rule, signals, hitsForRule);
                double risk = (weight / weightSum) * (100.0 - sub);
                if (risk <= 0) {
                    continue;
                }
                parts.add(new PointLine(scoreNote(rule, signals, hitsForRule), risk));
            }
        }
        double raw = 0;
        for (PointLine part : parts) {
            raw += part.exact;
        }
        boolean capped = raw > 100;
        if (capped) {
            double scale = 100.0 / raw;
            List<PointLine> scaled = new ArrayList<>();
            for (PointLine part : parts) {
                scaled.add(new PointLine(part.text, part.exact * scale));
            }
            parts = scaled;
        }
        double reported = writeBalanced(parts, notes, " risk+", true);
        if (capped) {
            notes.add("risk kalemleri ham " + round(raw) + ", tavan 100 uygulandı");
        }
        if (explainTotal) {
            notes.add("karmaşıklık " + reported + " = risk kalemlerinin toplamı");
        }
        return 100.0 - reported;
    }

    /**
     * Kalite 100'den başlar. Kritik ilk ihlal 15, yüksek 10, orta 6.
     * Stil kuralları doluysa 15, 10, 8, 7, 3, 2 ve sonrası 1 puan düşer.
     * 64 adet basamağı doldurur, aynı kuralın fazlası karekökle sürer.
     * Aynı kuralın 16. adedi bir dilim daha düşer ve durmaz. İki ayrı kurala denktir.
     * Boş catch adet başı 10, 4 adette 40, her fazlası 2. Karmaşıklık kaliteyi kesmez.
     * Stil düşüşü hata düşüşünden sonra iner.
     */
    private double qualityDeduction(List<Rule> rules, Signals signals, Map<String, Integer> counts,
                                    Map<String, Boolean> sealedOnly, boolean recordType,
                                    Set<String> omittedSignals, List<String> notes, boolean explainTotal,
                                    QualitySink qualitySink) {
        List<PointLine> strong = new ArrayList<>();
        List<StyleHit> styles = new ArrayList<>();
        for (Rule rule : rules) {
            if (omittedMetric(rule, omittedSignals)) {
                notes.add(rule.id() + " ölçülemedi, bu parametre atlandı");
                continue;
            }
            if (recordType && rule.skipOnRecord()) {
                continue;
            }
            int hitsForRule = counts.getOrDefault(rule.id(), 0);
            int weight = weightOf(rule, sealedOnly);
            double slice = qualitySlice(weight);
            boolean style = weight <= catalog.lowPriorityWeight()
                    && rule.kind() == Kind.VIOLATION
                    && hitsForRule > 0
                    && rule.penalty() >= 100;
            if (style) {
                double fill = Math.sqrt(hitsForRule / (double) LOW_QUALITY_FULL_HITS);
                styles.add(new StyleHit(rule, signals, hitsForRule, fill));
                continue;
            }
            double sub = ruleScore(rule, signals, hitsForRule);
            if (sub >= 100) {
                continue;
            }
            double fraction = (100.0 - sub) / 100.0;
            double drop;
            if (rule.penalty() < 100) {
                drop = fraction * 40.0;
                if ("EmptyCatchBlock".equals(rule.id()) && hitsForRule > 4) {
                    drop += 2.0 * (hitsForRule - 4);
                }
            } else {
                drop = fraction * slice;
                if (hitsForRule > 1) {
                    double extra = Math.sqrt((hitsForRule - 1) / (double) EXTRA_HITS_FULL);
                    drop += slice * extra;
                }
            }
            strong.add(new PointLine(scoreNote(rule, signals, hitsForRule), drop,
                    rule.concept(), rule.id(), hitsForRule, qualityGroup(rule, weight, false)));
        }
        styles.sort(Comparator.comparingDouble(StyleHit::fill).reversed());
        List<PointLine> styleLines = new ArrayList<>();
        for (int i = 0; i < styles.size(); i++) {
            double step = styleStep(i);
            StyleHit hit = styles.get(i);
            double drop = step * hit.fill;
            if (drop <= 0) {
                continue;
            }
            styleLines.add(new PointLine(scoreNote(hit.rule, hit.signals, hit.hits), drop,
                    hit.rule.concept(), hit.rule.id(), hit.hits, "style"));
        }
        double defectDrop = 0;
        for (PointLine line : strong) {
            defectDrop += line.exact;
        }
        double styleDrop = 0;
        for (PointLine line : styleLines) {
            styleDrop += line.exact;
        }
        List<PointLine> all = new ArrayList<>(strong);
        all.addAll(styleLines);
        writeBalanced(all, notes, " düşüş=", false);
        double defectQuality = softenQuality(defectDrop);
        double quality = round(Math.max(0, defectQuality - styleDrop));
        if (qualitySink != null) {
            qualitySink.json = qualityInputsJson(quality, strong, styleLines);
        }
        if (explainTotal && styleDrop <= 0) {
            if (defectDrop <= 100) {
                notes.add("kalite " + quality + " = 100 - " + round(defectDrop));
            } else {
                notes.add("ham düşüş " + round(defectDrop) + ", kalite 0");
            }
        }
        return quality;
    }

    /** İlk altı stil basamağı sabittir. Yedinci kuraldan sonra her kural 1 puan iner. */
    private static double styleStep(int index) {
        if (index < STYLE_STEPS.length) {
            return STYLE_STEPS[index];
        }
        return 1;
    }

    private record StyleHit(Rule rule, Signals signals, int hits, double fill) {
    }

    /** Metot kalite JSON'u. Sınıf skoru bunu doldurmaz. */
    private static final class QualitySink {
        String json = cleanQualityInputs();
    }

    private static String cleanQualityInputs() {
        return qualityInputsJson(100, "kalite 100", 0, 0, 0, null, 0, 0, 0, 0, new JsonArray());
    }

    private static String qualityGroup(Rule rule, int weight, boolean style) {
        if ("EmptyCatchBlock".equals(rule.id())) {
            return "emptyCatch";
        }
        if (style) {
            return "style";
        }
        if (weight >= 10) {
            return "critical";
        }
        if (weight >= 6) {
            return "high";
        }
        if (weight >= 3) {
            return "medium";
        }
        return "other";
    }

    /** Kuralların görünen düşüşü ve sıfır tabanı. riskFactors metni değişmez.
     *  steps ve show, ekranın basacağı yoldur. Karmaşıklık bu puana girmez. */
    private static String qualityInputsJson(double quality,
                                            List<PointLine> strong, List<PointLine> styleLines) {
        JsonArray rules = new JsonArray();
        int critical = 0;
        int high = 0;
        int medium = 0;
        int style = 0;
        double defectShown = 0;
        double styleShown = 0;
        for (PointLine line : strong) {
            if (line.shown <= 0) {
                continue;
            }
            defectShown = round(defectShown + line.shown);
            critical += countOf(line, "critical");
            high += countOf(line, "high");
            medium += countOf(line, "medium");
            rules.add(ruleJson(line));
        }
        for (PointLine line : styleLines) {
            if (line.shown <= 0) {
                continue;
            }
            styleShown = round(styleShown + line.shown);
            style += line.hits;
            rules.add(ruleJson(line));
        }
        double rawShown = round(defectShown + styleShown);
        String why = qualityWhy(quality, rawShown, styleShown);
        return qualityInputsJson(quality, why, defectShown, styleShown, rawShown, null, critical, high, medium, style, rules);
    }

    private static int countOf(PointLine line, String group) {
        return group.equals(line.group) ? line.hits : 0;
    }

    private static JsonObject ruleJson(PointLine line) {
        JsonObject rule = new JsonObject();
        rule.addProperty("name", line.name);
        rule.addProperty("id", line.id);
        rule.addProperty("hits", line.hits);
        rule.addProperty("drop", line.shown);
        rule.addProperty("group", line.group);
        rule.addProperty("label", ScoreSheet.label(line.name));
        rule.addProperty("kind", ScoreSheet.kind(line.group));
        rule.addProperty("measure", Integer.toString(line.hits));
        rule.addProperty("points", ScoreSheet.minus(line.shown));
        rule.addProperty("detail", ScoreSheet.qualityDetail(line.name));
        return rule;
    }

    /** Ekran bu diziyi sırasıyla basar. Puanları toplamaz. */
    private static JsonArray qualityRows(JsonArray rules) {
        java.util.List<JsonObject> sorted = new java.util.ArrayList<>();
        for (JsonElement element : rules) {
            sorted.add(element.getAsJsonObject());
        }
        sorted.sort((left, right) -> Double.compare(right.get("drop").getAsDouble(), left.get("drop").getAsDouble()));
        JsonArray rows = new JsonArray();
        for (JsonObject rule : sorted) {
            JsonObject row = new JsonObject();
            row.addProperty("label", rule.get("label").getAsString());
            row.addProperty("kind", rule.get("kind").getAsString());
            row.addProperty("measure", rule.get("measure").getAsString());
            row.addProperty("points", rule.get("points").getAsString());
            row.addProperty("detail", rule.get("detail").getAsString());
            rows.add(row);
        }
        return rows;
    }

    private static String qualityWhy(double quality, double rawShown, double styleShown) {
        if (quality <= 0 && rawShown >= 100) {
            return "ham düşüş " + round(rawShown) + ", kalite 0";
        }
        if (rawShown <= 0) {
            return "kalite " + round(quality);
        }
        StringBuilder why = new StringBuilder();
        double defectShown = round(rawShown - styleShown);
        if (defectShown > 0) {
            why.append("hata düşüşü ").append(defectShown);
        }
        if (styleShown > 0) {
            if (!why.isEmpty()) {
                why.append(", ");
            }
            why.append("stil düşüşü ").append(styleShown);
        }
        if (!why.isEmpty()) {
            why.append(", ");
        }
        why.append("kalite ").append(round(quality));
        return why.toString();
    }

    private static String qualityInputsJson(double score, String why, double defectDrop, double styleDrop, double rawDrop,
                                            JsonObject ceiling, int critical, int high, int medium, int style,
                                            JsonArray rules) {
        JsonObject o = new JsonObject();
        o.addProperty("score", score);
        o.addProperty("why", why);
        o.addProperty("defectDrop", defectDrop);
        o.addProperty("styleDrop", styleDrop);
        o.addProperty("rawDrop", rawDrop);
        if (ceiling == null) {
            o.add("ceiling", JsonNull.INSTANCE);
        } else {
            o.add("ceiling", ceiling);
        }
        o.addProperty("critical", critical);
        o.addProperty("high", high);
        o.addProperty("medium", medium);
        o.addProperty("style", style);
        JsonArray steps = qualitySteps(score, defectDrop, styleDrop);
        o.add("steps", steps);
        o.addProperty("show", qualityShow(steps, score));
        o.add("rows", qualityRows(rules));
        o.add("rules", rules);
        return o.toString();
    }

    /** Ekran bunu sırayla basar. Son after, score ile aynıdır. */
    private static JsonArray qualitySteps(double score, double defectDrop, double styleDrop) {
        JsonArray steps = new JsonArray();
        double cursor = 100;
        steps.add(qualityStep("base", "Başlangıç", null, cursor));
        if (defectDrop > 0) {
            cursor = round(Math.max(0, cursor - defectDrop));
            steps.add(qualityStep("defects", "Hata düşüşü", defectDrop, cursor));
        }
        if (styleDrop > 0) {
            steps.add(qualityStep("style", "Stil düşüşü", styleDrop, round(score)));
        }
        return steps;
    }

    private static JsonObject qualityStep(String id, String label, Double drop, double after) {
        JsonObject step = new JsonObject();
        step.addProperty("id", id);
        step.addProperty("label", label);
        if (drop == null) {
            step.add("drop", JsonNull.INSTANCE);
        } else {
            step.addProperty("drop", drop);
        }
        step.addProperty("after", after);
        return step;
    }

    /** Toplam satırı. 100 - kural toplamı yazılmaz. */
    private static String qualityShow(JsonArray steps, double score) {
        if (steps.size() <= 1) {
            return "kalite " + ScoreSheet.comma(score);
        }
        StringBuilder show = new StringBuilder();
        double cursor = 100;
        for (int i = 1; i < steps.size(); i++) {
            JsonObject step = steps.get(i).getAsJsonObject();
            double drop = step.get("drop").getAsDouble();
            double after = step.get("after").getAsDouble();
            if (!show.isEmpty()) {
                show.append("; ");
            }
            String id = step.get("id").getAsString();
            if ("defects".equals(id)) {
                show.append("100 − ").append(ScoreSheet.comma(drop)).append(" = ").append(ScoreSheet.comma(after));
            } else if (round(cursor - drop) + 0.001 < after || after <= 0 && cursor - drop < -0.001) {
                show.append("stil −").append(ScoreSheet.comma(drop)).append(", kalite ").append(ScoreSheet.comma(after));
            } else {
                show.append("stil −").append(ScoreSheet.comma(drop)).append(" = ").append(ScoreSheet.comma(after));
            }
            cursor = after;
        }
        return show.toString();
    }

    /** 100'e kadar birebir iner. Üstü 0 yazılır. */
    private static double softenQuality(double rawDeduction) {
        return round(Math.max(0, 100.0 - rawDeduction));
    }

    /** Satırları 3 ondalığa yuvarlar; son satır toplamı tutturur. */
    private static double writeBalanced(List<PointLine> parts, List<String> notes, String label, boolean capAt100) {
        double exact = 0;
        for (PointLine part : parts) {
            exact += part.exact;
        }
        double reported = round(exact);
        if (capAt100) {
            reported = Math.min(100, reported);
        }
        double acc = 0;
        for (int i = 0; i < parts.size(); i++) {
            double shown = i == parts.size() - 1 ? round(reported - acc) : round(parts.get(i).exact);
            if (i < parts.size() - 1) {
                acc = round(acc + shown);
            }
            parts.get(i).shown = shown;
            if (shown > 0) {
                notes.add(parts.get(i).text + label + shown);
            }
        }
        return reported;
    }

    private String scoreNote(Rule rule, Signals signals, int hitsForRule) {
        String note = rule.concept() + " " + rule.id();
        if (rule.kind() == Kind.METRIC) {
            int value = signal(signals, rule.signal());
            note += " sayım=" + value + " tavan=" + rule.metricMax();
            if ("maxNestingDepth".equals(rule.signal()) && signals.elseIfChain() > 1) {
                int fromChain = elseIfFirstShare(signals.elseIfChain(), Math.max(1, rule.metricMax()));
                if (fromChain > value) {
                    note += ", else-if " + signals.elseIfChain() + " kol birinci pay";
                }
            }
            if (rule.overflowMax() > rule.metricMax() && value > rule.metricMax()) {
                note += ", " + rule.overflowMax() + "'e kadar artar";
            }
        } else if (hitsForRule > 0) {
            note += " adet=" + hitsForRule;
        }
        return note;
    }

    private static final class PointLine {
        final String text;
        final double exact;
        final String name;
        final String id;
        final int hits;
        final String group;
        double shown;

        PointLine(String text, double exact) {
            this(text, exact, "", "", 0, "");
        }

        PointLine(String text, double exact, String name, String id, int hits, String group) {
            this.text = text;
            this.exact = exact;
            this.name = name;
            this.id = id;
            this.hits = hits;
            this.group = group;
        }
    }

    private static double qualitySlice(int weight) {
        if (weight >= 10) {
            return 15;
        }
        if (weight >= 6) {
            return 10;
        }
        if (weight >= 3) {
            return 6;
        }
        return 20;
    }

    private static boolean matchesHit(Rule rule, String ruleName) {
        if (ruleName == null) {
            return false;
        }
        if (ruleName.equals(rule.id())) {
            return true;
        }
        return rule.aliases().contains(ruleName);
    }

    private static boolean omittedMetric(Rule rule, Set<String> omittedSignals) {
        return rule.kind() == Kind.METRIC
                && rule.signal() != null
                && omittedSignals != null
                && omittedSignals.contains(rule.signal());
    }

    private int weightOf(Rule rule, Map<String, Boolean> sealedOnly) {
        if (Boolean.TRUE.equals(sealedOnly.get(rule.id()))) {
            return Math.max(1, catalog.lowPriorityWeight());
        }
        return rule.weight();
    }

    private double ruleScore(Rule rule, Signals signals, int hits) {
        if (rule.kind() == Kind.METRIC) {
            int value = Math.max(0, signal(signals, rule.signal()));
            int max = Math.max(1, rule.metricMax());
            int baseValue = metricBaseValue(rule, signals, value, max);
            double base;
            if (rule.logScale() && max > 1) {
                base = Math.log(Math.max(1, baseValue)) / Math.log(max);
            } else {
                base = baseValue / (double) max;
            }
            base = Math.min(1, Math.max(0, base));
            double extra = 0;
            int far = rule.overflowMax();
            if (!rule.logScale() && far > max && value > max) {
                extra = Math.log(value / (double) max) / Math.log(far / (double) max);
                extra = Math.min(1, Math.max(0, extra));
            }
            return 100.0 - (base + extra) * 100.0;
        }
        if (hits <= 0) {
            return 100;
        }
        int penalty = rule.penalty() < 1 ? 100 : rule.penalty();
        return Math.max(0, 100.0 - Math.min(100, hits * (double) penalty));
    }

    /** Gerçek derinlik taşmayı belirler. Else-if kolu yalnız birinci payı, tavanı geçmeden doldurur.
     *  Servis metodu tek CSBag ile yazılır. 0 ve 1 parametre karmaşıklığa puan yazmaz. */
    private static int metricBaseValue(Rule rule, Signals signals, int value, int max) {
        if ("parameterCount".equals(rule.signal()) && value <= 1) {
            return 0;
        }
        if (!"maxNestingDepth".equals(rule.signal()) || signals.elseIfChain() <= 1) {
            return value;
        }
        return Math.max(value, elseIfFirstShare(signals.elseIfChain(), max));
    }

    private static int elseIfFirstShare(int chain, int max) {
        if (chain <= 1) {
            return 0;
        }
        return Math.min(max, chain * max / ELSE_IF_CHAIN_FULL);
    }

    private static int signal(Signals signals, String name) {
        return switch (name) {
            case "cyclomaticComplexity" -> signals.cyclomatic();
            case "cognitiveComplexity" -> signals.cognitive();
            case "npathComplexity" -> signals.npath();
            case "ncssCount" -> signals.ncss();
            case "parameterCount" -> signals.parameters();
            case "maxNestingDepth" -> signals.nesting();
            default -> 0;
        };
    }

    private static Signals Signals(int ignored) {
        return new Signals(0, 0, 0, 0, 0, 0);
    }

    static double invert(double rawClean) {
        return round(100.0 - rawClean);
    }

    public static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public static String qualityBand(double score) {
        if (score >= 90) {
            return "COK_IYI";
        }
        if (score >= 75) {
            return "IYI";
        }
        if (score >= 50) {
            return "ORTA";
        }
        if (score >= 25) {
            return "ZAYIF";
        }
        return "KRITIK";
    }

    public static String complexityBand(double reported) {
        if (reported <= 24) {
            return "COK_IYI";
        }
        if (reported <= 49) {
            return "IYI";
        }
        if (reported <= 74) {
            return "ORTA";
        }
        if (reported <= 89) {
            return "ZAYIF";
        }
        return "KRITIK";
    }

    public static RiskLevel complexityLevel(double reported) {
        return RiskLevel.fromScore(reported);
    }

    private static String firstSegment(String path) {
        if (path == null || path.isBlank()) {
            return ".";
        }
        int slash = path.indexOf('/');
        return slash <= 0 ? "." : path.substring(0, slash);
    }

    private record ClassMetricRef(String file, ClassMetric type) {
    }
}
