package com.scanner.complexity;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.HashMap;
import java.util.Map;

/** Pre-indexes {@link SupplementalMetrics.LegacyMethodMetrics} for {@link ComplexityVisitor}. */
final class LegacyMetricsIndex {

    private LegacyMetricsIndex() {
    }

    static Map<String, SupplementalMetrics.LegacyMethodMetrics> fromCompilationUnit(CompilationUnit cu) {
        Map<String, SupplementalMetrics.LegacyMethodMetrics> index = new HashMap<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            indexTypeRecursive(type, index);
        }
        return index;
    }

    private static void indexTypeRecursive(TypeDeclaration<?> type,
                                           Map<String, SupplementalMetrics.LegacyMethodMetrics> index) {
        SupplementalMetrics.LegacyTypeMetrics legacy = LegacyAstMetricsVisitor.analyzeType(type);
        index.putAll(legacy.methods());
        for (TypeDeclaration<?> nested : type.findAll(TypeDeclaration.class)) {
            if (nested != type) {
                SupplementalMetrics.LegacyTypeMetrics nestedLegacy = LegacyAstMetricsVisitor.analyzeType(nested);
                index.putAll(nestedLegacy.methods());
            }
        }
    }
}
