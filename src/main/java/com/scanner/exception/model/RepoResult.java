package com.scanner.exception.model;

import java.util.ArrayList;
import java.util.List;

/** Bir reponun tarama sonucu. */
public final class RepoResult {
    public final RepoInfo repo;
    public int javaFiles;
    /** Aranan sınıfın adı metin olarak geçen Java dosyası sayısı (tanı için) */
    public int filesMentioningTarget;
    /** Ayrıştırılamayan dosya sayısı */
    public int parseFailures;
    public final List<UsageFinding> usages = new ArrayList<UsageFinding>();
    /** Hata yutan catch blokları (boş, sadece log, log + return); kapsamı scan.swallowed.scope belirler */
    public final List<CatchFinding> swallowed = new ArrayList<CatchFinding>();
    public final List<SubclassFinding> subclasses = new ArrayList<SubclassFinding>();
    /** Veritabanından okunan raporlarda alt sınıf listesi yerine sadece sayı bilinir; null ise subclasses.size() */
    public Integer subclassCount;
    public final List<ScanError> errors = new ArrayList<ScanError>();
    /** Ekran, region ve Jasper rapor dosyaları (ebml.enabled=true ise) */
    public final List<EbmlFile> ebmlFiles = new ArrayList<EbmlFile>();

    public RepoResult(RepoInfo repo) {
        this.repo = repo;
    }

    public int subclassCount() {
        return subclassCount != null ? subclassCount.intValue() : subclasses.size();
    }

    public long count(String pattern) {
        long n = 0;
        for (UsageFinding u : usages) if (u.pattern.equals(pattern)) n++;
        return n;
    }


    public long count(EbmlFile.Kind kind) {
        long n = 0;
        for (EbmlFile e : ebmlFiles) if (e.kind == kind) n++;
        return n;
    }
}
