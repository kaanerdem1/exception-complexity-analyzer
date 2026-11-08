package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.FileMetric;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Similarity (exact/structural duplicate) için PMD {@code bodyHash} yetersizse yalnızca hash alanlarını
 * JavaParser ile doldurur. Risk skoru değiştirilmez.
 */
final class DuplicateHashFallback {

    private DuplicateHashFallback() {
    }

    static List<FileMetric> enrichMissingHashes(List<FileMetric> files, Map<String, Path> relativeToSource,
                                                Charset charset, ParserConfiguration.LanguageLevel languageLevel)
            throws IOException {
        if (files.isEmpty() || relativeToSource.isEmpty()) {
            return files;
        }
        int missing = countMissingBodyHash(files);
        if (missing == 0) {
            return files;
        }
        System.err.printf(
                "[PMD-NATIVE]   ADIM 6/7: PMD bodyHash eksik %d metod — JavaParser yalnızca hash alanları%n",
                missing);
        Map<String, FileMetric> byPath = new HashMap<>();
        for (FileMetric file : files) {
            byPath.put(file.path(), file);
        }
        for (Map.Entry<String, Path> entry : relativeToSource.entrySet()) {
            String relative = entry.getKey();
            Path source = entry.getValue();
            FileMetric file = byPath.get(relative);
            if (file == null || !fileNeedsEnrichment(file)) {
                continue;
            }
            String text = SourceEncoding.read(source, charset);
            FileMetric enriched = ParserDuplicateHashEnrichment.enrich(source, relative, text, file, charset,
                    languageLevel).get(0);
            byPath.put(relative, enriched);
        }
        List<FileMetric> out = new ArrayList<>(files.size());
        for (FileMetric file : files) {
            out.add(byPath.getOrDefault(file.path(), file));
        }
        return out;
    }

    private static boolean fileNeedsEnrichment(FileMetric file) {
        for (AnalysisReport.ClassMetric type : file.classes()) {
            for (MethodMetric method : type.methods()) {
                if (method.bodyHash() == null || method.bodyHash().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countMissingBodyHash(List<FileMetric> files) {
        int n = 0;
        for (FileMetric file : files) {
            for (AnalysisReport.ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    if (method.bodyHash() == null || method.bodyHash().isEmpty()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
