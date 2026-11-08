package com.scanner.exception.model;

/** Rapordaki her bulgunun ortak konum bilgileri. */
public abstract class Located {
    public String projectKey = "";
    public String projectName = "";
    public String repo = "";
    public String module = "";
    public String file = "";
    public String className = "";
    /** Dosyanın paketi; className'in paketten sonraki kısmı iç içe sınıf yolunu verir */
    public String packageName = "";
    public String member = "";
    /**
     * Veritabanı eşleştirmesi için üye bilgisi. Anonim sınıf içindeki kullanımlarda
     * anonim sınıfın metodu yerine, onu içeren adlandırılmış sınıftaki metot tutulur.
     */
    public MemberRef memberRef = MemberRef.NONE;

    // ---- Veritabanı eşleştirme sonucu (db.enabled=true ise dolar)
    public Long dbClassId;
    public Long dbMethodId;
    public String matchStatus = "";
    public String matchNote = "";
    public int line;
    public boolean testCode;
    public String link;
}
