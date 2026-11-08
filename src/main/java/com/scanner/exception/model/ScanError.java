package com.scanner.exception.model;

/** Klonlama, okuma veya ayrıştırma sırasında oluşan hata. */
public final class ScanError {

    /** Hatanın oluştuğu adım. Rapora ve env.exception_scan_error.stage sütununa adıyla yazılır. */
    public enum Stage {
        /** Repo klonlanamadı / güncellenemedi (repo hiç taranmadı) */
        CLONE,
        /** İstenen dal (git.branch) repoda yok, varsayılan dal tarandı (git.branch.fallback=true) */
        BRANCH_FALLBACK,
        /** Repo taranırken beklenmeyen hata */
        SCAN,
        /** Repo için bellek yetmedi */
        OUT_OF_MEMORY,
        /** Repodaki dosyalar listelenemedi */
        FILE_LISTING,
        /** EBML / rapor / process dosyaları listelenemedi */
        EBML_LISTING,
        /** Java dosyası ayrıştırılamadı (kısmen taranmış olabilir) */
        PARSE,
        /** Dosya okunamadı */
        READ,
        /** Dosya scan.max.file.kb sınırından büyük, taranmadı */
        SKIPPED,
        /** processdefinition.xml okunamadı veya label özelliği yok */
        PROCESS_LABEL
    }

    public final String projectKey;
    public final String repo;
    public final Stage stage;
    public final String file;
    public final String message;

    public ScanError(RepoInfo repo, Stage stage, String file, String message) {
        this.projectKey = repo.projectKey;
        this.repo = repo.slug;
        this.stage = stage;
        this.file = file == null ? "" : file;
        this.message = message == null ? "" : message;
    }
}
