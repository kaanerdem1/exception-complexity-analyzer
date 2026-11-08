package com.scanner.complexity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class RiskBreakdownUtil {

    private RiskBreakdownUtil() {
    }

    /** Boyut id with highest sub-score (why risk is high). */
    static String dominantDriver(RiskBreakdown breakdown) {
        if (breakdown == null) {
            return "—";
        }
        List<ScoredId> scores = new ArrayList<>();
        scores.add(new ScoredId("CC", breakdown.ccSubScore()));
        scores.add(new ScoredId("LOC", breakdown.locSubScore()));
        scores.add(new ScoredId("nest", breakdown.nestingSubScore()));
        scores.add(new ScoredId("params", breakdown.paramsSubScore()));
        scores.add(new ScoredId("cog", breakdown.cognitiveSubScore()));
        scores.add(new ScoredId("fout", breakdown.outboundSubScore()));
        scores.add(new ScoredId("exit", breakdown.exitSubScore()));
        for (Map.Entry<String, Double> entry : breakdown.extraSubScores().entrySet()) {
            scores.add(new ScoredId(entry.getKey(), entry.getValue()));
        }
        return scores.stream()
                .max(Comparator.comparingDouble(ScoredId::score))
                .filter(s -> s.score() > 0.01)
                .map(ScoredId::id)
                .orElse("—");
    }

    /** Markdown raporunda kısa teknik kod → Türkçe etiket. */
    static String driverLabelTr(String driverId) {
        if (driverId == null || driverId.isBlank() || "—".equals(driverId)) {
            return "—";
        }
        return switch (driverId) {
            case "CC", "branching" -> "dallanma";
            case "LOC", "length" -> "uzunluk";
            case "nest", "nesting" -> "iç içe";
            case "params", "parameters" -> "parametre";
            case "cog", "cognitive" -> "okunabilirlik";
            case "fout", "outboundDistinctCalls" -> "dış çağrı";
            case "exit", "exitPoints" -> "çıkış noktası";
            case "logicalStatements" -> "ifade yoğunluğu";
            case "lambdaCount" -> "lambda";
            case "switchCases" -> "switch kolu";
            case "maxTryNestingDepth" -> "iç içe try";
            case "localVariableCount" -> "yerel değişken";
            case "maxMethodCallChainLength" -> "zincir çağrı";
            case "catchClauses" -> "catch sayısı";
            case "emptyCatchBlocks" -> "boş catch";
            case "catchExceptionOrThrowable" -> "geniş catch";
            case "catchWithOnlyPrintStackTrace" -> "printStackTrace catch";
            case "primitiveObsessionIndex" -> "primitive obsession";
            case "maxBooleanOperatorsInCondition" -> "complex conditional";
            case "halsteadDifficulty" -> "Halstead zorluk";
            case "halsteadEffort" -> "Halstead effort";
            case "rawTypeUsage" -> "raw tip";
            case "stringConcatInLoop" -> "döngüde concat";
            case "hardcodedLiteralCount" -> "gömülü sabit";
            case "swallowedExceptionSmells" -> "yutulan exception";
            case "genericExceptionSmells" -> "generic catch";
            default -> driverId;
        };
    }

    private record ScoredId(String id, double score) {
    }
}
