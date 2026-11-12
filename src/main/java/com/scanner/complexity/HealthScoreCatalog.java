package com.scanner.complexity;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** PDF skor kataloğu: kural, eksen, ağırlık, tavan. */
public final class HealthScoreCatalog {

    public enum Axis {
        COMPLEXITY, QUALITY
    }

    public enum Scope {
        METHOD, CLASS
    }

    public enum Kind {
        METRIC, VIOLATION
    }

    public record Rule(
            String id,
            String concept,
            Axis axis,
            Scope scope,
            int weight,
            Kind kind,
            String signal,
            int metricMax,
            boolean skipOnRecord,
            List<String> aliases,
            int penalty) {
    }

    private final String version;
    private final int w1;
    private final int w2;
    private final int drillClasses;
    private final int drillMethods;
    private final int lowPriorityWeight;
    private final List<Rule> rules;
    private final Map<String, Rule> byName;

    private HealthScoreCatalog(String version, int w1, int w2, int drillClasses, int drillMethods,
                               int lowPriorityWeight, List<Rule> rules) {
        this.version = version;
        this.w1 = w1;
        this.w2 = w2;
        this.drillClasses = drillClasses;
        this.drillMethods = drillMethods;
        this.lowPriorityWeight = lowPriorityWeight;
        this.rules = List.copyOf(rules);
        Map<String, Rule> index = new LinkedHashMap<>();
        for (Rule rule : rules) {
            index.put(rule.id(), rule);
            for (String alias : rule.aliases()) {
                index.putIfAbsent(alias, rule);
            }
        }
        this.byName = Map.copyOf(index);
    }

    public static HealthScoreCatalog loadDefault() {
        try (InputStream in = HealthScoreCatalog.class.getResourceAsStream("/config/health-score-catalog.yaml")) {
            if (in == null) {
                throw new IllegalStateException("health-score-catalog.yaml bulunamadı");
            }
            return parse(new String(in.readAllBytes()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static HealthScoreCatalog parse(String yaml) {
        Map<String, Object> root = new Yaml().load(new StringReader(yaml));
        Map<String, Integer> priorityWeights = new LinkedHashMap<>();
        Object pw = root.get("priority_weights");
        if (pw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                priorityWeights.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), asInt(e.getValue(), 1));
            }
        }
        int w1 = 50;
        int w2 = 50;
        int drillClasses = 3;
        int drillMethods = 5;
        Object agg = root.get("aggregation");
        if (agg instanceof Map<?, ?> map) {
            w1 = asInt(map.get("w1"), 50);
            w2 = asInt(map.get("w2"), 50);
            drillClasses = asInt(map.get("drill_worst_classes"), 3);
            drillMethods = asInt(map.get("drill_worst_methods"), 5);
        }
        List<Rule> rules = new ArrayList<>();
        Object rawRules = root.get("rules");
        if (rawRules instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rules.add(readRule(map, priorityWeights));
                }
            }
        }
        int low = priorityWeights.getOrDefault("low", 1);
        return new HealthScoreCatalog(stringOr(root.get("version"), "health-v1"), w1, w2, drillClasses, drillMethods,
                low, rules);
    }

    private static Rule readRule(Map<?, ?> map, Map<String, Integer> priorityWeights) {
        String priority = stringOr(map.get("priority"), "low").toLowerCase(Locale.ROOT);
        int weight = map.get("weight") == null
                ? priorityWeights.getOrDefault(priority, 1)
                : asInt(map.get("weight"), 1);
        List<String> aliases = new ArrayList<>();
        Object rawAliases = map.get("aliases");
        if (rawAliases instanceof List<?> list) {
            for (Object alias : list) {
                aliases.add(String.valueOf(alias));
            }
        }
        Axis axis = "quality".equalsIgnoreCase(stringOr(map.get("axis"), "complexity"))
                ? Axis.QUALITY : Axis.COMPLEXITY;
        Scope scope = "class".equalsIgnoreCase(stringOr(map.get("scope"), "method"))
                ? Scope.CLASS : Scope.METHOD;
        Kind kind = "metric".equalsIgnoreCase(stringOr(map.get("kind"), "violation"))
                ? Kind.METRIC : Kind.VIOLATION;
        int penalty = asInt(map.get("penalty"), 100);
        if (penalty < 1) {
            penalty = 100;
        }
        return new Rule(
                stringOr(map.get("id"), ""),
                stringOr(map.get("concept"), stringOr(map.get("id"), "")),
                axis,
                scope,
                Math.max(0, weight),
                kind,
                stringOr(map.get("signal"), ""),
                asInt(map.get("metric_max"), 1),
                Boolean.TRUE.equals(map.get("skip_on_record")),
                List.copyOf(aliases),
                Math.min(100, penalty));
    }

    public String version() {
        return version;
    }

    public int w1() {
        return w1;
    }

    public int w2() {
        return w2;
    }

    public int drillClasses() {
        return drillClasses;
    }

    public int drillMethods() {
        return drillMethods;
    }

    /** PDF: sealed switch ihlali düşük öncelik ağırlığına iner. */
    public int lowPriorityWeight() {
        return lowPriorityWeight;
    }

    public List<Rule> rules() {
        return rules;
    }

    public Rule find(String ruleName) {
        return ruleName == null ? null : byName.get(ruleName);
    }

    public List<Rule> rules(Axis axis, Scope scope) {
        List<Rule> out = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.axis() == axis && rule.scope() == scope && rule.weight() > 0) {
                out.add(rule);
            }
        }
        return out;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringOr(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
