package com.scanner.complexity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Aggregated run outcome for console, Markdown, and JSON. */
record ScanDiagnostics(
        String completionStatus,
        String lastPhaseTr,
        String fatalPhaseTr,
        String fatalMessage,
        String lastFileAttempted,
        double parseFailureRatio,
        Map<String, Integer> errorsByCategory,
        List<String> recommendationsTr,
        String lastPhaseEn,
        String fatalPhaseEn,
        List<String> recommendationsEn) {

    static ScanDiagnostics from(AnalysisReport report, ScanRunContext context, Integer exitCode) {
        AnalysisReport.Summary s = report.summary();
        Map<ScanErrorCategory, Integer> byCat = new EnumMap<>(ScanErrorCategory.class);
        for (AnalysisReport.FileError e : report.errors()) {
            ScanErrorCategory cat = e.category();
            byCat.merge(cat, 1, Integer::sum);
        }
        Map<String, Integer> named = new LinkedHashMap<>();
        byCat.entrySet().stream()
                .sorted(Map.Entry.<ScanErrorCategory, Integer>comparingByValue().reversed())
                .forEach(e -> named.put(e.getKey().name(), e.getValue()));

        double ratio = s.filesScanned() > 0 ? (double) s.filesFailed() / s.filesScanned() : 0.0;
        List<String> tipsTr = buildRecommendationsTr(named, ratio, report.parserLanguageLevel(), context, exitCode);
        List<String> tipsEn = buildRecommendationsEn(named, ratio, report.parserLanguageLevel(), context, exitCode);

        String status;
        String fatalTr = null;
        String fatalEn = null;
        String fatalMsg = null;
        if (context != null && context.fatal()) {
            status = "FAILED_FATAL";
            if (context.fatalPhase() != null) {
                fatalTr = context.fatalPhase().labelTr();
                fatalEn = context.fatalPhase().labelEn();
            }
            fatalMsg = context.fatalMessage();
        } else if (s.filesFailed() > 0) {
            status = "COMPLETED_WITH_FILE_ERRORS";
        } else {
            status = "COMPLETED_OK";
        }

        ScanPhase lastPhase = context != null ? context.phase() : ScanPhase.COMPLETE;
        String lastFile = context != null ? context.currentFile() : null;

        return new ScanDiagnostics(
                status,
                lastPhase.labelTr(),
                fatalTr,
                fatalMsg,
                lastFile,
                ratio,
                Map.copyOf(named),
                List.copyOf(tipsTr),
                lastPhase.labelEn(),
                fatalEn,
                List.copyOf(tipsEn));
    }

    private static List<String> buildRecommendationsTr(
            Map<String, Integer> byCat,
            double ratio,
            String languageLevel,
            ScanRunContext context,
            Integer exitCode) {
        List<String> tips = new ArrayList<>();
        if (context != null && context.fatal()) {
            tips.add("Süreç fatal hata ile kesildi; aşama: "
                    + (context.fatalPhase() != null ? context.fatalPhase().labelTr() : "?")
                    + (context.currentFile() != null ? "; son işlenen dosya: `" + context.currentFile() + "`" : "")
                    + ".");
        }
        int syntax = byCat.getOrDefault(ScanErrorCategory.PARSE_SYNTAX.name(), 0);
        int lang = byCat.getOrDefault(ScanErrorCategory.PARSE_LANGUAGE_LEVEL.name(), 0);
        if (syntax + lang > 0) {
            tips.add("Parse hataları: LANGUAGE_LEVEL, encoding, bozuk kaynak dosyası.");
        }
        if (lang > 0) {
            tips.add("Karışık Java sürümleri: dosya başına dil fallback var; yine de "
                    + "LANGUAGE_LEVEL=" + languageLevel + " yetersiz kalabilir — JAVA_21 deneyin veya modülü ayrı tarayın.");
        }
        if (ratio > 0.1 && ratio <= 1.0) {
            tips.add(String.format(Locale.US,
                    "Parse hata oranı %.1f%% — kökü src/main/java'ya daraltın, EXCLUDE_GLOBS ile target/build/generated hariç tutun.",
                    ratio * 100));
        }
        if (exitCode != null && exitCode == ScanExitEvaluator.EXIT_PARSE_FAILURE_RATIO) {
            tips.add("CI exit 2: --max-failure-ratio aşıldı; rapor yine üretildi, eşiği gevşetin veya kaynakları düzeltin.");
        }
        if (exitCode != null && exitCode == ScanExitEvaluator.EXIT_RISK_GATE) {
            tips.add("CI exit 3: --fail-on-risk tetiklendi; parse başarısından bağımsız risk gate.");
        }
        if (tips.isEmpty()) {
            tips.add("Kritik dosya hatası yok; özet ve metod tablosunu kullanabilirsiniz.");
        }
        return tips;
    }

    private static List<String> buildRecommendationsEn(
            Map<String, Integer> byCat,
            double ratio,
            String languageLevel,
            ScanRunContext context,
            Integer exitCode) {
        List<String> tips = new ArrayList<>();
        if (context != null && context.fatal()) {
            tips.add("Run stopped with fatal error; phase: "
                    + (context.fatalPhase() != null ? context.fatalPhase().labelEn() : "?")
                    + (context.currentFile() != null ? "; last file: " + context.currentFile() : "")
                    + ".");
        }
        int syntax = byCat.getOrDefault(ScanErrorCategory.PARSE_SYNTAX.name(), 0);
        int lang = byCat.getOrDefault(ScanErrorCategory.PARSE_LANGUAGE_LEVEL.name(), 0);
        if (syntax + lang > 0) {
            tips.add("Parse errors: check LANGUAGE_LEVEL and source encoding.");
        }
        if (lang > 0) {
            tips.add("Mixed Java versions: per-file language fallback is enabled; try LANGUAGE_LEVEL=JAVA_21 "
                    + "(current " + languageLevel + ") or scan modules separately.");
        }
        if (ratio > 0.1 && ratio <= 1.0) {
            tips.add(String.format(Locale.US,
                    "Parse failure ratio %.1f%% — narrow --path to src/main/java; exclude target/build/generated.",
                    ratio * 100));
        }
        if (exitCode != null && exitCode == ScanExitEvaluator.EXIT_PARSE_FAILURE_RATIO) {
            tips.add("CI exit 2: --max-failure-ratio exceeded; report was still written — fix sources or raise threshold.");
        }
        if (exitCode != null && exitCode == ScanExitEvaluator.EXIT_RISK_GATE) {
            tips.add("CI exit 3: --fail-on-risk triggered (independent of parse success).");
        }
        if (tips.isEmpty()) {
            tips.add("No critical file errors; use summary and method table in the report.");
        }
        return tips;
    }
}
