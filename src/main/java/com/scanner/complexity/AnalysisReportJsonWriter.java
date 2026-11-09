package com.scanner.complexity;

import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Gson {@code toJson} yerine doğrudan {@link Writer}'a yazar; dev JSON için ek String kopyası oluşturmaz. */
public final class AnalysisReportJsonWriter {

    private AnalysisReportJsonWriter() {
    }

    public static void write(AnalysisReport report, Writer out, boolean compact) throws IOException {
        write(report, out, compact, MethodAccessorFilter.DEFAULT_PUBLISH_MIN_SCORE);
    }

    public static void write(AnalysisReport report, Writer out, boolean compact, double methodRiskMinScore)
            throws IOException {
        JsonWriter writer = new JsonWriter(out);
        if (!compact) {
            writer.setIndent("  ");
        }
        writeReport(writer, report, methodRiskMinScore);
        writer.flush();
    }

    private static void writeReport(JsonWriter w, AnalysisReport report, double methodRiskMinScore) throws IOException {
        w.beginObject();
        w.name("tool").value(report.tool());
        w.name("generatedAt").value(report.generatedAt());
        w.name("analyzedPath").value(report.analyzedPath());
        w.name("parserLanguageLevel").value(report.parserLanguageLevel());
        writeRiskModel(w, report.riskModel());
        writeJsonLegend(w, report, methodRiskMinScore);
        writeSummary(w, report.summary());
        writeHealthDrill(w, report);
        writeScanDiagnostics(w, report.scanDiagnostics());
        writeCacheStatistics(w, report.cacheStatistics());
        writeIncrementalChanges(w, report.incrementalChanges());
        writeDuplicateStatistics(w, report.duplicateStatistics());
        writeDuplicateGroups(w, report.duplicateGroups());
        writePmdRulesetPhase(w, report.pmdRulesetPhase());
        writeHotspots(w, report.topRiskyMethods());
        String pipeline = report.tool().toLowerCase(Locale.ROOT).contains("pmd") ? "pmd-native" : "parser";
        writeFiles(w, report.files(), report.analyzedPath(), report.riskModel(), pipeline, methodRiskMinScore);
        writeErrors(w, report.errors());
        w.endObject();
    }

    private static void writeRiskModel(JsonWriter w, AnalysisReport.RiskModel model) throws IOException {
        w.name("riskModel");
        w.beginObject();
        w.name("version").value(model.version());
        w.name("formula").value(model.formula());
        w.name("weights");
        w.beginObject();
        for (Map.Entry<String, Double> e : model.weights().entrySet()) {
            w.name(e.getKey()).value(e.getValue());
        }
        w.endObject();
        w.name("thresholds");
        w.beginObject();
        for (Map.Entry<String, List<Integer>> e : model.thresholds().entrySet()) {
            w.name(e.getKey());
            w.beginArray();
            for (int v : e.getValue()) {
                w.value(v);
            }
            w.endArray();
        }
        w.endObject();
        w.endObject();
    }

    private static void writeHealthDrill(JsonWriter w, AnalysisReport report) throws IOException {
        w.name("healthDrillDown");
        w.beginArray();
        String root = report.analyzedPath();
        java.util.function.Function<String, String> moduleOf = root == null || root.isBlank()
                ? null
                : ModuleRootIndex.forScanRoot(Path.of(root))::moduleRoot;
        for (Map<String, Object> module : HealthScoreEngine.get().drill(report.files(), moduleOf)) {
            w.beginObject();
            w.name("module").value(String.valueOf(module.get("module")));
            w.name("worstClasses");
            w.beginArray();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> classes = (List<Map<String, Object>>) module.get("worstClasses");
            if (classes != null) {
                for (Map<String, Object> type : classes) {
                    w.beginObject();
                    w.name("file").value(String.valueOf(type.get("file")));
                    w.name("name").value(String.valueOf(type.get("name")));
                    w.name("complexityScore").value(((Number) type.get("complexityScore")).doubleValue());
                    w.name("qualityScore").value(((Number) type.get("qualityScore")).doubleValue());
                    w.name("worstMethods");
                    w.beginArray();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> methods = (List<Map<String, Object>>) type.get("worstMethods");
                    if (methods != null) {
                        for (Map<String, Object> method : methods) {
                            w.beginObject();
                            w.name("signature").value(String.valueOf(method.get("signature")));
                            w.name("startLine").value(((Number) method.get("startLine")).intValue());
                            w.name("complexityScore").value(((Number) method.get("complexityScore")).doubleValue());
                            w.name("qualityScore").value(((Number) method.get("qualityScore")).doubleValue());
                            w.endObject();
                        }
                    }
                    w.endArray();
                    w.endObject();
                }
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
    }

    private static void writeSummary(JsonWriter w, AnalysisReport.Summary s) throws IOException {
        w.name("summary");
        w.beginObject();
        w.name("filesScanned").value(s.filesScanned());
        w.name("filesParsed").value(s.filesParsed());
        w.name("filesFailed").value(s.filesFailed());
        w.name("classCount").value(s.classCount());
        w.name("methodCount").value(s.methodCount());
        w.name("totalCodeLines").value(s.totalCodeLines());
        w.name("averageCyclomaticComplexity").value(s.averageCyclomaticComplexity());
        w.name("maxCyclomaticComplexity").value(s.maxCyclomaticComplexity());
        w.name("godMethodCount").value(s.godMethodCount());
        w.name("projectRiskScore").value(s.projectRiskScore());
        w.name("projectRiskLevel").value(s.projectRiskLevel().name());
        w.name("projectComplexityScore").value(s.projectRiskScore());
        w.name("projectComplexityBand").value(HealthScoreEngine.complexityBand(s.projectRiskScore()));
        w.name("projectQualityScore").value(s.projectQualityScore());
        w.name("projectQualityBand").value(HealthScoreEngine.qualityBand(s.projectQualityScore()));
        w.name("highPlusCriticalLocRatio").value(s.highPlusCriticalLocRatio());
        w.name("criticalMethodsPerKloc").value(s.criticalMethodsPerKloc());
        w.name("methodRiskScoreP95").value(s.methodRiskScoreP95());
        w.name("methodRiskScoreP99").value(s.methodRiskScoreP99());
        w.name("methodRiskDistribution");
        w.beginObject();
        for (RiskLevel level : RiskLevel.values()) {
            w.name(level.name()).value(s.methodRiskDistribution().get(level));
        }
        w.endObject();
        w.name("moduleSummaries");
        w.beginArray();
        for (AnalysisReport.ModuleRiskSummary module : s.moduleSummaries()) {
            w.beginObject();
            w.name("moduleRoot").value(module.moduleRoot());
            w.name("methodCount").value(module.methodCount());
            w.name("codeLines").value(module.codeLines());
            w.name("locWeightedRiskScore").value(module.locWeightedRiskScore());
            w.name("locWeightedQualityScore").value(module.locWeightedQualityScore());
            w.name("methodRiskScoreP95").value(module.methodRiskScoreP95());
            w.name("criticalMethodsPerKloc").value(module.criticalMethodsPerKloc());
            w.endObject();
        }
        w.endArray();
        w.endObject();
    }

    private static void writeScanDiagnostics(JsonWriter w, ScanDiagnostics d) throws IOException {
        if (d == null) {
            return;
        }
        w.name("scanDiagnostics");
        w.beginObject();
        w.name("completionStatus").value(d.completionStatus());
        w.name("lastPhaseTr").value(d.lastPhaseTr());
        if (d.fatalPhaseTr() != null) {
            w.name("fatalPhaseTr").value(d.fatalPhaseTr());
        }
        if (d.fatalMessage() != null) {
            w.name("fatalMessage").value(d.fatalMessage());
        }
        if (d.lastFileAttempted() != null) {
            w.name("lastFileAttempted").value(d.lastFileAttempted());
        }
        w.name("parseFailureRatio").value(d.parseFailureRatio());
        w.name("errorsByCategory");
        w.beginObject();
        for (Map.Entry<String, Integer> e : d.errorsByCategory().entrySet()) {
            w.name(e.getKey()).value(e.getValue());
        }
        w.endObject();
        w.name("recommendationsTr");
        w.beginArray();
        for (String tip : d.recommendationsTr()) {
            w.value(tip);
        }
        w.endArray();
        w.endObject();
    }

    private static void writeHotspots(JsonWriter w, List<AnalysisReport.RiskHotspot> hotspots) throws IOException {
        w.name("topRiskyMethods");
        w.beginArray();
        for (AnalysisReport.RiskHotspot h : hotspots) {
            w.beginObject();
            w.name("file").value(h.file());
            w.name("packageName").value(h.packageName());
            w.name("className").value(h.className());
            w.name("method").value(h.method());
            w.name("startLine").value(h.startLine());
            w.name("riskScore").value(h.riskScore());
            w.name("riskLevel").value(h.riskLevel().name());
            w.name("cyclomaticComplexity").value(h.cyclomaticComplexity());
            w.name("codeLines").value(h.codeLines());
            w.name("maxNestingDepth").value(h.maxNestingDepth());
            w.name("parameterCount").value(h.parameterCount());
            w.name("cognitiveComplexity").value(h.cognitiveComplexity());
            w.name("outboundDistinctCalls").value(h.outboundDistinctCalls());
            w.name("dominantDriver").value(h.dominantDriver());
            writeStringList(w, "riskFactors", h.riskFactors());
            w.endObject();
        }
        w.endArray();
    }

    private static void writeFiles(JsonWriter w, List<AnalysisReport.FileMetric> files, String analyzedPath,
                                   AnalysisReport.RiskModel riskModel, String pipeline, double methodRiskMinScore)
            throws IOException {
        String profileVersion = riskModel.version();
        w.name("files");
        w.beginArray();
        for (AnalysisReport.FileMetric file : files) {
            w.beginObject();
            w.name("path").value(file.path());
            w.name("packageName").value(file.packageName());
            w.name("physicalLines").value(file.physicalLines());
            w.name("codeLines").value(file.codeLines());
            w.name("classCount").value(file.classCount());
            w.name("methodCount").value(file.methodCount());
            w.name("totalCyclomaticComplexity").value(file.totalCyclomaticComplexity());
            w.name("maxCyclomaticComplexity").value(file.maxCyclomaticComplexity());
            w.name("riskScore").value(file.riskScore());
            w.name("complexityScore").value(file.riskScore());
            w.name("complexityBand").value(HealthScoreEngine.complexityBand(file.riskScore()));
            w.name("qualityScore").value(file.qualityScore());
            w.name("qualityBand").value(HealthScoreEngine.qualityBand(file.qualityScore()));
            w.name("riskLevel").value(file.riskLevel().name());
            writeStringList(w, "riskFactors", file.riskFactors());
            w.name("classes");
            w.beginArray();
            for (AnalysisReport.ClassMetric type : file.classes()) {
                w.beginObject();
                w.name("name").value(type.name());
                w.name("kind").value(type.kind());
                w.name("startLine").value(type.startLine());
                w.name("endLine").value(type.endLine());
                w.name("methodCount").value(type.methodCount());
                w.name("publicMethodCount").value(type.publicMethodCount());
                w.name("codeLines").value(type.codeLines());
                int classPhysical = Math.max(0, type.endLine() - type.startLine() + 1);
                w.name("physicalLines").value(classPhysical);
                w.name("nonCodeLines").value(Math.max(0, classPhysical - type.codeLines()));
                w.name("efferentCouplingProxy").value(type.efferentCouplingProxy());
                w.name("weightedMethodComplexity").value(type.weightedMethodComplexity());
                w.name("maxMethodComplexity").value(type.maxMethodComplexity());
                w.name("averageMethodComplexity").value(type.averageMethodComplexity());
                w.name("riskScore").value(type.riskScore());
                w.name("complexityScore").value(type.riskScore());
                w.name("complexityBand").value(HealthScoreEngine.complexityBand(type.riskScore()));
                w.name("qualityScore").value(type.qualityScore());
                w.name("qualityBand").value(HealthScoreEngine.qualityBand(type.qualityScore()));
                w.name("riskLevel").value(type.riskLevel().name());
                writeStringList(w, "riskFactors", type.riskFactors());
                w.name("lcom3").value(type.lcom3());
                w.name("godClassCandidate").value(type.godClassCandidate());
                writeStringList(w, "dependentTypes", type.dependentTypes());
                w.name("methods");
                w.beginArray();
                for (MethodMetric m : type.methods()) {
                    if (MethodAccessorFilter.omitFromPublishedReport(m, methodRiskMinScore)) {
                        continue;
                    }
                    writeMethod(w, file.path(), type.name(), m, analyzedPath, profileVersion, pipeline, riskModel);
                }
                w.endArray();
                w.endObject();
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
    }

    private static void writeMethod(JsonWriter w, String filePath, String className, MethodMetric m,
                                    String analyzedPath, String profileVersion, String pipeline,
                                    AnalysisReport.RiskModel riskModel)
            throws IOException {
        w.beginObject();
        w.name("methodUid").value(methodUid(filePath, className, m.signature(), m.startLine()));
        w.name("name").value(m.name());
        w.name("kind").value(m.kind());
        w.name("signature").value(m.signature());
        w.name("startLine").value(m.startLine());
        w.name("endLine").value(m.endLine());
        w.name("cyclomaticComplexity").value(m.cyclomaticComplexity());
        w.name("physicalLines").value(m.physicalLines());
        w.name("codeLines").value(m.codeLines());
        w.name("nonCodeLines").value(Math.max(0, m.physicalLines() - m.codeLines()));
        w.name("logicalStatements").value(m.logicalStatements());
        w.name("cognitiveComplexity").value(m.cognitiveComplexity());
        w.name("npathComplexity").value(m.npathComplexity());
        w.name("npathIncludedInComplexityScore").value(true);
        w.name("npathCapped").value(m.npathComplexity() >= PmdMetricsAligner.NPATH_CAP);
        w.name("ncssCount").value(m.ncssCount());
        w.name("exitPoints").value(m.exitPoints());
        w.name("catchClauses").value(m.catchClauses());
        w.name("switchCases").value(m.switchCases());
        w.name("outboundDistinctCalls").value(m.outboundDistinctCalls());
        w.name("lambdaCount").value(m.lambdaCount());
        w.name("maxTryNestingDepth").value(m.maxTryNestingDepth());
        w.name("localVariableCount").value(m.localVariableCount());
        w.name("maxMethodCallChainLength").value(m.maxMethodCallChainLength());
        w.name("emptyCatchBlocks").value(m.emptyCatchBlocks());
        w.name("catchExceptionOrThrowable").value(m.catchExceptionOrThrowable());
        w.name("catchWithOnlyPrintStackTrace").value(m.catchWithOnlyPrintStackTrace());
        w.name("primitiveObsessionIndex").value(m.primitiveObsessionIndex());
        w.name("maxBooleanOperatorsInCondition").value(m.maxBooleanOperatorsInCondition());
        w.name("maxNestingDepth").value(m.maxNestingDepth());
        w.name("parameterCount").value(m.parameterCount());
        w.name("godMethod").value(m.godMethod());
        w.name("riskScore").value(m.riskScore());
        w.name("complexityScore").value(m.riskScore());
        w.name("complexityBand").value(HealthScoreEngine.complexityBand(m.riskScore()));
        w.name("qualityScore").value(m.qualityScore());
        w.name("qualityBand").value(HealthScoreEngine.qualityBand(m.qualityScore()));
        w.name("excludedFromHealthScore").value(m.excludedFromHealthScore());
        w.name("riskLevel").value(m.riskLevel().name());
        w.name("metricsImputed").value(m.metricsImputed());
        writeStringList(w, "riskFactors", m.riskFactors());
        if (m.riskBreakdown() != null) {
            writeBreakdown(w, m.riskBreakdown());
            writeRiskScoreExplain(w, m, m.riskBreakdown(), riskModel);
        }
        writeRiskInputs(w, m, analyzedPath, profileVersion, pipeline);
        writeExceptionSmells(w, m.exceptionSmells());
        writeCodeSmells(w, m.codeSmells());
        w.name("methodHash").value(m.methodHash());
        w.name("analysisReused").value(m.analysisReused());
        w.name("bodyHash").value(m.bodyHash());
        w.name("structuralHash").value(m.structuralHash());
        writeHashMeta(w, m);
        w.endObject();
    }

    /** Root-level hints for JSON consumers (DB host, diff tools). */
    private static void writeJsonLegend(JsonWriter w, AnalysisReport report, double methodRiskMinScore)
            throws IOException {
        w.name("jsonLegend");
        w.beginObject();
        w.name("omittedMethods").value(
                "files[].classes[].methods omits getters/setters/is-accessors and riskScore < "
                        + methodRiskMinScore + " (same floor as env.method_risk_analyze / db.method.risk.min.score). "
                        + "summary.methodCount still includes them. 1-2 line accessors are not risk-scored.");
        w.name("riskScoreDimensions").value(
                "Per method: rawValue = counted metric; subScore = 0-100; threshold* = profile dikkat/yuksek/kritik.");
        w.name("riskScoreComposition").value(
                "PMD: complexityScore high=bad (100-raw), qualityScore high=good. They are not merged. "
                        + "riskScore is the complexity score. projectQualityScore is the LOC-weighted class quality. "
                        + "See riskModel.formula.");
        w.name("npath").value(
                "npathComplexity is measured for every method and included in complexityScore. "
                        + "The PMD NPathComplexity rule is not in the ruleset (it can lock the scan). "
                        + "npathCapped=true means the heavy-method cap was scored instead of an unbounded count.");
        w.name("methodHash").value(
                "Incremental cache key (analyzer-state.json or pmd-analyzer-state.json). Independent of scan_run_id / DB.");
        w.name("metricsImputed").value(
                "true = PMD/JVM could not finish this method; riskScore is policy max (100), CC/LOC nominal; see riskFactors.");
        w.name("analysisReused").value(
                "true = reused prior risk/metrics from state (methodHash unchanged). File-level skip sets all methods reused.");
        w.name("bodyHash").value("Duplicate detection: BIREBIR_METIN (normalized body text, names/literals kept).");
        w.name("structuralHash").value(
                "Duplicate detection: YAPISAL_KLON (identifiers/literals abstracted to ID/LIT).");
        w.name("scanRunId").value("Not in analyzer JSON — inventory host sets UUID per scan when loading DB.");
        w.name("incrementalCache").value(report.cacheStatistics() != null
                ? "See cacheStatistics (--state=). New scan_run_id does not disable local file cache."
                : "No --state= on this run; every file was fully analyzed.");
        w.endObject();
    }

    private static void writeRiskScoreExplain(JsonWriter w, MethodMetric m, RiskBreakdown b,
                                              AnalysisReport.RiskModel riskModel) throws IOException {
        String driverId = RiskBreakdownUtil.dominantDriver(b);
        w.name("riskScoreComposition");
        w.beginObject();
        w.name("dominantDriverId").value(driverId);
        w.name("dominantDriverLabelTr").value(RiskBreakdownUtil.driverLabelTr(driverId));
        w.name("dominantSubScore").value(b.dominantSubScore());
        w.name("weightedBlend").value(b.weightedBlend());
        w.name("compoundBonus").value(b.compoundBonus());
        w.name("finalScore").value(b.finalScore());
        w.endObject();

        List<DimensionRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : riskModel.thresholds().entrySet()) {
            String id = entry.getKey();
            List<Integer> t = entry.getValue();
            if (t == null || t.size() < 3) {
                continue;
            }
            double sub = subScoreForDimensionId(b, id);
            int raw = rawValueForDimensionId(m, id);
            if (sub < 0.01 && raw == 0) {
                continue;
            }
            rows.add(new DimensionRow(id, RiskBreakdownUtil.driverLabelTr(id), raw, sub, t.get(0), t.get(1), t.get(2),
                    riskModel.weights().getOrDefault(id, 0.0)));
        }
        rows.sort(Comparator.comparingDouble(DimensionRow::subScore).reversed());

        w.name("riskScoreDimensions");
        w.beginArray();
        for (DimensionRow row : rows) {
            w.beginObject();
            w.name("id").value(row.id());
            w.name("labelTr").value(row.labelTr());
            w.name("rawValue").value(row.rawValue());
            w.name("subScore").value(row.subScore());
            w.name("thresholdDikkat").value(row.dikkat());
            w.name("thresholdYuksek").value(row.yuksek());
            w.name("thresholdKritik").value(row.kritik());
            w.name("blendWeight").value(row.blendWeight());
            w.endObject();
        }
        w.endArray();
    }

    private static void writeHashMeta(JsonWriter w, MethodMetric m) throws IOException {
        w.name("hashMeta");
        w.beginObject();
        w.name("methodHashPurpose").value("Incremental cache key (local state file for this pipeline).");
        w.name("analysisReusedMeaning").value(m.analysisReused()
                ? "Skipped full re-score; prior metrics copied (methodHash match)."
                : "Full metric/risk pass for this method.");
        w.name("bodyHashPurpose").value("Exact duplicate fingerprint (duplicateGroups BIREBIR_METIN).");
        w.name("structuralHashPurpose").value("Structural clone fingerprint (duplicateGroups YAPISAL_KLON).");
        w.endObject();
    }

    private record DimensionRow(String id, String labelTr, int rawValue, double subScore,
                                int dikkat, int yuksek, int kritik, double blendWeight) {
    }

    private static double subScoreForDimensionId(RiskBreakdown b, String id) {
        return switch (id) {
            case "branching" -> b.ccSubScore();
            case "length" -> b.locSubScore();
            case "nesting" -> b.nestingSubScore();
            case "parameters" -> b.paramsSubScore();
            case "cognitive" -> b.cognitiveSubScore();
            case "outboundDistinctCalls" -> b.outboundSubScore();
            case "exitPoints" -> b.exitSubScore();
            default -> b.extraSubScores().getOrDefault(id, 0.0);
        };
    }

    private static int rawValueForDimensionId(MethodMetric m, String id) {
        MethodScanValues scan = MethodScanValues.fromMetric(m);
        return switch (id) {
            case "branching" -> scan.cyclomaticComplexity();
            case "length" -> scan.codeLines();
            case "nesting" -> scan.maxNestingDepth();
            case "parameters" -> scan.parameterCount();
            case "cognitive" -> scan.cognitiveComplexity();
            case "logicalStatements" -> scan.logicalStatements();
            case "exitPoints" -> scan.exitPoints();
            case "lambdaCount" -> scan.lambdaCount();
            case "switchCases" -> scan.switchCases();
            case "maxTryNestingDepth" -> scan.maxTryNestingDepth();
            case "localVariableCount" -> scan.localVariableCount();
            case "maxMethodCallChainLength" -> scan.maxMethodCallChainLength();
            case "catchClauses" -> scan.catchClauses();
            case "emptyCatchBlocks" -> scan.emptyCatchBlocks();
            case "catchExceptionOrThrowable" -> scan.catchExceptionOrThrowable();
            case "catchWithOnlyPrintStackTrace" -> scan.catchWithOnlyPrintStackTrace();
            case "primitiveObsessionIndex" -> scan.primitiveObsessionIndex();
            case "maxBooleanOperatorsInCondition" -> scan.maxBooleanOperatorsInCondition();
            case "rawTypeUsage" -> scan.rawTypeUsage();
            case "stringConcatInLoop" -> scan.stringConcatInLoop();
            case "hardcodedLiteralCount" -> scan.hardcodedLiteralCount();
            case "swallowedExceptionSmells" -> scan.swallowedExceptionSmells();
            case "genericExceptionSmells" -> scan.genericExceptionSmells();
            case "outboundDistinctCalls" -> scan.outboundDistinctCalls();
            default -> 0;
        };
    }

    private static void writeDuplicateStatistics(JsonWriter w, AnalysisReport.DuplicateStatistics stats)
            throws IOException {
        w.name("duplicateStatistics");
        w.beginObject();
        w.name("exactGroups").value(stats.exactGroups());
        w.name("methodsInExactGroups").value(stats.methodsInExactGroups());
        w.name("nearMissGroups").value(stats.nearMissGroups());
        w.name("methodsInNearMissGroups").value(stats.methodsInNearMissGroups());
        w.endObject();
    }

    private static void writeDuplicateGroups(JsonWriter w, List<AnalysisReport.DuplicateGroup> groups)
            throws IOException {
        w.name("duplicateGroups");
        w.beginArray();
        for (AnalysisReport.DuplicateGroup group : groups) {
            w.beginObject();
            w.name("groupId").value(group.groupId());
            w.name("similarityType").value(group.similarityType());
            w.name("similarityTypeLabelTr").value(DuplicateSimilarityKind.labelTrFor(group.similarityType()));
            w.name("matchedTokenCount").value(group.matchedTokenCount());
            w.name("duplicatedLines").value(group.duplicatedLines());
            w.name("similarityPercent").value(group.similarityPercent());
            w.name("members");
            w.beginArray();
            for (AnalysisReport.DuplicateMember member : group.members()) {
                w.beginObject();
                w.name("file").value(member.file());
                w.name("className").value(member.className());
                w.name("method").value(member.method());
                w.name("startLine").value(member.startLine());
                w.name("endLine").value(member.endLine());
                w.endObject();
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
    }

    private static void writePmdRulesetPhase(JsonWriter w, AnalysisReport.PmdRulesetPhase phase)
            throws IOException {
        w.name("pmdRulesetPhase");
        if (phase == null) {
            w.nullValue();
            return;
        }
        w.beginObject();
        w.name("rulesetPath").value(phase.rulesetPath());
        w.name("violationCount").value(phase.violationCount());
        w.name("violationsXmlPath").value(phase.violationsXmlPath());
        w.name("violationsSample");
        w.beginArray();
        for (AnalysisReport.PmdViolationRow row : phase.violationsSample()) {
            w.beginObject();
            w.name("file").value(row.file());
            w.name("beginLine").value(row.beginLine());
            w.name("endLine").value(row.endLine());
            w.name("ruleName").value(row.ruleName());
            w.name("description").value(row.description());
            w.endObject();
        }
        w.endArray();
        w.endObject();
    }

    private static void writeCacheStatistics(JsonWriter w, AnalysisReport.CacheStatistics cache) throws IOException {
        if (cache == null) {
            return;
        }
        w.name("cacheStatistics");
        w.beginObject();
        w.name("totalFiles").value(cache.totalFiles());
        w.name("filesSkippedViaFileHash").value(cache.filesSkippedViaFileHash());
        w.name("filesReanalyzed").value(cache.filesReanalyzed());
        w.name("filesSemanticCosmeticOnly").value(cache.filesSemanticCosmeticOnly());
        w.name("filesSkippedViaModuleBulk").value(cache.filesSkippedViaModuleBulk());
        w.name("modulesUnchanged").value(cache.modulesUnchanged());
        w.name("totalMethods").value(cache.totalMethods());
        w.name("methodsSkippedViaHash").value(cache.methodsSkippedViaHash());
        w.name("methodsReanalyzed").value(cache.methodsReanalyzed());
        w.name("scanTimeMillis").value(cache.scanTimeMillis());
        w.endObject();
    }

    /** Host uygulamasının DB {@code risk_inputs_json} alanına map edebileceği özet (JDBC bu repoda yok). */
    /** {@link ComplexityJson} ve paket içi rapor yazımı */
    static void writeRiskInputs(JsonWriter w, MethodMetric m, String analyzedPath, String profileVersion,
                                        String pipeline) throws IOException {
        w.name("riskInputs");
        w.beginObject();
        w.name("profile").value(profileVersion == null ? "" : profileVersion);
        w.name("analyzedPath").value(analyzedPath == null ? "" : analyzedPath);
        w.name("pipeline").value(pipeline);
        w.name("complexityScore").value(RiskCalculator.round3(m.riskScore()));
        w.name("qualityScore").value(RiskCalculator.round3(m.qualityScore()));
        w.name("npathIncludedInComplexityScore").value(!m.excludedFromHealthScore());
        w.name("npathCapped").value(m.npathComplexity() >= PmdMetricsAligner.NPATH_CAP);
        w.name("metrics");
        w.beginObject();
        w.name("physicalLines").value(m.physicalLines());
        w.name("codeLines").value(m.codeLines());
        w.name("nonCodeLines").value(Math.max(0, m.physicalLines() - m.codeLines()));
        w.name("cyclomaticComplexity").value(m.cyclomaticComplexity());
        w.name("cognitiveComplexity").value(m.cognitiveComplexity());
        w.name("npathComplexity").value(m.npathComplexity());
        w.name("ncssCount").value(m.ncssCount());
        w.name("maxNestingDepth").value(m.maxNestingDepth());
        w.name("parameterCount").value(m.parameterCount());
        w.name("outboundDistinctCalls").value(m.outboundDistinctCalls());
        w.endObject();
        writeStringList(w, "riskFactors", m.riskFactors());
        if (m.riskBreakdown() != null) {
            writeBreakdown(w, m.riskBreakdown());
        }
        w.endObject();
    }

    static String methodUid(String filePath, String className, String signature, int startLine) {
        return filePath + "#" + className + "#" + signature + "#" + startLine;
    }

    private static void writeBreakdown(JsonWriter w, RiskBreakdown b) throws IOException {
        w.name("riskBreakdown");
        w.beginObject();
        w.name("cyclomaticComplexity").value(b.cyclomaticComplexity());
        w.name("codeLines").value(b.codeLines());
        w.name("maxNestingDepth").value(b.maxNestingDepth());
        w.name("parameterCount").value(b.parameterCount());
        w.name("ccSubScore").value(b.ccSubScore());
        w.name("locSubScore").value(b.locSubScore());
        w.name("nestingSubScore").value(b.nestingSubScore());
        w.name("paramsSubScore").value(b.paramsSubScore());
        w.name("cognitiveSubScore").value(b.cognitiveSubScore());
        w.name("outboundSubScore").value(b.outboundSubScore());
        w.name("exitSubScore").value(b.exitSubScore());
        w.name("extraSubScores");
        w.beginObject();
        for (Map.Entry<String, Double> entry : b.extraSubScores().entrySet()) {
            w.name(entry.getKey()).value(entry.getValue());
        }
        w.endObject();
        w.name("dominantSubScore").value(b.dominantSubScore());
        w.name("weightedBlend").value(b.weightedBlend());
        w.name("compoundBonus").value(b.compoundBonus());
        w.name("finalScore").value(b.finalScore());
        w.endObject();
    }

    private static void writeErrors(JsonWriter w, List<AnalysisReport.FileError> errors) throws IOException {
        w.name("errors");
        w.beginArray();
        for (AnalysisReport.FileError e : errors) {
            w.beginObject();
            w.name("file").value(e.file());
            w.name("message").value(e.message());
            w.name("category").value(e.category().name());
            w.name("className").value(e.className() == null ? "" : e.className());
            w.name("methodSignature").value(e.methodSignature() == null ? "" : e.methodSignature());
            w.name("startLine").value(e.startLine());
            w.name("endLine").value(e.endLine());
            w.endObject();
        }
        w.endArray();
    }

    private static void writeStringList(JsonWriter w, String name, List<String> values) throws IOException {
        w.name(name);
        w.beginArray();
        for (String v : values) {
            w.value(v);
        }
        w.endArray();
    }

    private static void writeIncrementalChanges(JsonWriter w, IncrementalChanges changes) throws IOException {
        w.name("incrementalChanges");
        w.beginObject();
        w.name("comparedToPreviousScan").value(changes.comparedToPreviousScan());
        w.name("filesAdded").value(changes.filesAdded());
        w.name("filesRemoved").value(changes.filesRemoved());
        w.name("filesModified").value(changes.filesModified());
        w.name("filesCosmeticOnly").value(changes.filesCosmeticOnly());
        w.name("filesUnchanged").value(changes.filesUnchanged());
        w.name("methodsAdded").value(changes.methodsAdded());
        w.name("methodsRemoved").value(changes.methodsRemoved());
        w.name("methodsModified").value(changes.methodsModified());
        w.name("fileChanges");
        w.beginArray();
        for (IncrementalChanges.FilePathChange fc : changes.fileChanges()) {
            w.beginObject();
            w.name("path").value(fc.path());
            w.name("kind").value(fc.kind().name());
            w.endObject();
        }
        w.endArray();
        w.name("methodChanges");
        w.beginArray();
        for (IncrementalChanges.MethodPathChange mc : changes.methodChanges()) {
            w.beginObject();
            w.name("file").value(mc.file());
            w.name("className").value(mc.className());
            w.name("methodSignature").value(mc.methodSignature());
            w.name("startLine").value(mc.startLine());
            w.name("endLine").value(mc.endLine());
            w.name("kind").value(mc.kind().name());
            w.endObject();
        }
        w.endArray();
        w.endObject();
    }

    private static void writeExceptionSmells(JsonWriter w, List<ExceptionSmell> smells) throws IOException {
        w.name("exceptionSmells");
        w.beginArray();
        for (ExceptionSmell s : smells) {
            w.beginObject();
            w.name("type").value(s.type().name());
            w.name("exceptionType").value(s.exceptionType());
            w.name("line").value(s.line());
            w.endObject();
        }
        w.endArray();
    }

    private static void writeCodeSmells(JsonWriter w, List<CodeSmell> smells) throws IOException {
        w.name("codeSmells");
        w.beginArray();
        for (CodeSmell s : smells) {
            w.beginObject();
            w.name("type").value(s.type().name());
            w.name("detail").value(s.detail());
            w.name("line").value(s.line());
            w.endObject();
        }
        w.endArray();
    }
}
