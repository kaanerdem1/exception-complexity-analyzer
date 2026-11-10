package com.scanner.complexity;

import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
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
        writeReading(w, methodRiskMinScore);
        writeSummary(w, report.summary());
        writeHealthDrill(w, report);
        writeScanDiagnostics(w, report.scanDiagnostics());
        if (report.pmdRulesetPhase() != null) {
            writePmdRulesetPhase(w, report.pmdRulesetPhase());
        }
        writeHotspots(w, report.topRiskyMethods());
        writeFiles(w, report.files(), methodRiskMinScore);
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
        w.name("projectComplexityScore").value(s.projectRiskScore());
        w.name("projectComplexityBand").value(HealthScoreEngine.complexityBand(s.projectRiskScore()));
        w.name("projectQualityScore").value(s.projectQualityScore());
        w.name("projectQualityBand").value(HealthScoreEngine.qualityBand(s.projectQualityScore()));
        w.name("moduleSummaries");
        w.beginArray();
        for (AnalysisReport.ModuleRiskSummary module : s.moduleSummaries()) {
            w.beginObject();
            w.name("moduleRoot").value(module.moduleRoot());
            w.name("methodCount").value(module.methodCount());
            w.name("codeLines").value(module.codeLines());
            w.name("complexityScore").value(module.locWeightedRiskScore());
            w.name("complexityBand").value(HealthScoreEngine.complexityBand(module.locWeightedRiskScore()));
            w.name("qualityScore").value(module.locWeightedQualityScore());
            w.name("qualityBand").value(HealthScoreEngine.qualityBand(module.locWeightedQualityScore()));
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
            w.name("complexityScore").value(h.riskScore());
            w.name("complexityBand").value(HealthScoreEngine.complexityBand(h.riskScore()));
            w.name("qualityScore").value(h.qualityScore());
            w.name("qualityBand").value(HealthScoreEngine.qualityBand(h.qualityScore()));
            w.name("cyclomaticComplexity").value(h.cyclomaticComplexity());
            w.name("cognitiveComplexity").value(h.cognitiveComplexity());
            w.name("codeLines").value(h.codeLines());
            w.name("maxNestingDepth").value(h.maxNestingDepth());
            w.name("parameterCount").value(h.parameterCount());
            writeStringList(w, "riskFactors", h.riskFactors());
            w.endObject();
        }
        w.endArray();
    }

    private static void writeFiles(JsonWriter w, List<AnalysisReport.FileMetric> files, double methodRiskMinScore)
            throws IOException {
        w.name("files");
        w.beginArray();
        for (AnalysisReport.FileMetric file : files) {
            w.beginObject();
            w.name("path").value(file.path());
            w.name("packageName").value(file.packageName());
            w.name("codeLines").value(file.codeLines());
            w.name("classCount").value(file.classCount());
            w.name("methodCount").value(file.methodCount());
            w.name("complexityScore").value(file.riskScore());
            w.name("complexityBand").value(HealthScoreEngine.complexityBand(file.riskScore()));
            w.name("qualityScore").value(file.qualityScore());
            w.name("qualityBand").value(HealthScoreEngine.qualityBand(file.qualityScore()));
            w.name("classes");
            w.beginArray();
            for (AnalysisReport.ClassMetric type : file.classes()) {
                w.beginObject();
                w.name("name").value(type.name());
                w.name("kind").value(type.kind());
                w.name("startLine").value(type.startLine());
                w.name("endLine").value(type.endLine());
                w.name("methodCount").value(type.methodCount());
                w.name("codeLines").value(type.codeLines());
                w.name("complexityScore").value(type.riskScore());
                w.name("complexityBand").value(HealthScoreEngine.complexityBand(type.riskScore()));
                w.name("qualityScore").value(type.qualityScore());
                w.name("qualityBand").value(HealthScoreEngine.qualityBand(type.qualityScore()));
                writeStringList(w, "riskFactors", type.riskFactors());
                w.name("methods");
                w.beginArray();
                for (MethodMetric m : type.methods()) {
                    if (MethodAccessorFilter.omitFromPublishedReport(m, methodRiskMinScore)) {
                        continue;
                    }
                    writeMethod(w, file.path(), type.name(), m);
                }
                w.endArray();
                w.endObject();
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
    }

    private static void writeMethod(JsonWriter w, String filePath, String className, MethodMetric m)
            throws IOException {
        w.beginObject();
        w.name("methodUid").value(methodUid(filePath, className, m.signature(), m.startLine()));
        w.name("name").value(m.name());
        w.name("kind").value(m.kind());
        w.name("signature").value(m.signature());
        w.name("startLine").value(m.startLine());
        w.name("endLine").value(m.endLine());
        w.name("codeLines").value(m.codeLines());
        w.name("cyclomaticComplexity").value(m.cyclomaticComplexity());
        w.name("cognitiveComplexity").value(m.cognitiveComplexity());
        w.name("npathComplexity").value(m.npathComplexity());
        w.name("npathCapped").value(m.npathComplexity() >= PmdMetricsAligner.NPATH_CAP);
        w.name("ncssCount").value(m.ncssCount());
        w.name("maxNestingDepth").value(m.maxNestingDepth());
        w.name("parameterCount").value(m.parameterCount());
        w.name("complexityScore").value(m.riskScore());
        w.name("complexityBand").value(HealthScoreEngine.complexityBand(m.riskScore()));
        w.name("qualityScore").value(m.qualityScore());
        w.name("qualityBand").value(HealthScoreEngine.qualityBand(m.qualityScore()));
        if (m.excludedFromHealthScore()) {
            w.name("excludedFromHealthScore").value(true);
        }
        if (m.metricsImputed()) {
            w.name("metricsImputed").value(true);
        }
        writeStringList(w, "riskFactors", m.riskFactors());
        w.endObject();
    }

    /** İki skorun yönü. PDF bunları yan yana gösterirken yönün yazılmasını ister. */
    private static void writeReading(JsonWriter w, double methodRiskMinScore) throws IOException {
        w.name("reading");
        w.beginObject();
        w.name("complexity").value("0-100, yüksek kötü");
        w.name("quality").value("0-100, yüksek iyi");
        w.name("bands").value("kalite: 90 çok iyi, 75 iyi, 50 orta, 25 zayıf, altı kritik. karmaşıklık bantları ters.");
        w.name("rollup").value("sınıf = satır ağırlıklı metot ortalaması ile sınıf kuralları, yarı yarıya. "
                + "modül ve proje = sınıf skorunun satır ağırlıklı ortalaması.");
        w.name("omittedMethods").value("trivial get/set/is ve karmaşıklığı " + methodRiskMinScore
                + " altındaki metotlar files içine yazılmaz. summary.methodCount onları da sayar.");
        w.name("npathCapped").value("true ise dallanma 15 ve üstü olduğu için yol sayısı ölçülmedi, kural 0 puan.");
        w.endObject();
    }

    private static void writePmdRulesetPhase(JsonWriter w, AnalysisReport.PmdRulesetPhase phase)
            throws IOException {
        w.name("pmdRulesetPhase");
        w.beginObject();
        w.name("rulesetPath").value(phase.rulesetPath());
        w.name("violationCount").value(phase.violationCount());
        w.endObject();
    }

    /** {@link ComplexityJson} veritabanı sütunu. Rapor dosyasına yazılmaz. */
    static void writeRiskInputs(JsonWriter w, MethodMetric m, String profileVersion, String pipeline)
            throws IOException {
        w.name("riskInputs");
        w.beginObject();
        w.name("profile").value(profileVersion == null ? "" : profileVersion);
        w.name("pipeline").value(pipeline);
        w.name("complexityScore").value(RiskCalculator.round3(m.riskScore()));
        w.name("qualityScore").value(RiskCalculator.round3(m.qualityScore()));
        w.name("npathCapped").value(m.npathComplexity() >= PmdMetricsAligner.NPATH_CAP);
        w.name("metrics");
        w.beginObject();
        w.name("codeLines").value(m.codeLines());
        w.name("cyclomaticComplexity").value(m.cyclomaticComplexity());
        w.name("cognitiveComplexity").value(m.cognitiveComplexity());
        w.name("npathComplexity").value(m.npathComplexity());
        w.name("ncssCount").value(m.ncssCount());
        w.name("maxNestingDepth").value(m.maxNestingDepth());
        w.name("parameterCount").value(m.parameterCount());
        w.endObject();
        writeStringList(w, "riskFactors", m.riskFactors());
        w.endObject();
    }

    static String methodUid(String filePath, String className, String signature, int startLine) {
        return filePath + "#" + className + "#" + signature + "#" + startLine;
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

}
