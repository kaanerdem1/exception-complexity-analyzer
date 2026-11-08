package com.scanner.complexity;

/**
 * PMD'de üstel / pahalı metrikleri (NPATH, Halstead yürüyüşü) skor için gerekli
 * CC / NCSS / cognitive / nesting hesaplandıktan sonra keser.
 */
final class PmdHeavyMetricGuard {

    static final int CYCLO_HEAVY = 15;
    static final int NCSS_HEAVY = 40;

    private PmdHeavyMetricGuard() {
    }

    static boolean skipExplosiveMetrics(int cyclo, int ncss) {
        return cyclo >= CYCLO_HEAVY || ncss >= NCSS_HEAVY;
    }
}
