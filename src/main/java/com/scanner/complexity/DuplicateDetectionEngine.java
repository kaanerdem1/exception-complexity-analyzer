package com.scanner.complexity;

import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.DuplicateGroup;
import com.scanner.complexity.AnalysisReport.DuplicateMember;
import com.scanner.complexity.AnalysisReport.FileMetric;

import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
import net.sourceforge.pmd.cpd.Match;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Proje geneli duplicate / near-duplicate tespiti:
 * Type-1 {@link #detectExactTextDuplicates} (gövde metni + literal/isim),
 * Type-2 {@link #detectExactStructureDuplicates} (yapısal hash + aynı imza),
 * Type-3 {@link #detectNearMissDuplicates} (PMD CPD, literal eşleşmeli).
 */
final class DuplicateDetectionEngine {

    private static final int MIN_CC_FOR_DUPLICATE_CHECK = 3;
    /** Metod gövdesi bu satırın altındaysa benzerlik adayı değil. */
    private static final int MIN_LOC_FOR_DUPLICATE_CHECK = 40;
    /** Eşleşen / kopya bölge 40 satırdan kısaysa grup rapora/JSON’a girmez. */
    static final int MIN_DUPLICATED_LINES = 40;

    List<DuplicateGroup> detectExactTextDuplicates(List<FileMetric> files) {
        return groupByHash(files, DuplicateSimilarityKind.BIREBIR_METIN.code(), method -> method.bodyHash());
    }

    List<DuplicateGroup> detectExactStructureDuplicates(List<FileMetric> files) {
        Map<String, List<DuplicateMember>> byHash = new LinkedHashMap<>();
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    if (!qualifiesForDuplicateCheck(method)) {
                        continue;
                    }
                    String structural = method.structuralHash();
                    if (structural == null || structural.isEmpty()) {
                        continue;
                    }
                    String key = structural + "\0" + method.structureDuplicateSignature();
                    byHash.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new DuplicateMember(file.path(), type.name(), method.signature(),
                                    method.startLine(), method.endLine()));
                }
            }
        }
        List<DuplicateGroup> groups = buildGroups(byHash, DuplicateSimilarityKind.YAPISAL_KLON.code(), "YAPISAL-");
        return dropStructureGroupsAlreadyExactText(files, groups);
    }

    /** Aynı gövde metni EXACT_TEXT'te raporlanır; yapısal grupta tekrar etme. */
    private static List<DuplicateGroup> dropStructureGroupsAlreadyExactText(List<FileMetric> files,
                                                                          List<DuplicateGroup> structureGroups) {
        Map<String, MethodMetric> byFileSig = indexMethods(files);
        List<DuplicateGroup> out = new ArrayList<>();
        for (DuplicateGroup group : structureGroups) {
            String sharedBody = null;
            boolean allSameBody = true;
            for (DuplicateMember member : group.members()) {
                MethodMetric m = byFileSig.get(member.file() + "\0" + member.method());
                if (m == null || m.bodyHash().isEmpty()) {
                    allSameBody = false;
                    break;
                }
                if (sharedBody == null) {
                    sharedBody = m.bodyHash();
                } else if (!sharedBody.equals(m.bodyHash())) {
                    allSameBody = false;
                    break;
                }
            }
            if (allSameBody && group.members().size() >= 2) {
                continue;
            }
            out.add(group);
        }
        return out;
    }

    private static Map<String, MethodMetric> indexMethods(List<FileMetric> files) {
        Map<String, MethodMetric> map = new LinkedHashMap<>();
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    map.put(file.path() + "\0" + method.signature(), method);
                }
            }
        }
        return map;
    }

    /** @deprecated use {@link #detectExactTextDuplicates} + {@link #detectExactStructureDuplicates} */
    List<DuplicateGroup> detectExactDuplicates(List<FileMetric> files) {
        List<DuplicateGroup> groups = new ArrayList<>();
        groups.addAll(detectExactTextDuplicates(files));
        groups.addAll(detectExactStructureDuplicates(files));
        return groups;
    }

    List<DuplicateGroup> detectNearMissDuplicates(Path root, int minimumTokens, List<FileMetric> files) {
        Map<String, FileMetric> filesByPath = files.stream()
                .collect(Collectors.toMap(FileMetric::path, f -> f, (a, b) -> a));

        CPDConfiguration config = new CPDConfiguration();
        config.setMinimumTileSize(minimumTokens);
        config.setOnlyRecognizeLanguage(config.getLanguageRegistry().getLanguageById("java"));
        config.setIgnoreIdentifiers(true);
        config.setIgnoreLiterals(false);
        config.setSourceEncoding(StandardCharsets.UTF_8);
        config.addInputPath(root);

        List<DuplicateGroup> groups = new ArrayList<>();
        try (CpdAnalysis cpd = CpdAnalysis.create(config)) {
            cpd.performAnalysis(report -> {
                int groupId = 1;
                for (Match match : report.getMatches()) {
                    List<DuplicateMember> members = new ArrayList<>();
                    for (Mark mark : match.getMarkSet()) {
                        var location = mark.getLocation();
                        Path absoluteFile = Path.of(location.getFileId().getAbsolutePath());
                        int beginLine = location.getStartLine();
                        int endLine = location.getEndLine();
                        String relative = relativize(root, absoluteFile);
                        FileMetric file = filesByPath.get(relative);
                        ResolvedMethod resolved = resolveMethod(file, beginLine, endLine);
                        if (resolved != null && !qualifiesForDuplicateCheck(resolved.method())) {
                            continue;
                        }
                        String className = resolved != null ? resolved.className() : "?";
                        String signature = resolved != null ? resolved.method().signature()
                                : "(satır " + beginLine + "-" + endLine + ")";
                        int reportStart = beginLine;
                        int reportEnd = endLine;
                        if (resolved != null) {
                            reportStart = resolved.method().startLine();
                            reportEnd = resolved.method().endLine();
                        }
                        members.add(new DuplicateMember(relative, className, signature,
                                reportStart, reportEnd));
                    }
                    List<DuplicateMember> consolidated = consolidateMembers(members);
                    int lineCount = match.getLineCount();
                    if (consolidated.size() >= 2 && lineCount >= MIN_DUPLICATED_LINES) {
                        String kind = DuplicateSimilarityKind.classifyCpd(lineCount, consolidated);
                        groups.add(new DuplicateGroup("BENZER-" + groupId++, kind,
                                match.getTokenCount(), lineCount, consolidated));
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("PMD CPD analysis failed", e);
        }
        return groups;
    }

    private static List<DuplicateGroup> groupByHash(List<FileMetric> files, String similarityType,
                                                    java.util.function.Function<MethodMetric, String> hashFn) {
        Map<String, List<DuplicateMember>> byHash = new LinkedHashMap<>();
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    if (!qualifiesForDuplicateCheck(method)) {
                        continue;
                    }
                    String hash = hashFn.apply(method);
                    if (hash == null || hash.isEmpty()) {
                        continue;
                    }
                    byHash.computeIfAbsent(hash, k -> new ArrayList<>())
                            .add(new DuplicateMember(file.path(), type.name(), method.signature(),
                                    method.startLine(), method.endLine()));
                }
            }
        }
        String prefix = DuplicateSimilarityKind.BIREBIR_METIN.code().equals(similarityType)
                ? "BIREBIR-" : "YAPISAL-";
        return buildGroups(byHash, similarityType, prefix);
    }

    private static List<DuplicateGroup> buildGroups(Map<String, List<DuplicateMember>> byHash,
                                                    String similarityType, String idPrefix) {
        List<DuplicateGroup> groups = new ArrayList<>();
        int groupId = 1;
        for (List<DuplicateMember> members : byHash.values()) {
            if (members.size() < 2) {
                continue;
            }
            int duplicatedLines = members.stream()
                    .mapToInt(m -> Math.max(0, m.endLine() - m.startLine() + 1))
                    .max().orElse(0);
            if (duplicatedLines < MIN_DUPLICATED_LINES) {
                continue;
            }
            groups.add(new DuplicateGroup(idPrefix + groupId++, similarityType, 0, duplicatedLines, members));
        }
        return groups;
    }

    private static boolean qualifiesForDuplicateCheck(MethodMetric method) {
        return method.cyclomaticComplexity() >= MIN_CC_FOR_DUPLICATE_CHECK
                || method.codeLines() >= MIN_LOC_FOR_DUPLICATE_CHECK;
    }

    private static ResolvedMethod resolveMethod(FileMetric file, int beginLine, int endLine) {
        if (file == null) {
            return null;
        }
        ResolvedMethod contained = null;
        ResolvedMethod best = null;
        int bestOverlap = 0;
        int rangeSpan = Math.max(1, endLine - beginLine + 1);
        for (ClassMetric type : file.classes()) {
            for (MethodMetric method : type.methods()) {
                int overlap = lineOverlap(method.startLine(), method.endLine(), beginLine, endLine);
                if (overlap <= 0) {
                    continue;
                }
                boolean rangeInsideMethod = beginLine >= method.startLine() && endLine <= method.endLine();
                boolean methodInsideRange = method.startLine() >= beginLine && method.endLine() <= endLine;
                int methodSpan = Math.max(1, method.endLine() - method.startLine() + 1);
                if ((rangeInsideMethod || methodInsideRange) && overlap * 100 / methodSpan >= 50) {
                    contained = new ResolvedMethod(type.name(), method);
                }
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = new ResolvedMethod(type.name(), method);
                }
            }
        }
        if (contained != null) {
            return contained;
        }
        if (best != null && bestOverlap * 100 / rangeSpan >= 40) {
            return best;
        }
        return best;
    }

    private static int lineOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        return Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart) + 1);
    }

    private static String relativize(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        Path relative = normalizedFile.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedFile) : normalizedFile;
        return relative.toString().replace('\\', '/');
    }

    private record ResolvedMethod(String className, MethodMetric method) {
    }

    /** PMD CPD bazen aynı metod için kaydırmalı satır pencereleri üretir; raporda metod başına tek satır. */
    static List<DuplicateMember> consolidateMembers(List<DuplicateMember> members) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        Map<String, DuplicateMember> merged = new LinkedHashMap<>();
        for (DuplicateMember member : members) {
            String key = member.file() + "\0" + member.className() + "\0" + member.method();
            merged.merge(key, member, DuplicateDetectionEngine::unionMemberLines);
        }
        List<DuplicateMember> out = new ArrayList<>(merged.values());
        out.sort(Comparator
                .comparing(DuplicateMember::file)
                .thenComparing(DuplicateMember::className)
                .thenComparing(DuplicateMember::method));
        return out;
    }

    static DuplicateGroup consolidateGroup(DuplicateGroup group) {
        if (group == null) {
            return null;
        }
        return new DuplicateGroup(group.groupId(), group.similarityType(), group.matchedTokenCount(),
                group.duplicatedLines(), group.similarityPercent(), consolidateMembers(group.members()));
    }

    private static DuplicateMember unionMemberLines(DuplicateMember a, DuplicateMember b) {
        return new DuplicateMember(a.file(), a.className(), a.method(),
                Math.min(a.startLine(), b.startLine()), Math.max(a.endLine(), b.endLine()));
    }
}
