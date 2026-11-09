package com.scanner.complexity;

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
import java.util.function.Function;

/**
 * İki bağımsız skor. Karmaşıklık raporda yüksek = kötü (100 − ham). Kalite yüksek = iyi.
 * Sınıf = w1 × metot LOC ortalaması + w2 × sınıf kuralları. Modül = sınıf LOC ortalaması.
 */
public final class HealthScoreEngine {

    private static final HealthScoreEngine DEFAULT = new HealthScoreEngine(HealthScoreCatalog.loadDefault());

    private final HealthScoreCatalog catalog;

    public HealthScoreEngine(HealthScoreCatalog catalog) {
        this.catalog = catalog;
    }

    public static HealthScoreEngine get() {
        return DEFAULT;
    }

    public HealthScoreCatalog catalog() {
        return catalog;
    }

    public record Signals(int cyclomatic, int cognitive, int npath, int ncss, int parameters, int nesting) {
    }

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
            List<String> notes) {
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
        if (excluded) {
            return new MethodScore(0, "COK_IYI", RiskLevel.LOW, 100, "COK_IYI",
                    List.of("excludedFromHealthScore=true"));
        }
        List<String> notes = new ArrayList<>();
        double complexityRaw = average(Axis.COMPLEXITY, Scope.METHOD, signals, hits, false, notes);
        double qualityRaw = average(Axis.QUALITY, Scope.METHOD, signals, hits, false, notes);
        double complexity = invert(complexityRaw);
        return new MethodScore(complexity, complexityBand(complexity), complexityLevel(complexity),
                round(qualityRaw), qualityBand(qualityRaw), List.copyOf(notes));
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
        double classComplexityRaw = average(Axis.COMPLEXITY, Scope.CLASS, Signals(0), classHits, recordType, notes);
        double classQualityRaw = average(Axis.QUALITY, Scope.CLASS, Signals(0), classHits, recordType, notes);
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
        String formula = "quality = weighted(rule scores); complexity = 100 - weighted(rule scores); "
                + "class = (w1*locMethodAvg + w2*classRules)/(w1+w2); w1=" + catalog.w1()
                + " w2=" + catalog.w2() + "; trivial get/set/is excluded from both sides";
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

    private double average(Axis axis, Scope scope, Signals signals, List<Hit> hits, boolean recordType,
                           List<String> notes) {
        List<Rule> rules = catalog.rules(axis, scope);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Boolean> sealedOnly = new LinkedHashMap<>();
        if (hits != null) {
            for (Hit hit : hits) {
                Rule rule = catalog.find(hit.ruleName());
                if (rule == null || rule.kind() != Kind.VIOLATION) {
                    continue;
                }
                counts.merge(rule.id(), 1, Integer::sum);
                sealedOnly.merge(rule.id(), hit.lowWeight(), (left, right) -> left && right);
            }
        }
        double weightSum = 0;
        for (Rule rule : rules) {
            weightSum += weightOf(rule, sealedOnly);
        }
        if (weightSum <= 0) {
            return 100;
        }
        double acc = 0;
        for (Rule rule : rules) {
            int weight = weightOf(rule, sealedOnly);
            if (recordType && rule.skipOnRecord()) {
                acc += (weight / weightSum) * 100;
                continue;
            }
            double sub = ruleScore(rule, signals, counts.getOrDefault(rule.id(), 0));
            acc += (weight / weightSum) * sub;
            if (sub < 80) {
                notes.add(rule.concept() + " " + rule.id() + " sub=" + round(sub));
            }
        }
        return acc;
    }

    private int weightOf(Rule rule, Map<String, Boolean> sealedOnly) {
        if (Boolean.TRUE.equals(sealedOnly.get(rule.id()))) {
            return Math.max(1, catalog.lowPriorityWeight());
        }
        return rule.weight();
    }

    private double ruleScore(Rule rule, Signals signals, int hits) {
        if (rule.kind() == Kind.METRIC) {
            int value = signal(signals, rule.signal());
            int max = Math.max(1, rule.metricMax());
            return Math.max(0, 100.0 - (value / (double) max) * 100.0);
        }
        if (hits <= 0) {
            return 100;
        }
        return Math.max(0, 100.0 - Math.min(100, hits * 100.0));
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
