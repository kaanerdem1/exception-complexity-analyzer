package com.scanner.complexity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Metod satir araligindan hash (duplicate icin; JavaParser yok). */
final class PmdSourceMethodHashes {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PmdSourceMethodHashes() {
    }

    record Hashes(String bodyHash, String structuralHash) {
        static Hashes empty() {
            return new Hashes("", "");
        }
    }

    static Hashes fromSourceSlice(String source, int startLine, int endLine) {
        if (source == null || source.isEmpty() || startLine < 1 || endLine < startLine) {
            return Hashes.empty();
        }
        String[] lines = source.split("\n", -1);
        int from = Math.min(startLine - 1, lines.length);
        int to = Math.min(endLine, lines.length);
        if (from >= to) {
            return Hashes.empty();
        }
        StringBuilder slice = new StringBuilder();
        for (int i = from; i < to; i++) {
            slice.append(lines[i]).append('\n');
        }
        String body = slice.toString();
        String normalized = normalize(body);
        String structural = normalize(body.replaceAll("\\b[A-Za-z_][A-Za-z0-9_]*\\b", "ID")
                .replaceAll("\\b\\d+(\\.\\d+)?\\b", "LIT")
                .replaceAll("\"([^\"\\\\]|\\\\.)*\"", "LIT")
                .replaceAll("'([^'\\\\]|\\\\.)*'", "LIT"));
        return new Hashes(sha256(normalized), sha256(structural));
    }

    private static String normalize(String text) {
        String noBlock = BLOCK_COMMENT.matcher(text).replaceAll(" ");
        String noLine = LINE_COMMENT.matcher(noBlock).replaceAll(" ");
        return WHITESPACE.matcher(noLine).replaceAll(" ").trim();
    }

    private static String sha256(String normalized) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
