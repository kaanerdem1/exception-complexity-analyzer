package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmdSignatureDedupTest {

    private static final String SOURCE = """
            package bean;
            public class CoverPledgeDefMapper {
                CoverPledge getCoverPledge() { return cover; }
                CoverPledge getCoverPledge(String id) {
                    if (id == null) { return null; }
                    return load(id);
                }
                void foo(String a, int b) {
                    if (a != null && b > 0) { System.out.println(a + b); }
                }
                private CoverPledge cover;
                private CoverPledge load(String id) { return cover; }
            }
            class CoverPledge {}
            """;

    @Test
    void signaturesUseTypesNotArityLabels() {
        FileMetric file = PmdFileMetricsBuilder.build(
                "CoverPledgeDefMapper.java", SOURCE, ParserConfiguration.LanguageLevel.JAVA_17,
                List.of(), new RiskCalculator(), false);

        Set<String> signatures = new HashSet<>();
        int methodRows = 0;
        for (ClassMetric type : file.classes()) {
            if (!"CoverPledgeDefMapper".equals(type.name())) {
                continue;
            }
            for (MethodMetric m : type.methods()) {
                methodRows++;
                assertFalse(m.signature().contains("param"), m.signature());
                signatures.add(m.signature());
            }
        }
        assertTrue(signatures.contains("getCoverPledge()"), signatures.toString());
        assertTrue(signatures.contains("getCoverPledge(String)"), signatures.toString());
        assertTrue(signatures.contains("foo(String, int)") || signatures.contains("foo(String,int)"),
                signatures.toString());
        assertEquals(methodRows, signatures.size(), "aynı imza iki kez");
    }
}
