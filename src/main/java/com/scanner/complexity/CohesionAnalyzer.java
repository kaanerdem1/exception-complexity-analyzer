package com.scanner.complexity;

import java.util.List;
import java.util.Set;

/**
 * LCOM3 (Henderson-Sellers) cohesion estimate.
 *
 * <p>For {@code m} methods and {@code a} non-static fields, with {@code mu(f)} the number of
 * methods that touch field {@code f}:</p>
 * <pre>LCOM3 = ((sum(mu(f)) / a) - m) / (1 - m)</pre>
 * <p>0 means every method touches every field (perfectly cohesive); values above 1 flag a class
 * whose methods split into largely unrelated groups of fields — a "God Class" candidate. The
 * result is clamped to [0, 2].</p>
 *
 * <p>Field and name-reference matching is syntactic (no symbol resolution), so a local variable
 * or parameter that shadows a field name can be mistaken for a field access; this is a
 * deliberate, documented trade-off to stay classpath-free.</p>
 */
final class CohesionAnalyzer {

    private CohesionAnalyzer() {
    }

    static double lcom3(Set<String> fieldNames, List<Set<String>> methodFieldAccesses) {
        int a = fieldNames.size();
        int m = methodFieldAccesses.size();
        if (a == 0 || m <= 1) {
            return 0.0;
        }
        long sumMu = 0;
        for (String field : fieldNames) {
            for (Set<String> accessed : methodFieldAccesses) {
                if (accessed.contains(field)) {
                    sumMu++;
                }
            }
        }
        double lcom3 = ((sumMu / (double) a) - m) / (1 - m);
        return Math.max(0.0, Math.min(2.0, lcom3));
    }
}
