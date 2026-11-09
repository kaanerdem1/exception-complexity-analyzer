package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.ast.FileAnalysisException;
import net.sourceforge.pmd.lang.ast.Parser;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProcessor;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.metrics.Metric;
import net.sourceforge.pmd.lang.metrics.MetricOptions;

import java.math.BigInteger;

/** PMD ayrıştırma oturumu ve tavanlı NPath. Skor {@link HealthScoreEngine} tarafındadır. */
final class PmdMetricsAligner {

    static final int NPATH_CAP = 2_000_000_000;

    private PmdMetricsAligner() {
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

    /** NPATH üstel; ağır metotta sayılmaz, tavan yazılır. */
    static int safeNpath(ASTExecutableDeclaration exec, MetricOptions options, int cyclo) {
        if (PmdHeavyMetricGuard.skipExplosiveMetrics(cyclo)) {
            return NPATH_CAP;
        }
        return capNpath(Metric.compute(JavaMetrics.NPATH, exec, options));
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
