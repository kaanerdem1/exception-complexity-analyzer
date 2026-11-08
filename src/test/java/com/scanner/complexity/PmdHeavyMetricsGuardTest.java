package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmdHeavyMetricsGuardTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void branchedMethodSkipsNpathKeepsRiskScore() {
        FileMetric file = PmdFileMetricsBuilder.build(
                "BranchedService.java", branchedSource(22), ParserConfiguration.LanguageLevel.JAVA_17,
                List.of(), new RiskCalculator(), false);
        MethodMetric method = named(file, "evaluate");
        assertTrue(method.cyclomaticComplexity() >= PmdHeavyMetricGuard.CYCLO_HEAVY, "cc=" + method.cyclomaticComplexity());
        assertEquals(PmdMetricsAligner.NPATH_CAP, method.npathComplexity());
        assertTrue(method.riskScore() >= 30, "score=" + method.riskScore());
        assertTrue(method.cognitiveComplexity() > 0);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void simpleMethodStillComputesRealNpath() {
        String src = """
                package demo;
                public class Tiny {
                    int add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        FileMetric file = PmdFileMetricsBuilder.build(
                "Tiny.java", src, ParserConfiguration.LanguageLevel.JAVA_17,
                List.of(), new RiskCalculator(), false);
        MethodMetric method = named(file, "add");
        assertTrue(method.cyclomaticComplexity() < PmdHeavyMetricGuard.CYCLO_HEAVY);
        assertTrue(method.npathComplexity() < PmdMetricsAligner.NPATH_CAP);
        assertTrue(method.npathComplexity() >= 1);
    }

    private static MethodMetric named(FileMetric file, String name) {
        for (ClassMetric type : file.classes()) {
            for (MethodMetric m : type.methods()) {
                if (name.equals(m.name())) {
                    return m;
                }
            }
        }
        throw new AssertionError("method not found: " + name);
    }

    private static String branchedSource(int branches) {
        StringBuilder body = new StringBuilder();
        body.append("package demo;\npublic class BranchedService {\n");
        body.append("    int evaluate(int x, String flag) {\n");
        body.append("        int n = 0;\n");
        for (int i = 0; i < branches; i++) {
            body.append("        if (x > ").append(i).append(") { n += ").append(i).append("; }\n");
        }
        body.append("        if (flag != null && flag.length() > 2) { n++; }\n");
        body.append("        return n;\n    }\n}\n");
        return body.toString();
    }
}
