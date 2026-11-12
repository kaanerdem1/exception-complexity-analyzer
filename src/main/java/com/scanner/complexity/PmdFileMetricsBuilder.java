package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileError;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.PmdViolationRow;
import com.scanner.complexity.RiskCalculator.Assessment;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTFormalParameter;
import net.sourceforge.pmd.lang.java.ast.ASTFormalParameters;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchLike;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.metrics.Metric;
import net.sourceforge.pmd.lang.metrics.MetricOptions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** PMD AST + JavaMetrics ile metod katalogu ve risk (JavaParser kullanılmaz). */
final class PmdFileMetricsBuilder {

    private PmdFileMetricsBuilder() {
    }

    static FileMetric build(String relativePath, String sourceText,
                            ParserConfiguration.LanguageLevel languageLevel,
                            List<PmdViolationRow> fileViolations, RiskCalculator riskCalculator,
                            boolean attachDuplicateHashes) {
        return build(relativePath, sourceText, languageLevel, fileViolations, riskCalculator, attachDuplicateHashes,
                Map.of(), "");
    }

    static FileMetric build(String relativePath, String sourceText,
                            ParserConfiguration.LanguageLevel languageLevel,
                            List<PmdViolationRow> fileViolations, RiskCalculator riskCalculator,
                            boolean attachDuplicateHashes, Map<String, MethodMetric> previousMethods,
                            String fileHash) {
        return build(relativePath, sourceText, languageLevel, fileViolations, riskCalculator, attachDuplicateHashes,
                previousMethods, fileHash, null);
    }

    static FileMetric build(String relativePath, String sourceText,
                            ParserConfiguration.LanguageLevel languageLevel,
                            List<PmdViolationRow> fileViolations, RiskCalculator riskCalculator,
                            boolean attachDuplicateHashes, Map<String, MethodMetric> previousMethods,
                            String fileHash, List<FileError> methodLevelErrors) {
        return PmdMetricsAligner.withParseSession(languageLevel, session -> {
            try {
                ASTCompilationUnit unit = parseTree(session, sourceText, relativePath);
                return buildFromUnit(relativePath, sourceText, unit, fileViolations, riskCalculator,
                        attachDuplicateHashes, previousMethods, fileHash, methodLevelErrors);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(relativePath + ": " + e.getMessage(), e);
            }
        });
    }

    /**
     * Ağaç kurulamazsa metotlar ayrılamaz. Önce 16MB, yetmezse 32MB yığın denenir.
     * Metot içindeki taşma buraya çıkmaz; o metot ayrıca ölçülür.
     */
    private static ASTCompilationUnit parseTree(PmdMetricsAligner.PmdParseSession session, String sourceText,
                                                 String relativePath) throws Exception {
        try {
            return DeepStack.call(DeepStack.STANDARD, () -> session.parse(sourceText, relativePath));
        } catch (VirtualMachineError first) {
            try {
                return DeepStack.call(DeepStack.TALL, () -> session.parse(sourceText, relativePath));
            } catch (VirtualMachineError second) {
                throw new RuntimeException(relativePath
                        + ": sözdizimi ağacı kurulamadı, metotlar ayrılamadı. Yığın 16MB ve 32MB denendi.",
                        second);
            }
        }
    }

    private static FileMetric buildFromUnit(String relativePath, String sourceText, ASTCompilationUnit unit,
                                            List<PmdViolationRow> fileViolations,
                                            RiskCalculator riskCalculator, boolean attachDuplicateHashes,
                                            Map<String, MethodMetric> previousMethods, String fileHash,
                                            List<FileError> methodLevelErrors) {
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName();
        MetricOptions options = MetricOptions.emptyOptions();
        List<ClassMetric> classes = new ArrayList<>();
        Map<String, MethodMetric> previous = previousMethods == null ? Map.of() : previousMethods;
        for (ASTTypeDeclaration type : unit.getTypeDeclarations().toList()) {
            classes.add(buildType(relativePath, type, sourceText, fileViolations, options, riskCalculator,
                    attachDuplicateHashes, previous, methodLevelErrors));
        }
        List<MethodMetric> allMethods = classes.stream().flatMap(c -> c.methods().stream()).toList();
        int wmc = allMethods.stream().mapToInt(MethodMetric::cyclomaticComplexity).sum();
        int maxCc = allMethods.stream().mapToInt(MethodMetric::cyclomaticComplexity).max().orElse(0);
        int loc = allMethods.stream().mapToInt(MethodMetric::codeLines).sum();
        List<HealthScoreEngine.ClassScore> classScores = new ArrayList<>();
        for (ClassMetric type : classes) {
            int scoredLoc = type.methods().stream()
                    .filter(m -> !m.excludedFromHealthScore())
                    .mapToInt(MethodMetric::codeLines)
                    .sum();
            classScores.add(new HealthScoreEngine.ClassScore(
                    type.riskScore(), type.riskLevel(), "", type.qualityScore(), "", scoredLoc, List.of()));
        }
        HealthScoreEngine.FileScore fileScore = HealthScoreEngine.get().scoreFile(classScores);
        Assessment risk = new Assessment(fileScore.complexity(), fileScore.complexityLevel(), List.of());
        int methodsReused = (int) allMethods.stream().filter(MethodMetric::analysisReused).count();
        String resolvedFileHash = fileHash == null ? "" : fileHash;
        return new FileMetric(relativePath, packageName, unit.getEndLine(), loc, classes.size(), allMethods.size(),
                wmc, maxCc, risk.score(), risk.level(), risk.factors(), classes,
                HalsteadMetrics.EMPTY, 0, 0, 0, 0, 0, resolvedFileHash, false, methodsReused,
                fileScore.quality());
    }

    private static ClassMetric buildType(String relativePath, ASTTypeDeclaration type, String sourceText,
                                         List<PmdViolationRow> fileViolations,
                                         MetricOptions options, RiskCalculator riskCalculator,
                                         boolean attachDuplicateHashes, Map<String, MethodMetric> previousMethods,
                                         List<FileError> methodLevelErrors) {
        String simpleName = type.getSimpleName();
        java.util.LinkedHashMap<String, MethodMetric> methodsByLine = new java.util.LinkedHashMap<>();
        String className = simpleName;
        for (ASTExecutableDeclaration exec : type.getOperations().toList()) {
            MethodMetric built = measureMethod(relativePath, exec, sourceText, fileViolations, options,
                    riskCalculator, attachDuplicateHashes, className, previousMethods, methodLevelErrors);
            String lineKey = built.startLine() + "#" + built.name();
            MethodMetric previous = methodsByLine.get(lineKey);
            if (previous == null || methodSpan(built) > methodSpan(previous)) {
                methodsByLine.put(lineKey, built);
            }
        }
        List<MethodMetric> methods = new ArrayList<>(methodsByLine.values());
        int wmc = methods.stream().mapToInt(MethodMetric::cyclomaticComplexity).sum();
        int maxCc = methods.stream().mapToInt(MethodMetric::cyclomaticComplexity).max().orElse(0);
        double avg = methods.isEmpty() ? 0.0 : Math.round(wmc * 100.0 / methods.size()) / 100.0;
        int loc = methods.stream().mapToInt(MethodMetric::codeLines).sum();
        boolean record = "RECORD".equals(typeKind(type));
        List<HealthScoreEngine.MethodInput> inputs = new ArrayList<>();
        for (MethodMetric method : methods) {
            inputs.add(new HealthScoreEngine.MethodInput(
                    method.codeLines(), method.riskScore(), method.qualityScore(), method.excludedFromHealthScore()));
        }
        HealthScoreEngine.ClassScore classScore = HealthScoreEngine.get().scoreClass(
                inputs, hitsInRange(fileViolations, type.getBeginLine(), type.getEndLine()), record);
        boolean godClass = methods.stream().anyMatch(MethodMetric::godMethod)
                || fileViolations != null && fileViolations.stream()
                .anyMatch(v -> "GodClass".equals(v.ruleName()) && v.beginLine() >= type.getBeginLine()
                        && v.beginLine() <= type.getEndLine());
        return new ClassMetric(simpleName, typeKind(type), type.getBeginLine(), type.getEndLine(), methods.size(), 0,
                loc, 0, wmc, maxCc, avg, classScore.complexity(), classScore.complexityLevel(), classScore.notes(),
                methods, HalsteadMetrics.EMPTY, List.of(), 0.0, godClass, 0, 0, 0, 0, 0, classScore.quality());
    }

    /**
     * Metot ölçümü geniş yığında yürür. Taşan parametre paydadan çıkar; metot
     * ölçülen parametrelerle skorlanır. Dosyanın sonraki metotları sürer.
     */
    private static MethodMetric measureMethod(String relativePath, ASTExecutableDeclaration exec, String sourceText,
                                               List<PmdViolationRow> fileViolations, MetricOptions options,
                                               RiskCalculator riskCalculator, boolean attachDuplicateHashes,
                                               String className, Map<String, MethodMetric> previousMethods,
                                               List<FileError> methodLevelErrors) {
        MethodIdentity[] seen = new MethodIdentity[1];
        try {
            return DeepStack.call(DeepStack.STANDARD, () -> {
                seen[0] = MethodIdentity.capture(exec, sourceText);
                return buildMethod(relativePath, exec, sourceText, fileViolations, options, riskCalculator,
                        attachDuplicateHashes, className, previousMethods, methodLevelErrors);
            });
        } catch (VirtualMachineError overflow) {
            MethodIdentity id = seen[0] != null ? seen[0] : MethodIdentity.unknown(exec);
            recordMethodMetricFailure(methodLevelErrors, relativePath, className, id.signature, id.start, id.end,
                    overflow);
            return failedMethodStub(sourceText, id.name, id.kind, id.signature, id.returnType, id.start, id.end,
                    id.physical, id.arity, riskCalculator, attachDuplicateHashes);
        } catch (Exception e) {
            throw new RuntimeException(relativePath + ": " + e.getMessage(), e);
        }
    }

    private static final class MethodIdentity {
        final String name;
        final String kind;
        final String signature;
        final String returnType;
        final int start;
        final int end;
        final int physical;
        final int arity;

        private MethodIdentity(String name, String kind, String signature, String returnType, int start, int end,
                                int physical, int arity) {
            this.name = name;
            this.kind = kind;
            this.signature = signature;
            this.returnType = returnType;
            this.start = start;
            this.end = end;
            this.physical = physical;
            this.arity = arity;
        }

        static MethodIdentity capture(ASTExecutableDeclaration exec, String sourceText) {
            String name = exec.getName();
            String kind = exec instanceof ASTConstructorDeclaration ? "CONSTRUCTOR" : "METHOD";
            int arity = exec.getArity();
            String signature = methodSignature(exec);
            int start = exec.getBeginLine();
            int end = resolveMethodEndLine(sourceText, start, exec.getEndLine());
            int physical = Math.max(0, end - start + 1);
            return new MethodIdentity(name, kind, signature, returnType(exec), start, end, physical, arity);
        }

        static MethodIdentity unknown(ASTExecutableDeclaration exec) {
            int line = 0;
            try {
                line = exec.getBeginLine();
            } catch (VirtualMachineError ignored) {
                line = 0;
            }
            return new MethodIdentity("?", "METHOD", "?", "", line, line, 1, 0);
        }
    }

    private static MethodMetric buildMethod(String relativePath, ASTExecutableDeclaration exec, String sourceText,
                                            List<PmdViolationRow> fileViolations, MetricOptions options,
                                            RiskCalculator riskCalculator, boolean attachDuplicateHashes,
                                            String className, Map<String, MethodMetric> previousMethods,
                                            List<FileError> methodLevelErrors) {
        String name = exec.getName();
        String kind = exec instanceof ASTConstructorDeclaration ? "CONSTRUCTOR" : "METHOD";
        int arity = exec.getArity();
        String signature = methodSignature(exec);
        int start = exec.getBeginLine();
        int end = resolveMethodEndLine(sourceText, start, exec.getEndLine());
        int physical = Math.max(0, end - start + 1);
        if (MethodAccessorFilter.isTrivialShortAccessor(kind, name, arity, physical)) {
            return skippedShortAccessor(exec, sourceText, name, kind, signature, arity, start, end, physical,
                    attachDuplicateHashes, className, previousMethods);
        }
        try {
            Integer cycloBox = measureParameter(() -> unboxOrZero(Metric.compute(JavaMetrics.CYCLO, exec, options)));
            Integer ncssBox = measureParameter(() -> unboxOrZero(Metric.compute(JavaMetrics.NCSS, exec, options)));
            Integer cognitiveBox = measureParameter(
                    () -> unboxOrZero(Metric.compute(JavaMetrics.COGNITIVE_COMPLEXITY, exec, options)));
            Integer npathBox = cycloBox != null && PmdHeavyMetricGuard.skipExplosiveMetrics(cycloBox)
                    ? PmdMetricsAligner.NPATH_CAP
                    : measureParameter(() -> BoundedNPath.compute(exec));
            PmdAstMethodStats.Stats stats = measureParameter(() -> PmdAstMethodStats.analyze(exec));
            Set<String> omitted = new LinkedHashSet<>();
            if (cycloBox == null) {
                omitted.add("cyclomaticComplexity");
            }
            if (cognitiveBox == null) {
                omitted.add("cognitiveComplexity");
            }
            if (npathBox == null) {
                omitted.add("npathComplexity");
            }
            if (ncssBox == null) {
                omitted.add("ncssCount");
            }
            if (stats == null) {
                omitted.add("maxNestingDepth");
            }
            int cyclo = cycloBox == null ? 0 : cycloBox;
            int ncss = ncssBox == null ? 0 : ncssBox;
            int cognitive = cognitiveBox == null ? 0 : cognitiveBox;
            int npath = npathBox == null ? 0 : npathBox;
            PmdAstMethodStats.Stats counted = stats == null ? PmdAstMethodStats.EMPTY : stats;
            // Risk LOC = NCSS (yorum / boş satır hariç). physical satır aralığı yorumları da kapsar — skora girmez.
            int codeLines = ncss > 0 ? ncss : Math.max(countCodeLinesExcludingComments(sourceText, start, end), 1);

            MethodMetric skeleton = MethodMetric.withoutLegacyExtensions(
                    name, kind, signature, returnType(exec), start, end,
                    cyclo, physical, codeLines, counted.logicalStatements(), cognitive, counted.exitPoints(),
                    counted.catchClauses(), counted.switchCases(), 0, counted.lambdaCount(), counted.maxTryNestingDepth(),
                    counted.localVariableCount(), 0, counted.emptyCatchBlocks(), counted.catchExceptionOrThrowable(), 0,
                    0, counted.maxBooleanOperatorsInCondition(), counted.maxNestingDepth(), arity,
                    false, 0.0, RiskLevel.LOW, List.of(), null, "", "", npath, ncss);

            boolean trivialBody = stats != null && MethodAccessorFilter.isSingleStatementAccessor(
                    kind, name, arity, cyclo, counted.logicalStatements(), counted.exitPoints(),
                    counted.catchClauses(), counted.localVariableCount());
            HealthScoreEngine engine = HealthScoreEngine.get();
            HealthScoreEngine.Signals signals = new HealthScoreEngine.Signals(
                    cyclo, cognitive, npath, codeLines, arity, counted.maxNestingDepth());
            List<HealthScoreEngine.Hit> hits = hitsInRange(fileViolations, start, end, exec);
            int emptyCatch = emptyCatchCount(hits, counted.emptyCatchBlocks());
            hits = withEmptyCatchHits(hits, emptyCatch);
            HealthScoreEngine.MethodScore health = engine.scoreMethod(
                    signals, hits, trivialBody, omitted);
            boolean god = !trivialBody && engine.godMethod(signals);

            PmdSourceMethodHashes.Hashes hashes = PmdSourceMethodHashes.fromSourceSlice(sourceText, start, end);
            // Cache katman 2: methodHash === bodyHash (gövde). structuralHash yalnız benzerlik.
            String bodyHash = hashes.bodyHash() == null ? "" : hashes.bodyHash();
            String methodHash = bodyHash;
            String structuralOut = attachDuplicateHashes ? hashes.structuralHash() : "";

            String cacheKey = className + "#" + signature;
            MethodMetric previous = previousMethods.get(cacheKey);
            String previousBody = previous == null ? ""
                    : (previous.methodHash() != null && !previous.methodHash().isEmpty()
                    ? previous.methodHash()
                    : previous.bodyHash() == null ? "" : previous.bodyHash());
            boolean reuse = previous != null && !bodyHash.isEmpty() && bodyHash.equals(previousBody);
            double complexity = health.complexity();
            RiskLevel level = health.complexityLevel();
            List<String> factors = new ArrayList<>(health.notes());
            if (!trivialBody && cycloBox != null) {
                factors.add(cycloFactor(cyclo));
            }
            if (!trivialBody && npathBox != null) {
                factors.add(npathFactor(cyclo, npath));
            }
            double quality = health.quality();
            boolean excluded = trivialBody;
            if (reuse && !previous.metricsImputed()) {
                complexity = previous.riskScore();
                level = previous.riskLevel();
                factors = previous.riskFactors();
                quality = previous.qualityScore();
                excluded = previous.excludedFromHealthScore();
                god = previous.godMethod();
            }

            return new MethodMetric(name, kind, signature, returnType(exec), start, end, cyclo, physical, codeLines,
                    counted.logicalStatements(), cognitive, counted.exitPoints(), counted.catchClauses(), counted.switchCases(),
                    0, counted.lambdaCount(), counted.maxTryNestingDepth(), counted.localVariableCount(),
                    0, emptyCatch, counted.catchExceptionOrThrowable(),
                    0, 0, counted.maxBooleanOperatorsInCondition(), counted.maxNestingDepth(), arity, god,
                    complexity, level, factors, null, HalsteadMetrics.EMPTY, List.of(), List.of(), methodHash, reuse, List.of(),
                    bodyHash, structuralOut, npath, ncss, !omitted.isEmpty(), quality, excluded);
        } catch (VirtualMachineError e) {
            recordMethodMetricFailure(methodLevelErrors, relativePath, className, signature, start, end, e);
            return failedMethodStub(sourceText, name, kind, signature, returnType(exec), start, end, physical, arity,
                    riskCalculator, attachDuplicateHashes);
        }
    }

    /** Bir parametrenin yığını taşarsa null döner. Metot bu parametre olmadan skorlanır. */
    private static <T> T measureParameter(DeepStack.Task<T> task) {
        try {
            return DeepStack.call(DeepStack.STANDARD, task);
        } catch (VirtualMachineError overflow) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static void recordMethodMetricFailure(List<FileError> sink, String relativePath, String className,
                                                  String signature, int startLine, int endLine, Throwable t) {
        if (sink == null) {
            return;
        }
        String detail = analysisFailureDetail(t);
        sink.add(new FileError(relativePath, "PMD metrics: " + detail, className, signature, startLine, endLine));
    }

    /**
     * Metot içi analiz başarısız — karmaşıklık 100, kalite 0 yazılır
     * (gerçek ölçüm yapılamadı — manuel inceleme).
     */
    private static MethodMetric failedMethodStub(
            String sourceText,
            String name, String kind, String signature, String returnTypeLabel,
            int start, int end, int physical, int arity,
            RiskCalculator riskCalculator, boolean attachDuplicateHashes) {
        int locNominal = Math.max(physical, 100);
        int ccNominal = Math.max(50, PmdHeavyMetricGuard.CYCLO_HEAVY);
        int nestNominal = 25;
        double finalScore = RiskCalculator.SCORE_MAX;
        java.util.ArrayList<String> factors = new java.util.ArrayList<>();
        factors.add("METRICS_IMPUTED=true: bu metot bitirilemedi (yığın veya bellek). Karmaşıklık 100, kalite 0 yazıldı; gerçek ölçüm değildir.");
        factors.add("PMD_ANALYSIS_INCOMPLETE: -Xss/-Xmx büyütün veya dosyayı hariç tutun. Daha geniş yığınla yeniden tarayınca gerçek metrik gelir.");
        boolean god = true;

        PmdSourceMethodHashes.Hashes hashes = PmdSourceMethodHashes.fromSourceSlice(sourceText, start, end);
        String bodyHash = hashes.bodyHash() == null ? "" : hashes.bodyHash();
        String methodHash = bodyHash;
        String structuralOut = attachDuplicateHashes ? hashes.structuralHash() : "";
        // Bu koşuda metrik tamamlanamadı — önbellekten eski (düşük) skoru ASLA yeniden kullanma.
        boolean reuse = false;

        int npath = PmdMetricsAligner.NPATH_CAP;
        int ncss = locNominal;
        return new MethodMetric(name, kind, signature, returnTypeLabel, start, end, ccNominal, physical, locNominal,
                locNominal,
                0,
                0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, nestNominal,
                arity,
                god, finalScore, RiskLevel.CRITICAL, factors, null,
                HalsteadMetrics.EMPTY, List.of(), List.of(),
                methodHash, reuse, List.of(), bodyHash, structuralOut, npath, ncss, true, 0, false);
    }

    private static String cycloFactor(int cyclo) {
        int decisions = Math.max(0, cyclo - 1);
        return "döngüsel karmaşıklık " + cyclo + ": 1 metoda giriştir, kalan " + decisions
                + " her if, for, while, do, foreach, catch, throw, switch case, &&, || ve üçlü ifadeden bir kez sayılır."
                + " İç içe derinlik değildir. Skor tavanı " + metricMax("CyclomaticComplexity", 17) + ".";
    }

    private static String npathFactor(int cyclo, int npath) {
        int countCap = BoundedNPath.scoreLimit();
        int scoreMax = npathScoreMax();
        String how = " Metot 1 yolla başlar. Art arda if, döngü, && ve || yolları çarpar;"
                + " if-else ile switch kolları toplar. Satır sayısı değildir.";
        if (PmdHeavyMetricGuard.skipExplosiveMetrics(cyclo)) {
            return "npath: dallanma " + cyclo + " (>= " + PmdHeavyMetricGuard.CYCLO_HEAVY
                    + "), sayım yapılmadı, tavan " + countCap
                    + " yazıldı. Bu " + countCap + " gerçek yol sayısı değildir."
                    + " Skor tavanı " + scoreMax + ", bu kural 0 puan.";
        }
        if (npath >= countCap) {
            return "npath: yol sayısı sayım tavanı " + countCap
                    + " değerine vardı, sayım durdu." + how
                    + " " + countCap + " gerçek yol değil, durulan tavandır. Skor tavanı " + scoreMax
                    + ", bu kural 0 puan. Daha büyük bir yol sayısı da aynı puanı verir.";
        }
        if (npath >= scoreMax) {
            return "npath: ölçülen yol sayısı " + npath + "." + how
                    + " " + npath + " sayım tavanı " + countCap + " altında olduğu için gerçek yoldur, yuvarlama değildir."
                    + " Skor tavanı " + scoreMax + ", bu kural 0 puan. Sayım " + countCap + " değerine kadar sürer.";
        }
        return "npath: ölçülen yol sayısı " + npath + "." + how
                + " Skor tavanı " + scoreMax + " altı, tam sayı.";
    }

    private static int npathScoreMax() {
        return metricMax("NPathComplexity", 20);
    }

    private static int metricMax(String ruleId, int fallback) {
        HealthScoreCatalog.Rule rule = HealthScoreEngine.get().catalog().find(ruleId);
        if (rule == null || rule.metricMax() < 1) {
            return fallback;
        }
        return rule.metricMax();
    }

    private static String analysisFailureDetail(Throwable t) {
        if (t instanceof StackOverflowError) {
            return "Yığın taştı (stack overflow): ifade veya AST çok derin. "
                    + "Bu metot ölçülemedi; karmaşıklık 100, kalite 0 yazıldı. Dosyanın diğer metotları sürer.";
        }
        if (t instanceof OutOfMemoryError) {
            return "Bellek yetmedi (out of memory): bu metot ölçülürken heap doldu. "
                    + "Karmaşıklık 100, kalite 0 yazıldı. Dosyanın diğer metotları sürer.";
        }
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            return t.getClass().getSimpleName() + ": " + msg;
        }
        return t.getClass().getSimpleName();
    }

    /** 1–2 satır get/set/is: JavaMetrics, AST sayaç ve RiskCalculator yok. */
    private static MethodMetric skippedShortAccessor(ASTExecutableDeclaration exec, String sourceText,
                                                     String name, String kind, String signature, int arity,
                                                     int start, int end, int physical, boolean attachDuplicateHashes,
                                                     String className, Map<String, MethodMetric> previousMethods) {
        int loc = Math.max(1, Math.min(physical, MethodAccessorFilter.MAX_TRIVIAL_PHYSICAL_LINES));
        PmdSourceMethodHashes.Hashes hashes = PmdSourceMethodHashes.fromSourceSlice(sourceText, start, end);
        String bodyHash = hashes.bodyHash() == null ? "" : hashes.bodyHash();
        String methodHash = bodyHash;
        String structuralOut = attachDuplicateHashes ? hashes.structuralHash() : "";
        String cacheKey = className + "#" + signature;
        MethodMetric previous = previousMethods.get(cacheKey);
        String previousBody = previous == null ? ""
                : (previous.methodHash() != null && !previous.methodHash().isEmpty()
                ? previous.methodHash()
                : previous.bodyHash() == null ? "" : previous.bodyHash());
        boolean reuse = previous != null && !bodyHash.isEmpty() && bodyHash.equals(previousBody);
        return new MethodMetric(name, kind, signature, returnType(exec), start, end, 1, physical, loc,
                1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, arity, false, 0.0, RiskLevel.LOW,
                List.of("excludedFromHealthScore=true"), null, HalsteadMetrics.EMPTY, List.of(), List.of(),
                methodHash, reuse, List.of(), bodyHash, structuralOut, 1, loc, false, 100, true);
    }

    /** PMD ihlali ile AST sayısından büyük olan. Skor ve tablo aynı adedi kullanır. */
    private static int emptyCatchCount(List<HealthScoreEngine.Hit> hits, int astCount) {
        int fromRules = 0;
        if (hits != null) {
            for (HealthScoreEngine.Hit hit : hits) {
                if ("EmptyCatchBlock".equals(hit.ruleName())) {
                    fromRules++;
                }
            }
        }
        return Math.max(fromRules, Math.max(0, astCount));
    }

    private static List<HealthScoreEngine.Hit> withEmptyCatchHits(List<HealthScoreEngine.Hit> hits, int total) {
        int present = 0;
        List<HealthScoreEngine.Hit> copy = new ArrayList<>();
        if (hits != null) {
            for (HealthScoreEngine.Hit hit : hits) {
                copy.add(hit);
                if ("EmptyCatchBlock".equals(hit.ruleName())) {
                    present++;
                }
            }
        }
        for (int i = present; i < total; i++) {
            copy.add(new HealthScoreEngine.Hit("EmptyCatchBlock"));
        }
        return copy;
    }

    private static List<HealthScoreEngine.Hit> hitsInRange(List<PmdViolationRow> violations, int start, int end) {
        return hitsInRange(violations, start, end, null);
    }

    private static List<HealthScoreEngine.Hit> hitsInRange(List<PmdViolationRow> violations, int start, int end,
                                                          ASTExecutableDeclaration exec) {
        if (violations == null || violations.isEmpty()) {
            return List.of();
        }
        List<HealthScoreEngine.Hit> hits = new ArrayList<>();
        for (PmdViolationRow row : violations) {
            if (row.beginLine() >= start && row.beginLine() <= end) {
                boolean sealedSwitch = exec != null
                        && "ExhaustiveSwitchHasDefault".equals(row.ruleName())
                        && sealedExhaustiveDefaultAt(exec, row.beginLine());
                hits.add(new HealthScoreEngine.Hit(row.ruleName(), sealedSwitch));
            }
        }
        return hits;
    }

    /** Sealed (enum değil) ve derleyici exhaustive sayıyorsa ihlal düşük ağırlığa iner. */
    private static boolean sealedExhaustiveDefaultAt(ASTExecutableDeclaration exec, int line) {
        for (ASTSwitchLike sw : exec.descendants(ASTSwitchLike.class)) {
            if (line < sw.getBeginLine() || line > sw.getEndLine()) {
                continue;
            }
            if (sw.hasDefaultCase() && sw.isExhaustive() && !sw.isEnumSwitch()) {
                return true;
            }
        }
        return false;
    }

    /**
     * PMD {@link ASTExecutableDeclaration#getEndLine()} often equals {@code beginLine} (signature only).
     * Brace-match from {@code startLine} so JSON/DB {@code endLine} and duplicate hashes match the body.
     */
    static int resolveMethodEndLine(String sourceText, int startLine, int pmdEndLine) {
        if (pmdEndLine > startLine) {
            return pmdEndLine;
        }
        if (sourceText == null || sourceText.isEmpty() || startLine < 1) {
            return Math.max(startLine, pmdEndLine);
        }
        String[] lines = sourceText.split("\n", -1);
        if (startLine > lines.length) {
            return startLine;
        }
        int depth = 0;
        boolean bodyStarted = false;
        for (int i = startLine - 1; i < lines.length; i++) {
            String line = lines[i];
            boolean inString = false;
            char stringQuote = 0;
            for (int j = 0; j < line.length(); j++) {
                char ch = line.charAt(j);
                if (inString) {
                    if (ch == '\\') {
                        j++;
                        continue;
                    }
                    if (ch == stringQuote) {
                        inString = false;
                    }
                    continue;
                }
                if (ch == '"' || ch == '\'') {
                    inString = true;
                    stringQuote = ch;
                    continue;
                }
                if (ch == '{') {
                    depth++;
                    bodyStarted = true;
                } else if (ch == '}' && bodyStarted) {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
        }
        for (int i = lines.length - 1; i >= startLine - 1; i--) {
            if (!lines[i].trim().isEmpty()) {
                return i + 1;
            }
        }
        return startLine;
    }

    /** Yorum ve boş satırları sayma — NCSS yoksa LOC yedek. */
    static int countCodeLinesExcludingComments(String source, int startLine, int endLine) {
        if (source == null || source.isEmpty() || startLine < 1 || endLine < startLine) {
            return 0;
        }
        String[] lines = source.split("\n", -1);
        int from = Math.min(startLine - 1, lines.length);
        int to = Math.min(endLine, lines.length);
        boolean inBlock = false;
        int count = 0;
        for (int i = from; i < to; i++) {
            String line = lines[i];
            StringBuilder code = new StringBuilder();
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                char next = c + 1 < line.length() ? line.charAt(c + 1) : '\0';
                if (inBlock) {
                    if (ch == '*' && next == '/') {
                        inBlock = false;
                        c++;
                    }
                    continue;
                }
                if (ch == '/' && next == '*') {
                    inBlock = true;
                    c++;
                    continue;
                }
                if (ch == '/' && next == '/') {
                    break;
                }
                code.append(ch);
            }
            if (!code.toString().trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int unboxOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static int methodSpan(MethodMetric m) {
        return Math.max(0, m.endLine() - m.startLine());
    }

    /** Parser ile aynı yazım: getCoverPledge() / foo(String, int) — "N param" değil. */
    static String methodSignature(ASTExecutableDeclaration exec) {
        String name = exec.getName();
        ASTFormalParameters params = exec.getFormalParameters();
        if (params == null || params.size() == 0) {
            return name + "()";
        }
        StringBuilder sb = new StringBuilder(name).append('(');
        boolean first = true;
        for (ASTFormalParameter p : params) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(formalTypeLabel(p));
            if (p.isVarargs()) {
                sb.append("...");
            }
        }
        return sb.append(')').toString();
    }

    private static String formalTypeLabel(ASTFormalParameter parameter) {
        try {
            if (parameter.getTypeNode() != null) {
                return simpleTypeName(parameter.getTypeNode().getTypeMirror().toString());
            }
        } catch (RuntimeException ignored) {
            // type resolution yoksa aşağı
        }
        return "Object";
    }

    private static String simpleTypeName(String type) {
        if (type == null || type.isEmpty()) {
            return "Object";
        }
        String trimmed = type.replace(" ", "");
        int generic = trimmed.indexOf('<');
        String raw = generic >= 0 ? trimmed.substring(0, generic) : trimmed;
        int dot = raw.lastIndexOf('.');
        String simple = dot >= 0 ? raw.substring(dot + 1) : raw;
        if (generic >= 0) {
            return simple + trimmed.substring(generic);
        }
        return simple;
    }

    private static String returnType(ASTExecutableDeclaration exec) {
        if (exec instanceof ASTMethodDeclaration method) {
            if (method.isVoid()) {
                return "void";
            }
            return method.getResultTypeNode().getTypeMirror().toString();
        }
        return "";
    }

    private static String typeKind(ASTTypeDeclaration type) {
        return switch (type.getXPathNodeName()) {
            case "InterfaceDeclaration" -> "INTERFACE";
            case "EnumDeclaration" -> "ENUM";
            case "RecordDeclaration" -> "RECORD";
            case "AnnotationTypeDeclaration" -> "ANNOTATION";
            default -> "CLASS";
        };
    }
}
