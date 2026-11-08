package com.scanner.complexity;

/**
 * Basit JavaBeans getter/setter/is-accessor — risk tablosu ve hotspot gürültüsünü azaltmak için.
 */
public final class MethodAccessorFilter {

    /** Tek/çift satır get/set/is — JavaMetrics + RiskCalculator atlanır. */
    static final int MAX_TRIVIAL_PHYSICAL_LINES = 2;
    /** JSON / DB yayın eşiği (db.method.risk.min.score varsayılanı). */
    public static final double DEFAULT_PUBLISH_MIN_SCORE = 30;

    private static final int MAX_CYCLOMATIC = 2;
    private static final int MAX_CODE_LINES = 8;
    private static final int MAX_LOGICAL_STATEMENTS = 3;

    private MethodAccessorFilter() {
    }

    public static boolean isSimpleGetterOrSetter(MethodMetric method) {
        if (method == null) {
            return false;
        }
        return isSimpleGetterOrSetter(method.kind(), method.name(), method.parameterCount(),
                method.cyclomaticComplexity(), method.codeLines(), method.logicalStatements(),
                method.outboundDistinctCalls(), method.catchClauses());
    }

    static boolean isSimpleGetterOrSetter(String kind, String name, int parameterCount, int cyclomaticComplexity,
                                          int codeLines, int logicalStatements, int outboundDistinctCalls,
                                          int catchClauses) {
        if (!"METHOD".equals(kind)) {
            return false;
        }
        if (cyclomaticComplexity > MAX_CYCLOMATIC
                || codeLines > MAX_CODE_LINES
                || logicalStatements > MAX_LOGICAL_STATEMENTS) {
            return false;
        }
        if (outboundDistinctCalls > 0 || catchClauses > 0) {
            return false;
        }
        return isAccessorName(name, parameterCount);
    }

    /** getX / isX / setX imza kalıbı (gövdeye bakılmaz). */
    static boolean isAccessorName(String name, int parameterCount) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        int params = parameterCount;
        if (name.startsWith("get") && name.length() > 3 && params == 0) {
            return true;
        }
        if (name.startsWith("is") && name.length() > 2 && params == 0) {
            return true;
        }
        return name.startsWith("set") && name.length() > 3 && params == 1;
    }

    /**
     * 1–2 fiziksel satırlık getter/setter/is — isim + satır; skor üretilmez.
     */
    public static boolean isTrivialShortAccessor(String kind, String name, int parameterCount, int physicalLines) {
        return "METHOD".equals(kind)
                && physicalLines > 0
                && physicalLines <= MAX_TRIVIAL_PHYSICAL_LINES
                && isAccessorName(name, parameterCount);
    }

    public static boolean isTrivialShortAccessor(MethodMetric method) {
        if (method == null) {
            return false;
        }
        return isTrivialShortAccessor(method.kind(), method.name(), method.parameterCount(), method.physicalLines());
    }

    /** JSON / Markdown metot listesine yazılmaz (DB ile aynı kural). */
    public static boolean omitFromPublishedReport(MethodMetric method, double minScore) {
        if (method == null) {
            return true;
        }
        if (method.metricsImputed()) {
            return false;
        }
        if (isTrivialShortAccessor(method) || isSimpleGetterOrSetter(method)) {
            return true;
        }
        return method.riskScore() < minScore;
    }
}
