package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.DuplicateMember;

import java.util.List;
import java.util.Locale;

/**
 * Benzerlik türleri — JSON {@code similarityType} + Türkçe rapor etiketi.
 * Eski kodlar ({@code EXACT_TEXT}, {@code NEAR_MISS}, {@code TOKEN_*}, {@code BLOK_*}) okunurken eşlenir.
 */
enum DuplicateSimilarityKind {

    BIREBIR_METIN(
            "BIREBIR_METIN",
            "Birebir aynı metin",
            "Gövde metni birebir (yorum/boşluk hariç aynı kaynak; sabitler ve isimler dahil)."),
    YAPISAL_KLON(
            "YAPISAL_KLON",
            "Yapısal klon",
            "Aynı metod imzası + aynı kontrol akışı; değişken/literal isimleri farklı olabilir."),
    BENZER_COK_YUKSEK(
            "BENZER_COK_YUKSEK",
            "Çok yüksek benzerlik",
            "PMD CPD: ortak parça metodun ≈%90–99’unu kaplar."),
    BENZER_YUKSEK(
            "BENZER_YUKSEK",
            "Yüksek benzerlik",
            "PMD CPD: ortak parça ≈%75–89."),
    BENZER_ORTA(
            "BENZER_ORTA",
            "Orta benzerlik",
            "PMD CPD: ortak parça ≈%60–74."),
    BENZER_KISMI(
            "BENZER_KISMI",
            "Kısmi benzerlik",
            "PMD CPD: ortak parça ≈%40–59; refaktör adayı.");

    /** CPD sınıflandırma eşikleri — {@link #lineOverlapPercent} ile uyumlu. */
    static final int TIER_COK_YUKSEK_MIN = 90;
    static final int TIER_YUKSEK_MIN = 75;
    static final int TIER_ORTA_MIN = 60;
    /** Raporda yapısal klon sabit yüzdesi (birebir ile CPD üst bandı arasında). */
    static final int YAPISAL_KLON_PERCENT = 95;

    private final String code;
    private final String labelTr;
    private final String descriptionTr;

    DuplicateSimilarityKind(String code, String labelTr, String descriptionTr) {
        this.code = code;
        this.labelTr = labelTr;
        this.descriptionTr = descriptionTr;
    }

    String code() {
        return code;
    }

    String labelTr() {
        return labelTr;
    }

    String descriptionTr() {
        return descriptionTr;
    }

    static String labelTrFor(String similarityType) {
        DuplicateSimilarityKind kind = fromCode(similarityType);
        return kind != null ? kind.labelTr : nullSafe(similarityType);
    }

    static String describeTrFor(String similarityType) {
        DuplicateSimilarityKind kind = fromCode(similarityType);
        return kind != null ? kind.descriptionTr : "";
    }

    static DuplicateSimilarityKind fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String n = raw.trim().toUpperCase(Locale.ROOT);
        return switch (n) {
            case "EXACT_TEXT" -> BIREBIR_METIN;
            case "EXACT_STRUCTURE", "YAPIK_KLON" -> YAPISAL_KLON;
            case "NEAR_MISS" -> BENZER_ORTA;
            case "CPD_COK_YUKSEK", "TOKEN_COK_YUKSEK", "BLOK_COK_YUKSEK", "BENZER_COK_YUKSEK" -> BENZER_COK_YUKSEK;
            case "CPD_YUKSEK", "TOKEN_YUKSEK", "BLOK_YUKSEK", "BENZER_YUKSEK" -> BENZER_YUKSEK;
            case "CPD_ORTA", "TOKEN_ORTA", "BLOK_ORTA", "BENZER_ORTA" -> BENZER_ORTA;
            case "CPD_KISMI", "TOKEN_KISMI", "BLOK_KISMI", "BENZER_KISMI" -> BENZER_KISMI;
            default -> {
                for (DuplicateSimilarityKind k : values()) {
                    if (k.code.equals(n)) {
                        yield k;
                    }
                }
                yield null;
            }
        };
    }

    /**
     * CPD: ortak satır sayısı ÷ üyelerin en geniş satır aralığı (metoda çözülmüşse metod LOC’u).
     */
    static int lineOverlapPercent(int duplicatedLines, List<DuplicateMember> members) {
        int maxSpan = 0;
        if (members != null) {
            for (DuplicateMember member : members) {
                maxSpan = Math.max(maxSpan, Math.max(0, member.endLine() - member.startLine() + 1));
            }
        }
        if (maxSpan <= 0 || duplicatedLines <= 0) {
            return 0;
        }
        return Math.min(99, Math.max(1, (int) Math.round(100.0 * duplicatedLines / maxSpan)));
    }

    static String classifyCpd(int duplicatedLines, List<DuplicateMember> members) {
        return tierCodeForPercent(lineOverlapPercent(duplicatedLines, members));
    }

    static String tierCodeForPercent(int pct) {
        if (pct >= TIER_COK_YUKSEK_MIN) {
            return BENZER_COK_YUKSEK.code;
        }
        if (pct >= TIER_YUKSEK_MIN) {
            return BENZER_YUKSEK.code;
        }
        if (pct >= TIER_ORTA_MIN) {
            return BENZER_ORTA.code;
        }
        return BENZER_KISMI.code;
    }

    /** PMD CPD ile bulunan benzerlikler (hash tabanlı birebir/yapısal değil). */
    static boolean isCpdSimilarity(String similarityType) {
        DuplicateSimilarityKind k = fromCode(similarityType);
        if (k == null) {
            return "NEAR_MISS".equalsIgnoreCase(similarityType);
        }
        return k == BENZER_COK_YUKSEK || k == BENZER_YUKSEK || k == BENZER_ORTA || k == BENZER_KISMI;
    }

    static boolean isExactHashBased(String similarityType) {
        DuplicateSimilarityKind k = fromCode(similarityType);
        return k == BIREBIR_METIN || k == YAPISAL_KLON;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Özet tablo: her tür için [grup sayısı, konum sayısı]. */
    static int[] countGroups(List<com.scanner.complexity.AnalysisReport.DuplicateGroup> groups,
                             DuplicateSimilarityKind kind) {
        int groupCount = 0;
        int memberCount = 0;
        if (groups == null) {
            return new int[] {0, 0};
        }
        for (var group : groups) {
            DuplicateSimilarityKind g = fromCode(group.similarityType());
            if (g == kind) {
                groupCount++;
                memberCount += group.members() == null ? 0 : group.members().size();
            }
        }
        return new int[] {groupCount, memberCount};
    }
}
