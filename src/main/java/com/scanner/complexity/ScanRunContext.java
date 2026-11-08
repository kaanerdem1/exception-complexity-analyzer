package com.scanner.complexity;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** High-level stages of a scan run (for fatal error reporting). */
enum ScanPhase {
    START,
    DISCOVERY,
    PARSING,
    REPORT_ASSEMBLY,
    WRITE_JSON,
    WRITE_MARKDOWN,
    EXIT_EVAL,
    COMPLETE;

    String labelTr() {
        return switch (this) {
            case START -> "başlangıç";
            case DISCOVERY -> "dosya keşfi";
            case PARSING -> "parse ve metrik";
            case REPORT_ASSEMBLY -> "rapor özeti";
            case WRITE_JSON -> "JSON yazımı";
            case WRITE_MARKDOWN -> "Markdown yazımı";
            case EXIT_EVAL -> "çıkış kodu değerlendirme";
            case COMPLETE -> "tamamlandı";
        };
    }

    String labelEn() {
        return switch (this) {
            case START -> "start";
            case DISCOVERY -> "file discovery";
            case PARSING -> "parse and metrics";
            case REPORT_ASSEMBLY -> "report assembly";
            case WRITE_JSON -> "JSON write";
            case WRITE_MARKDOWN -> "Markdown write";
            case EXIT_EVAL -> "exit code evaluation";
            case COMPLETE -> "complete";
        };
    }
}

/** Tracks scan phase and last file for diagnostics (thread-safe for parallel parse). */
final class ScanRunContext {

    private final AtomicReference<ScanPhase> phase = new AtomicReference<>(ScanPhase.START);
    private final AtomicReference<String> currentFile = new AtomicReference<>();
    private final AtomicReference<String> fatalMessage = new AtomicReference<>();
    private final AtomicReference<ScanPhase> fatalPhase = new AtomicReference<>();
    private final AtomicInteger filesProcessed = new AtomicInteger();

    void phase(ScanPhase next) {
        phase.set(next);
    }

    ScanPhase phase() {
        return phase.get();
    }

    void parsingFile(String relativePath) {
        currentFile.set(relativePath);
    }

    void clearParsingFile() {
        currentFile.set(null);
    }

    void fileFinished() {
        filesProcessed.incrementAndGet();
    }

    int filesProcessed() {
        return filesProcessed.get();
    }

    String currentFile() {
        return currentFile.get();
    }

    void markFatal(ScanPhase at, String message) {
        fatalPhase.compareAndSet(null, at);
        fatalMessage.compareAndSet(null, message);
    }

    boolean fatal() {
        return fatalMessage.get() != null;
    }

    ScanPhase fatalPhase() {
        return fatalPhase.get();
    }

    String fatalMessage() {
        return fatalMessage.get();
    }
}
