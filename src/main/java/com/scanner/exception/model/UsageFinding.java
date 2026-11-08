package com.scanner.exception.model;

import com.scanner.exception.scan.ArgumentClassifier.ArgKind;

/** Aranan desene uyan bir exception oluşturma yeri. */
public final class UsageFinding extends Located {

    /** Kullanımın bağlamı. Rapora ve env.exception_usage.usage_context sütununa adıyla yazılır. */
    public enum Context {
        /** throw new ... */
        THROW,
        /** return new ... */
        RETURN,
        /** Değişkene atanıyor: Ex e = new ...; veya e = new ... */
        ASSIGNMENT,
        /** Bir metot veya constructor çağrısına parametre olarak veriliyor */
        ARGUMENT,
        /** Diğer kullanımlar */
        OTHER
    }

    public String targetClass = "";
    public String pattern = "";
    /** Veritabanındaki kullanım tipi kodu (desenin type değeri) */
    public String typeCode = "";
    public String action = "";
    /** Argümanlardaki ilk sayı (id) */
    public String idValue = "";
    /** Mesajı taşıyan argümanın türü; mesaj yoksa null */
    public ArgKind messageKind;
    public String message = "";
    public String expression = "";
    public Context context = Context.OTHER;
}
