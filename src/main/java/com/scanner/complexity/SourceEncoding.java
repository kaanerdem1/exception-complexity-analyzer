package com.scanner.complexity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Kaynak {@code .java} okuma: UTF-8 sıkı dene, olmazsa Windows-1254 / ISO-8859-9
 * (eski TR kodlar). Hiçbiri tutmazsa aynı sırayla REPLACE ile okur — dosya genelde
 * taramadan düşmez; yalnızca gerçekten bozuk/ikili içerikte anlamsız karakterler olabilir.
 */
final class SourceEncoding {

    private static final List<Charset> LEGACY_FALLBACKS = List.of(
            Charset.forName("windows-1254"),
            Charset.forName("ISO-8859-9"),
            StandardCharsets.ISO_8859_1);

    private SourceEncoding() {
    }

    static String read(Path file, Charset preferred) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return decode(bytes, preferred);
    }

    static String decode(byte[] bytes, Charset preferred) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        for (Charset charset : candidates(preferred)) {
            try {
                return decodeStrict(bytes, charset);
            } catch (CharacterCodingException ignored) {
                // sonraki aday
            }
        }
        for (Charset charset : candidates(preferred)) {
            return decodeReplacing(bytes, charset);
        }
        return decodeReplacing(bytes, StandardCharsets.UTF_8);
    }

    private static List<Charset> candidates(Charset preferred) {
        Set<Charset> ordered = new LinkedHashSet<>();
        if (preferred != null) {
            ordered.add(preferred);
        }
        ordered.add(StandardCharsets.UTF_8);
        ordered.addAll(LEGACY_FALLBACKS);
        return new ArrayList<>(ordered);
    }

    private static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String decodeReplacing(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("replace decode failed", e);
        }
    }

    static boolean looksLikeMalformedUtf8(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof MalformedInputException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("input length")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
