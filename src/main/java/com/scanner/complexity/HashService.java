package com.scanner.complexity;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * SHA-256 hashing for the two-stage lazy-parsing cache, using {@link MessageDigest} directly
 * (JDK-only, no external hashing dependency).
 *
 * <ul>
 *   <li>{@link #hashFile(Path)} — raw byte hash of a source file on disk (Stage 1: decides
 *       whether the file needs to be parsed at all).</li>
 *   <li>{@link #normalizedMethodHash(CallableDeclaration)} — hash of a method/constructor body
 *       after comments and incidental whitespace/indentation are normalised away (Stage 2:
 *       decides whether that one method's risk analysis can be skipped).</li>
 *   <li>{@link #structuralHash(BlockStmt)} — identifier/literal-abstracted body hash for
 *       Type-1/2 duplicate grouping ({@link DuplicateDetectionEngine}).</li>
 * </ul>
 */
final class HashService {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private HashService() {
    }

    static String hashFile(Path file) throws IOException {
        return sha256Hex(Files.readAllBytes(file));
    }

    /**
     * Normalises a method/constructor declaration to a comment-free, whitespace-collapsed string
     * and hashes it. The declaration is re-printed from its parsed AST (JavaParser's default
     * pretty-printer), not read verbatim from disk, so two bodies that differ only in original
     * formatting produce identical printed text; comment stripping is applied defensively on top
     * of that (the parser already runs with comment attribution disabled, so comments normally
     * never reach the printed text in the first place).
     */
    /**
     * Stage-1b: yorum/boşluk dışındaki parse edilmiş kaynak özeti (tam analiz atlanmaz; rapor /
     * değişiklik sınıflandırması için).
     */
    static String semanticCompilationUnitHash(CompilationUnit cu) {
        return normalizeSourceText(cu.toString());
    }

    static String normalizedMethodHash(CallableDeclaration<?> declaration) {
        return normalizeSourceText(declaration.toString());
    }

    /** Normalised gövde metni (yorum/boşluk hariç); isim ve literal korunur — Type-1 birebir klon. */
    static String normalizedBodyHash(BlockStmt body) {
        return normalizeSourceText(body.toString());
    }

    static String structuralHash(BlockStmt body) {
        BlockStmt clone = body.clone();
        clone.findAll(NameExpr.class).forEach(n -> n.setName("ID"));
        clone.findAll(FieldAccessExpr.class).forEach(n -> n.setName("ID"));
        clone.findAll(VariableDeclarator.class).forEach(n -> n.setName("ID"));
        clone.findAll(Parameter.class).forEach(n -> n.setName("ID"));
        for (LiteralExpr literal : new ArrayList<>(clone.findAll(LiteralExpr.class))) {
            literal.replace(new NameExpr("LIT"));
        }
        return normalizeSourceText(clone.toString());
    }

    private static String normalizeSourceText(String printed) {
        String noBlockComments = BLOCK_COMMENT.matcher(printed).replaceAll(" ");
        String noComments = LINE_COMMENT.matcher(noBlockComments).replaceAll(" ");
        String normalized = WHITESPACE.matcher(noComments).replaceAll(" ").trim();
        return sha256Hex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }
}
