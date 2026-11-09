package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.ClassMetric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dört ölçüm tavanını aynı koşudan, p90 olarak basar.
 * NPath ruleset kuralı çalışmaz. Ağır metotta atlanan NPath sayımı persentile girmez.
 */
public final class MetricCeilingCalibration {

    private MetricCeilingCalibration() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: MetricCeilingCalibration <java-root>...");
            System.exit(2);
        }
        RiskCalculator risk = new RiskCalculator();
        List<Integer> cc = new ArrayList<>();
        List<Integer> cognitive = new ArrayList<>();
        List<Integer> ncss = new ArrayList<>();
        List<Integer> npath = new ArrayList<>();
        int files = 0;
        int failed = 0;
        for (String arg : args) {
            Path root = Path.of(arg);
            JavaSourceDiscovery.Result found = JavaSourceDiscovery.collect(
                    root, root, new ArrayList<>(), ScanOptions.defaults());
            for (Path file : found.paths()) {
                files++;
                String relative = root.relativize(file).toString();
                String source = Files.readString(file);
                try {
                    var metric = PmdFileMetricsBuilder.build(relative, source,
                            ParserConfiguration.LanguageLevel.JAVA_17, List.of(), risk, false);
                    for (ClassMetric type : metric.classes()) {
                        for (MethodMetric method : type.methods()) {
                            if (method.excludedFromHealthScore() || method.metricsImputed()) {
                                continue;
                            }
                            cc.add(method.cyclomaticComplexity());
                            cognitive.add(method.cognitiveComplexity());
                            ncss.add(method.ncssCount());
                            if (method.npathComplexity() < PmdMetricsAligner.NPATH_CAP) {
                                npath.add(method.npathComplexity());
                            }
                        }
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    System.err.println("skip " + relative + ": " + ex.getMessage());
                }
            }
        }
        System.out.println("files=" + files + " failed=" + failed);
        System.out.println("methods=" + cc.size() + " npathMeasured=" + npath.size());
        print("cc", cc);
        print("cognitive", cognitive);
        print("ncss", ncss);
        print("npath", npath);
    }

    private static void print(String name, List<Integer> values) {
        System.out.println(name
                + " p75=" + percentile(values, 0.75)
                + " p90=" + percentile(values, 0.90)
                + " p95=" + percentile(values, 0.95)
                + " p99=" + percentile(values, 0.99)
                + " max=" + percentile(values, 1.0));
    }

    static int percentile(List<Integer> values, double p) {
        if (values.isEmpty()) {
            return 1;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return Math.max(1, sorted.get(index));
    }
}
