package com.scanner.complexity;

import java.util.List;
import java.util.Map;

/**
 * Eski risk YAML'i kalktı. Profil adı yalnız önbellek kimliğinde durur.
 * Metot puanı {@code health-score-catalog.yaml} ile hesaplanır.
 */
public final class RiskProfileLoader {

    /** Önbellek kimliğindeki profil adı. Skoru değiştirmez. */
    public static final String DEFAULT_PROFILE_ID = "enterprise-java-pmd";

    private static final String MODEL_VERSION = "health-v1-external";

    private RiskProfileLoader() {
    }

    public static RiskProfile loadDefaultProfile() {
        return shell(DEFAULT_PROFILE_ID);
    }

    public static RiskCalculator createCalculator(String profileName) {
        String id = profileName == null || profileName.isBlank() ? DEFAULT_PROFILE_ID : profileName.trim();
        return new RiskCalculator(shell(id));
    }

    private static RiskProfile shell(String profileId) {
        return new RiskProfile(profileId, MODEL_VERSION, 0, 0.58, 0.42,
                List.of(), List.of(), List.of(), Map.of(), List.of());
    }
}
