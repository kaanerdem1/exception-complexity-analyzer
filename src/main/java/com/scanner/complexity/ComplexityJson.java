package com.scanner.complexity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Locale;

/**
 * Veritabanına (env.method_risk_analyze, env.method_similarity) yazılan JSON alanları.
 * Rapor dosyası benzerlik grubu taşımaz; gruplar yalnız bu sütunlara gider.
 */
public final class ComplexityJson {

    private ComplexityJson() { }

    /** "parser" veya "pmd-native" (rapor JSON'undaki riskInputs.pipeline ile aynı) */
    public static String pipeline(AnalysisReport report) {
        return report.tool().toLowerCase(Locale.ROOT).contains("pmd") ? "pmd-native" : "parser";
    }

    /** Veritabanı risk_inputs_json. metrics ve emptyCatchBlocks count'tur; iki skor puandır. */
    public static String riskInputs(AnalysisReport report, MethodMetric m) {
        StringWriter out = new StringWriter();
        try {
            JsonWriter w = new JsonWriter(out);
            w.beginObject(); // writeRiskInputs bir alan adıyla yazar; dıştaki nesne atılır
            AnalysisReportJsonWriter.writeRiskInputs(w, m, report.riskModel().version(), pipeline(report));
            w.endObject();
            w.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String s = out.toString();
        return s.substring("{\"riskInputs\":".length(), s.length() - 1);
    }

    /** Kopya kod grubunun üyeleri: [{file, className, method, startLine, endLine}, ...] */
    public static String members(AnalysisReport.DuplicateGroup g) {
        JsonArray arr = new JsonArray();
        for (AnalysisReport.DuplicateMember m : g.members()) {
            JsonObject o = new JsonObject();
            o.addProperty("file", m.file());
            o.addProperty("className", m.className());
            o.addProperty("method", m.method());
            o.addProperty("startLine", m.startLine());
            o.addProperty("endLine", m.endLine());
            arr.add(o);
        }
        return arr.toString();
    }

    /** Grubun tabloda sütunu olmayan bilgileri */
    public static String groupMeta(AnalysisReport report, AnalysisReport.DuplicateGroup g) {
        JsonObject o = new JsonObject();
        o.addProperty("groupId", g.groupId());
        o.addProperty("similarityTypeLabelTr", DuplicateSimilarityKind.labelTrFor(g.similarityType()));
        o.addProperty("matchedTokenCount", g.matchedTokenCount());
        o.addProperty("memberCount", g.members().size());
        o.addProperty("pipeline", pipeline(report));
        o.addProperty("analyzedPath", report.analyzedPath());
        return o.toString();
    }

    /** Raporlardaki methodUid: path#className#signature#startLine */
    public static String methodUid(String filePath, String className, MethodMetric m) {
        return AnalysisReportJsonWriter.methodUid(filePath, className, m.signature(), m.startLine());
    }
}
