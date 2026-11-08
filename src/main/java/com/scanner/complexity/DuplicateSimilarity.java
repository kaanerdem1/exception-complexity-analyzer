package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.DuplicateMember;

import java.util.ArrayList;
import java.util.List;

/** Benzerlik yüzdesi ve metod etiketleri (rapor / JSON). */
final class DuplicateSimilarity {

    private DuplicateSimilarity() {
    }

    /**
     * Rapordaki benzerlik yüzdesi (tek ölçek):
     * <ul>
     *   <li>Birebir metin: %100</li>
     *   <li>Yapısal klon: %95 (isim/literal farklı; birebir ile CPD üst bandı arası sabit ağırlık)</li>
     *   <li>CPD: ortak satır ÷ en geniş konum aralığı (1–99); tür eşikleri {@link DuplicateSimilarityKind}</li>
     * </ul>
     */
    static int percent(String similarityType, int duplicatedLines, List<DuplicateMember> members) {
        DuplicateSimilarityKind kind = DuplicateSimilarityKind.fromCode(similarityType);
        if (kind == DuplicateSimilarityKind.BIREBIR_METIN) {
            return 100;
        }
        if (kind == DuplicateSimilarityKind.YAPISAL_KLON) {
            return DuplicateSimilarityKind.YAPISAL_KLON_PERCENT;
        }
        if (kind != null && DuplicateSimilarityKind.isCpdSimilarity(similarityType)) {
            return DuplicateSimilarityKind.lineOverlapPercent(duplicatedLines, members);
        }
        if ("EXACT_TEXT".equals(similarityType)) {
            return 100;
        }
        if ("EXACT_STRUCTURE".equals(similarityType)) {
            return DuplicateSimilarityKind.YAPISAL_KLON_PERCENT;
        }
        return DuplicateSimilarityKind.lineOverlapPercent(duplicatedLines, members);
    }

    static String methodLabel(DuplicateMember member) {
        if (member == null) {
            return "?";
        }
        String type = member.className() == null || member.className().isBlank() || "?".equals(member.className())
                ? "" : member.className().trim();
        String method = simpleMethodName(member.method());
        if (type.isEmpty()) {
            return method;
        }
        if (method.isEmpty() || method.startsWith("(satır")) {
            return type;
        }
        return type + "." + method;
    }

    static String simpleMethodName(String signatureOrName) {
        if (signatureOrName == null || signatureOrName.isBlank()) {
            return "";
        }
        String raw = signatureOrName.trim();
        int paren = raw.indexOf('(');
        String head = paren >= 0 ? raw.substring(0, paren).trim() : raw;
        int space = head.lastIndexOf(' ');
        if (space >= 0) {
            head = head.substring(space + 1);
        }
        int dot = head.lastIndexOf('.');
        if (dot >= 0) {
            head = head.substring(dot + 1);
        }
        return head;
    }

    static String pairHeadline(List<DuplicateMember> members) {
        if (members == null || members.size() < 2) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (DuplicateMember member : members) {
            String label = methodLabel(member);
            if (!label.isEmpty() && !labels.contains(label)) {
                labels.add(label);
            }
        }
        if (labels.size() < 2) {
            if (labels.size() == 1) {
                return "**`" + labels.get(0) + "`** benzer kopyaları (aynı metod imzası, farklı konum).";
            }
            return "";
        }
        String first = labels.get(0);
        StringBuilder rest = new StringBuilder();
        for (int i = 1; i < labels.size(); i++) {
            if (i > 1) {
                rest.append(i == labels.size() - 1 ? " ve " : ", ");
            }
            rest.append("**`").append(labels.get(i)).append("`**");
        }
        if (labels.size() == 2) {
            return "**`" + first + "`** şu metoda benziyor: " + rest + ".";
        }
        return "**`" + first + "`** şu metodlara benziyor: " + rest + ".";
    }
}
