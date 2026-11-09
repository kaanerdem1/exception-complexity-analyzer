package com.scanner.complexity;

/**
 * NPath yalnız çok dallı metotta sayılmaz. Satır sayısı bu kesikte yok;
 * uzun ama düz bir metot tek yoldur ve satır kuralı kendi tavanından puanlanır.
 */
final class PmdHeavyMetricGuard {

    static final int CYCLO_HEAVY = 15;

    private PmdHeavyMetricGuard() {
    }

    static boolean skipExplosiveMetrics(int cyclo) {
        return cyclo >= CYCLO_HEAVY;
    }
}
