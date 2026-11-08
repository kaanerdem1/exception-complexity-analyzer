package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.RiskCalculator.Assessment;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.ast.FileAnalysisException;
import net.sourceforge.pmd.lang.ast.Parser;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProcessor;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.metrics.Metric;
import net.sourceforge.pmd.lang.metrics.MetricOptions;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cyclomatic / cognitive complexity and NCSS / NPath from PMD ({@link JavaMetrics}) so
 * parser counts match maven-pmd-plugin rules.
 */
final class PmdMetricsAligner {

    static final int NPATH_CAP = 2_000_000_000;

    private PmdMetricsAligner() {
    }

    public record PmdMethodMetrics(int cyclomaticComplexity, int cognitiveComplexity, int ncssCount,
                                   int npathComplexity) {
    }

    record MethodKey(String typeName, String methodName, int startLine) {
    }

    static List<ClassMetric> align(String source, String relativePath,
                                   ParserConfiguration.LanguageLevel languageLevel,
                                   List<ClassMetric> classes, RiskCalculator riskCalculator) {
        Map<MethodKey, PmdMethodMetrics> byKey;
        try {
            byKey = extract(source, relativePath, languageLevel);
        } catch (RuntimeException e) {
            System.err.println("[STANDALONE] PMD metric alignment skipped for " + relativePath + ": "
                    + e.getMessage());
            return classes;
        }
        if (byKey.isEmpty()) {
            return classes;
        }
        List<ClassMetric> aligned = new ArrayList<>(classes.size());
        for (ClassMetric type : classes) {
            List<MethodMetric> methods = new ArrayList<>(type.methods().size());
            int wmc = 0;
            int maxCc = 0;
            for (MethodMetric method : type.methods()) {
                PmdMethodMetrics pmd = lookupPmdMetrics(byKey, simpleTypeName(type.name()), method.name(),
                        method.startLine());
                if (pmd == null) {
                    methods.add(method);
                    wmc += method.cyclomaticComplexity();
                    maxCc = Math.max(maxCc, method.cyclomaticComplexity());
                    continue;
                }
                MethodMetric updated = withPmdMetrics(method, pmd, riskCalculator);
                methods.add(updated);
                wmc += updated.cyclomaticComplexity();
                maxCc = Math.max(maxCc, updated.cyclomaticComplexity());
            }
            aligned.add(copyClassWithMethods(type, methods, wmc, maxCc, riskCalculator));
        }
        return aligned;
    }

    private static PmdMethodMetrics lookupPmdMetrics(Map<MethodKey, PmdMethodMetrics> byKey, String typeName,
                                                     String methodName, int startLine) {
        PmdMethodMetrics exact = byKey.get(new MethodKey(typeName, methodName, startLine));
        if (exact != null) {
            return exact;
        }
        for (int delta : new int[] {-1, 1, -2, 2}) {
            PmdMethodMetrics near = byKey.get(new MethodKey(typeName, methodName, startLine + delta));
            if (near != null) {
                return near;
            }
        }
        return null;
    }

    private static String simpleTypeName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }

    private static MethodMetric withPmdMetrics(MethodMetric method, PmdMethodMetrics pmd,
                                               RiskCalculator riskCalculator) {
        if (MethodAccessorFilter.isTrivialShortAccessor(method)) {
            return method;
        }
        if (method.cyclomaticComplexity() == pmd.cyclomaticComplexity()
                && method.cognitiveComplexity() == pmd.cognitiveComplexity()
                && method.ncssCount() == pmd.ncssCount()
                && method.npathComplexity() == pmd.npathComplexity()) {
            return method;
        }
        MethodScanValues scan = MethodScanValues.fromMetric(method);
        scan = new MethodScanValues(
                pmd.cyclomaticComplexity(), scan.codeLines(), scan.maxNestingDepth(), scan.parameterCount(),
                pmd.cognitiveComplexity(), scan.logicalStatements(), scan.exitPoints(), scan.catchClauses(),
                scan.switchCases(), scan.outboundDistinctCalls(), scan.lambdaCount(), scan.maxTryNestingDepth(),
                scan.localVariableCount(), scan.maxMethodCallChainLength(), scan.emptyCatchBlocks(),
                scan.catchExceptionOrThrowable(), scan.catchWithOnlyPrintStackTrace(),
                scan.primitiveObsessionIndex(), scan.maxBooleanOperatorsInCondition(),
                scan.halsteadDifficultyRounded(), scan.halsteadEffortRounded(), scan.rawTypeUsage(),
                scan.stringConcatInLoop(), scan.hardcodedLiteralCount(), scan.swallowedExceptionSmells(),
                scan.genericExceptionSmells());

        boolean ccCogChanged = method.cyclomaticComplexity() != pmd.cyclomaticComplexity()
                || method.cognitiveComplexity() != pmd.cognitiveComplexity();
        Assessment risk;
        boolean god;
        if (method.analysisReused() && !ccCogChanged) {
            risk = new Assessment(method.riskScore(), method.riskLevel(), method.riskFactors(),
                    method.riskBreakdown());
            god = method.godMethod();
        } else {
            risk = riskCalculator.assessMethod(scan);
            god = riskCalculator.isGodMethod(scan);
        }

        return new MethodMetric(
                method.name(), method.kind(), method.signature(), method.returnType(),
                method.startLine(), method.endLine(),
                pmd.cyclomaticComplexity(), method.physicalLines(), method.codeLines(), method.logicalStatements(),
                pmd.cognitiveComplexity(), method.exitPoints(), method.catchClauses(), method.switchCases(),
                method.outboundDistinctCalls(), method.lambdaCount(), method.maxTryNestingDepth(),
                method.localVariableCount(), method.maxMethodCallChainLength(), method.emptyCatchBlocks(),
                method.catchExceptionOrThrowable(), method.catchWithOnlyPrintStackTrace(),
                method.primitiveObsessionIndex(), method.maxBooleanOperatorsInCondition(), method.maxNestingDepth(),
                method.parameterCount(), god, risk.score(), risk.level(), risk.factors(), risk.breakdown(),
                method.halstead(), method.exceptionSmells(), method.codeSmells(), method.methodHash(),
                method.analysisReused(), method.accessedFieldNames(), method.bodyHash(), method.structuralHash(),
                pmd.npathComplexity(), pmd.ncssCount(), method.metricsImputed());
    }

    private static ClassMetric copyClassWithMethods(ClassMetric type, List<MethodMetric> methods, int wmc,
                                                     int maxCc, RiskCalculator riskCalculator) {
        double avgCc = methods.isEmpty() ? 0.0 : Math.round(wmc * 100.0 / methods.size()) / 100.0;
        Assessment classRisk = riskCalculator.assessClassAggregate(methods, type.codeLines(), wmc,
                type.publicMethodCount(), type.efferentCouplingProxy());
        return new ClassMetric(type.name(), type.kind(), type.startLine(), type.endLine(), methods.size(),
                type.publicMethodCount(), type.codeLines(), type.efferentCouplingProxy(), wmc, maxCc, avgCc,
                classRisk.score(), classRisk.level(), classRisk.factors(), methods, type.halstead(),
                type.dependentTypes(), type.lcom3(), type.godClassCandidate(), type.swallowedExceptionCount(),
                type.genericExceptionCatchCount(), type.rawTypeUsageCount(), type.stringConcatInLoopCount(),
                type.hardcodedLiteralCount());
    }

    @FunctionalInterface
    interface PmdParseCallback<T> {
        T run(PmdParseSession session) throws Exception;
    }

    static <T> T withParseSession(ParserConfiguration.LanguageLevel languageLevel, PmdParseCallback<T> callback) {
        try (PmdParseSession session = PmdParseSession.open(toPmdLanguageVersion(languageLevel))) {
            return callback.run(session);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    static Map<MethodKey, PmdMethodMetrics> extract(String source, String relativePath,
                                                      ParserConfiguration.LanguageLevel languageLevel) {
        return withParseSession(languageLevel, session -> {
            ASTCompilationUnit root = session.parse(source, relativePath);
            Map<MethodKey, PmdMethodMetrics> map = new HashMap<>();
            MetricOptions options = MetricOptions.emptyOptions();
            root.descendants(ASTExecutableDeclaration.class).forEach(exec -> {
                String typeName = enclosingTypeSimpleName(exec).orElse("");
                MethodKey key = new MethodKey(typeName, exec.getName(), exec.getBeginLine());
                int cyclo = unbox(Metric.compute(JavaMetrics.CYCLO, exec, options));
                int cognitive = unbox(Metric.compute(JavaMetrics.COGNITIVE_COMPLEXITY, exec, options));
                int ncss = unbox(Metric.compute(JavaMetrics.NCSS, exec, options));
                int npath = safeNpath(exec, options, cyclo, ncss);
                map.put(key, new PmdMethodMetrics(cyclo, cognitive, ncss, npath));
            });
            return map;
        });
    }

    private static Optional<String> enclosingTypeSimpleName(ASTExecutableDeclaration exec) {
        return exec.ancestors(ASTTypeDeclaration.class)
                .firstOpt()
                .map(ASTTypeDeclaration::getSimpleName);
    }

    /** NPATH üstel; ağır metotta sayılmaz, tavan yazılır (skor CC/cognitive/LOC). */
    static int safeNpath(ASTExecutableDeclaration exec, MetricOptions options, int cyclo, int ncss) {
        if (PmdHeavyMetricGuard.skipExplosiveMetrics(cyclo, ncss)) {
            return NPATH_CAP;
        }
        return capNpath(Metric.compute(JavaMetrics.NPATH, exec, options));
    }

    private static int unbox(Integer value) {
        return value == null ? 0 : value;
    }

    static int capNpath(BigInteger npath) {
        if (npath == null) {
            return 0;
        }
        if (npath.signum() < 0) {
            return 0;
        }
        if (npath.compareTo(BigInteger.valueOf(NPATH_CAP)) > 0) {
            return NPATH_CAP;
        }
        return npath.intValue();
    }

    private static LanguageVersion toPmdLanguageVersion(ParserConfiguration.LanguageLevel level) {
        return PmdLanguageLevels.toPmd(level);
    }

    static final class PmdParseSession implements AutoCloseable {

        private final LanguageVersion languageVersion;
        private final JavaLanguageProcessor processor;
        private final LanguageProcessorRegistry registry;

        private PmdParseSession(LanguageVersion languageVersion, JavaLanguageProcessor processor,
                                LanguageProcessorRegistry registry) {
            this.languageVersion = languageVersion;
            this.processor = processor;
            this.registry = registry;
        }

        static PmdParseSession open(LanguageVersion version) {
            JavaLanguageProperties props =
                    (JavaLanguageProperties) JavaLanguageModule.getInstance().newPropertyBundle();
            props.setLanguageVersion(version.getVersion());
            JavaLanguageProcessor proc = new JavaLanguageProcessor(props);
            return new PmdParseSession(version, proc, LanguageProcessorRegistry.singleton(proc));
        }

        ASTCompilationUnit parse(String source, String relativePath) throws FileAnalysisException {
            TextDocument document = TextDocument.readOnlyString(source, FileId.fromPathLikeString(relativePath),
                    languageVersion);
            Parser.ParserTask task =
                    new Parser.ParserTask(document, SemanticErrorReporter.noop(), registry);
            return (ASTCompilationUnit) processor.getParser().parse(task);
        }

        @Override
        public void close() {
            try {
                registry.close();
            } catch (Exception ignored) {
                // best effort
            }
            try {
                processor.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
