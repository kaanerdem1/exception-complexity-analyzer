package com.scanner.complexity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Türkçe adım logları — PMD-native tarama (stderr). */
final class PmdScanDiagnosticsLog {

    private PmdScanDiagnosticsLog() {
    }

    static void stepStart(String step, String aciklama) {
        System.err.println("[PMD-NATIVE] ▶ ADIM " + step + ": " + aciklama);
    }

    static void stepDone(String step, String ozet) {
        System.err.println("[PMD-NATIVE] ✓ ADIM " + step + " tamam" + (ozet.isEmpty() ? "" : " — " + ozet));
    }

    static void stepWarn(String step, String mesaj) {
        System.err.println("[PMD-NATIVE] ⚠ ADIM " + step + ": " + mesaj);
    }

    static void stepFail(String step, String aciklama, Throwable e) throws IOException {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        System.err.println("[PMD-NATIVE] ✗ ADIM " + step + " HATA (" + aciklama + "): " + detail);
        if (e.getCause() != null && e.getCause() != e) {
            System.err.println("[PMD-NATIVE]   neden: " + e.getCause().getMessage());
        }
        throw new IOException("PMD tarama ADIM " + step + " başarısız: " + aciklama + " — " + detail, e);
    }

    static void logIncrementalBootstrap(Path stateFile, boolean ignoreCache, boolean incremental,
                                      AnalyzerState loaded, String expectedIdentity, boolean hadPreviousScan) {
        if (!incremental) {
            System.err.println("[PMD-NATIVE]   Artımlı önbellek: KAPALI (--state verilmedi veya NO_STATE=1). "
                    + "Her .java dosyası baştan PMD ile ölçülür.");
            return;
        }
        Path abs = stateFile.toAbsolutePath().normalize();
        System.err.println("[PMD-NATIVE]   State dosyası: " + abs);
        if (ignoreCache) {
            System.err.println("[PMD-NATIVE]   --fresh: önceki state yok sayılıyor (soğuk koşu, yine de sonunda yazılır).");
            return;
        }
        boolean exists = Files.isRegularFile(stateFile);
        System.err.println("[PMD-NATIVE]   Dosya diskte " + (exists ? "var" : "yok (ilk koşu veya silinmiş)"));
        if (exists) {
            try {
                long bytes = Files.size(stateFile);
                System.err.printf("[PMD-NATIVE]   Boyut: %,d byte%n", bytes);
            } catch (IOException ignored) {
                // optional
            }
        }
        int fileEntries = loaded.files == null ? 0 : loaded.files.size();
        System.err.println("[PMD-NATIVE]   State içinde kayıtlı .java sayısı: " + fileEntries);
        if (fileEntries > 0 && loaded.cacheIdentity != null) {
            System.err.println("[PMD-NATIVE]   State kimliği: " + loaded.cacheIdentity);
        }
        System.err.println("[PMD-NATIVE]   Bu koşu kimliği: " + expectedIdentity);
        if (hadPreviousScan) {
            System.err.println("[PMD-NATIVE]   Önbellek KULLANILACAK: dosya byte hash aynıysa PMD metrik atlanır.");
        } else if (fileEntries > 0) {
            System.err.println("[PMD-NATIVE]   Önbellek KULLANILMAYACAK: profil/sürüm uyuşmuyor veya state boş — tam skor.");
        } else {
            System.err.println("[PMD-NATIVE]   Önbellek henüz yok — bu koşu sonunda state yazılır.");
        }
    }

    static void logCacheOzet(AnalysisReport.CacheStatistics s) {
        double seconds = s.scanTimeMillis() / 1000.0;
        System.err.println("[PMD-NATIVE] —— Önbellek özeti (bu koşu) ——");
        System.err.printf(Locale.ROOT,
                "[PMD-NATIVE]   Toplam .java: %d | dosya atlandı (byte hash): %d | dosya yeniden ölçüldü: %d%n",
                s.totalFiles(), s.filesSkippedViaFileHash(), s.filesReanalyzed());
        System.err.printf(Locale.ROOT,
                "[PMD-NATIVE]   Metod: yeniden skor %d | önbellekten %d | süre %.1f sn%n",
                s.methodsReanalyzed(), s.methodsSkippedViaHash(), seconds);
        if (s.filesSkippedViaFileHash() == 0 && s.totalFiles() > 0) {
            System.err.println("[PMD-NATIVE]   (Hiç dosya atlanmadı — ilk koşu, FRESH, state yolu değişti veya tüm dosyalar değişmiş olabilir.)");
        }
    }

    /** Risk + persist bittikten sonra: state nerede, neye bakıldı, hangileri tarandı. */
    static void logAfterRiskState(Path stateFile, boolean hadPreviousScan,
                                  AnalysisReport.CacheStatistics cache, IncrementalChanges changes) {
        System.err.println("[ÖNBELLEK] —— Risk bitti; state konumu ve karar ——");
        if (stateFile == null) {
            System.err.println("[ÖNBELLEK]   State yazılmadı (cache kapalı).");
            return;
        }
        Path abs = stateFile.toAbsolutePath().normalize();
        boolean exists = Files.isRegularFile(stateFile);
        System.err.println("[ÖNBELLEK]   State dosyası: " + abs);
        System.err.println("[ÖNBELLEK]   Diskte " + (exists ? "var (oluştu / güncellendi)" : "YOK — yazılamadı"));
        if (exists) {
            try {
                System.err.printf(Locale.ROOT, "[ÖNBELLEK]   Boyut: %,d byte%n", Files.size(stateFile));
            } catch (IOException ignored) {
                // optional
            }
        }
        System.err.println("[ÖNBELLEK]   1) State: analyzer-cache/<proje>/<repo>-pmd-analyzer-state.json");
        System.err.println("[ÖNBELLEK]   2) fileHash (dosyanın tüm byte'ı) aynı → dosya ATLANIR, metodlara bakılmaz.");
        System.err.println("[ÖNBELLEK]   3) fileHash farklı → yalnız o dosya PMD; her metotta bodyHash (=methodHash) aynıysa eski skor, farklıysa yeniden risk.");
        System.err.println("[ÖNBELLEK]   structuralHash cache'de kullanılmaz (benzerlik).");
        if (!hadPreviousScan || cache == null) {
            System.err.println("[ÖNBELLEK]   Bu koşu soğuk: önceki state yok veya kimlik uyuşmadı — tüm keşfedilen .java ölçüldü.");
            return;
        }
        System.err.printf(Locale.ROOT,
                "[ÖNBELLEK]   Bu repoda: %d .java | değişmeyen (atlandı): %d | değişen/yeni (PMD + risk): %d%n",
                cache.totalFiles(), cache.filesSkippedViaFileHash(), cache.filesReanalyzed());
        if (changes != null && changes.comparedToPreviousScan()) {
            System.err.printf(Locale.ROOT,
                    "[ÖNBELLEK]   Diff: dosya +%d -%d ~%d (değişen) | metod +%d -%d ~%d%n",
                    changes.filesAdded(), changes.filesRemoved(), changes.filesModified(),
                    changes.methodsAdded(), changes.methodsRemoved(), changes.methodsModified());
            int shown = 0;
            for (IncrementalChanges.FilePathChange fc : changes.fileChanges()) {
                if (shown >= 25) {
                    System.err.println("[ÖNBELLEK]   … +" + (changes.fileChanges().size() - 25)
                            + " dosya daha (log kısaltıldı)");
                    break;
                }
                System.err.println("[ÖNBELLEK]   tarandı: " + fc.kind() + "  " + fc.path());
                shown++;
            }
        }
        if (cache.filesReanalyzed() == 0 && cache.totalFiles() > 0) {
            System.err.println("[ÖNBELLEK]   Hiç dosya değişmemiş — bu repoda PMD/risk yeniden çalışmadı.");
        }
    }
}
