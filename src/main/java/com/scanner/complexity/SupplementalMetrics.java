package com.scanner.complexity;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.RiskCalculator.Assessment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Halstead, smell listeleri, LCOM3 ve bağımlılık metrikleri — risk skoru {@link ComplexityVisitor} /
 * {@link RiskCalculator} içinde birleşik enterprise profil ile hesaplanır.
 */
final class SupplementalMetrics {

    private SupplementalMetrics() {
    }

    static List<ClassMetric> enrich(CompilationUnit cu, List<ClassMetric> classes, RiskCalculator riskCalculator,
                                    Map<String, MethodMetric> previousMethods) {
        Map<String, TypeDeclaration<?>> typesByName = indexTypes(cu);
        List<ClassMetric> enriched = new ArrayList<>(classes.size());
        for (ClassMetric type : classes) {
            TypeDeclaration<?> decl = resolveTypeDeclaration(typesByName, type.name());
            Set<String> efferent = decl == null ? Set.of()
                    : TypeDependencyAnalyzer.collectEfferentTypes(decl);
            List<String> dependentTypes = efferent.stream().limit(40).toList();

            LegacyTypeMetrics legacyType = decl == null ? LegacyTypeMetrics.empty()
                    : LegacyAstMetricsVisitor.analyzeType(decl);

            List<MethodMetric> methods = new ArrayList<>(type.methods().size());
            List<Set<String>> fieldAccessSets = new ArrayList<>();
            for (MethodMetric method : type.methods()) {
                MethodMetric enrichedMethod = enrichMethod(type.name(), method, previousMethods, legacyType);
                methods.add(enrichedMethod);
                fieldAccessSets.add(Set.copyOf(enrichedMethod.accessedFieldNames()));
            }

            double lcom3 = CohesionAnalyzer.lcom3(legacyType.fieldNames(), fieldAccessSets);
            boolean godClass = lcom3 > 1.0 && legacyType.fieldNames().size() >= 4 && methods.size() >= 5;

            int swallowed = sumSmell(methods, SmellKind.SWALLOWED);
            int generic = sumSmell(methods, SmellKind.GENERIC);
            int raw = sumSmell(methods, SmellKind.RAW);
            int concat = sumSmell(methods, SmellKind.CONCAT);
            int hardcoded = sumSmell(methods, SmellKind.HARDCODED);

            HalsteadMetrics classHalstead = legacyType.classHalstead();
            RiskProfile.ClassScanValues classScan = new RiskProfile.ClassScanValues(
                    type.codeLines(), type.weightedMethodComplexity(), type.publicMethodCount(),
                    type.efferentCouplingProxy(), efferent.size(), (int) Math.round(lcom3 * 100.0),
                    (int) Math.min(Integer.MAX_VALUE, Math.round(classHalstead.effort())),
                    swallowed, generic, raw, concat, hardcoded, godClass ? 1 : 0);
            Assessment classRisk = riskCalculator.assessClassAggregate(methods, classScan);

            enriched.add(new ClassMetric(type.name(), type.kind(), type.startLine(), type.endLine(),
                    type.methodCount(), type.publicMethodCount(), type.codeLines(), type.efferentCouplingProxy(),
                    type.weightedMethodComplexity(), type.maxMethodComplexity(), type.averageMethodComplexity(),
                    classRisk.score(), classRisk.level(), classRisk.factors(), List.copyOf(methods),
                    classHalstead, dependentTypes, round2(lcom3), godClass,
                    swallowed, generic, raw, concat, hardcoded));
        }
        return enriched;
    }

    private static MethodMetric enrichMethod(String typeName, MethodMetric method,
                                             Map<String, MethodMetric> previousMethods,
                                             LegacyTypeMetrics legacyType) {
        String cacheKey = typeName + "#" + method.signature();
        LegacyMethodMetrics legacy = resolveLegacyMethodMetrics(legacyType, typeName, method.signature());
        MethodMetric previous = previousMethods.get(cacheKey);
        String methodHash = legacy.methodHash();
        boolean reuse = previous != null && methodHash.equals(previous.methodHash()) && !methodHash.isEmpty();

        HalsteadMetrics halstead;
        List<ExceptionSmell> exceptionSmells;
        List<CodeSmell> codeSmells;
        if (reuse) {
            halstead = previous.halstead();
            exceptionSmells = previous.exceptionSmells();
            codeSmells = previous.codeSmells();
        } else {
            halstead = legacy.halstead();
            exceptionSmells = legacy.exceptionSmells();
            codeSmells = legacy.codeSmells();
        }

        return new MethodMetric(method.name(), method.kind(), method.signature(), method.returnType(),
                method.startLine(), method.endLine(),
                method.cyclomaticComplexity(), method.physicalLines(), method.codeLines(), method.logicalStatements(),
                method.cognitiveComplexity(), method.exitPoints(), method.catchClauses(), method.switchCases(),
                method.outboundDistinctCalls(), method.lambdaCount(), method.maxTryNestingDepth(),
                method.localVariableCount(), method.maxMethodCallChainLength(), method.emptyCatchBlocks(),
                method.catchExceptionOrThrowable(), method.catchWithOnlyPrintStackTrace(),
                method.primitiveObsessionIndex(), method.maxBooleanOperatorsInCondition(), method.maxNestingDepth(),
                method.parameterCount(), method.godMethod(), method.riskScore(), method.riskLevel(),
                method.riskFactors(), method.riskBreakdown(), halstead, exceptionSmells, codeSmells,
                methodHash.isEmpty() ? legacy.methodHash() : methodHash, reuse || method.analysisReused(),
                legacy.accessedFieldNames(), method.bodyHash(), method.structuralHash(),
                method.npathComplexity(), method.ncssCount(), method.metricsImputed());
    }

    /** {@link ClassMetric#name()} is often a simple/nested name; index keys are FQN. */
    private static TypeDeclaration<?> resolveTypeDeclaration(Map<String, TypeDeclaration<?>> typesByName,
                                                             String typeName) {
        TypeDeclaration<?> direct = typesByName.get(typeName);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, TypeDeclaration<?>> entry : typesByName.entrySet()) {
            String key = entry.getKey();
            if (key.equals(typeName) || key.endsWith("." + typeName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Same suffix rules as {@link ComplexityVisitor} legacy index lookup. */
    private static LegacyMethodMetrics resolveLegacyMethodMetrics(LegacyTypeMetrics legacyType, String typeName,
                                                                  String signature) {
        Map<String, LegacyMethodMetrics> methods = legacyType.methods();
        LegacyMethodMetrics hit = methods.get(typeName + "#" + signature);
        if (hit != null) {
            return hit;
        }
        String suffix = "#" + signature;
        for (Map.Entry<String, LegacyMethodMetrics> entry : methods.entrySet()) {
            if (!entry.getKey().endsWith(suffix)) {
                continue;
            }
            String cls = entry.getKey().substring(0, entry.getKey().length() - suffix.length());
            if (cls.equals(typeName) || cls.endsWith("." + typeName) || typeName.endsWith("." + cls)) {
                return entry.getValue();
            }
        }
        return LegacyMethodMetrics.empty();
    }

    private static Map<String, TypeDeclaration<?>> indexTypes(CompilationUnit cu) {
        Map<String, TypeDeclaration<?>> map = new HashMap<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            indexTypeRecursive(type, map);
        }
        return map;
    }

    private static void indexTypeRecursive(TypeDeclaration<?> type, Map<String, TypeDeclaration<?>> map) {
        map.put(type.getFullyQualifiedName().orElse(type.getNameAsString()), type);
        for (TypeDeclaration<?> nested : type.findAll(TypeDeclaration.class)) {
            if (nested != type) {
                String key = nested.getFullyQualifiedName()
                        .orElse(type.getNameAsString() + "." + nested.getNameAsString());
                map.put(key, nested);
            }
        }
    }

    private enum SmellKind { SWALLOWED, GENERIC, RAW, CONCAT, HARDCODED }

    private static int sumSmell(List<MethodMetric> methods, SmellKind kind) {
        return methods.stream().mapToInt(m -> switch (kind) {
            case SWALLOWED -> SmellCounts.swallowed(m.exceptionSmells());
            case GENERIC -> SmellCounts.genericCatch(m.exceptionSmells());
            case RAW -> SmellCounts.rawType(m.codeSmells());
            case CONCAT -> SmellCounts.concatInLoop(m.codeSmells());
            case HARDCODED -> SmellCounts.hardcoded(m.codeSmells());
        }).sum();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    record LegacyTypeMetrics(Set<String> fieldNames, HalsteadMetrics classHalstead, Map<String, LegacyMethodMetrics> methods) {
        static LegacyTypeMetrics empty() {
            return new LegacyTypeMetrics(Set.of(), HalsteadMetrics.EMPTY, Map.of());
        }
    }

    record LegacyMethodMetrics(
            String methodHash,
            HalsteadMetrics halstead,
            List<ExceptionSmell> exceptionSmells,
            List<CodeSmell> codeSmells,
            List<String> accessedFieldNames) {

        static LegacyMethodMetrics empty() {
            return new LegacyMethodMetrics("", HalsteadMetrics.EMPTY, List.of(), List.of(), List.of());
        }
    }
}
